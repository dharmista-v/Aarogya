package com.example.aarogya.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * SmolVLM-256M-Instruct over its three ONNX exports (transformers.js layout):
 * vision_encoder (512×512 image -> 64 feature vectors), embed_tokens, and decoder_model_merged.
 * The image token embeddings are replaced by the vision features, then decoding works like [SmolLm].
 * One un-tiled 512 px image keeps it fast enough for a phone.
 */
class SmolVlm(visionFile: File, embedFile: File, decoderFile: File, tokenizerFile: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private fun session(f: File): OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors())))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(f.absolutePath, opts)
    }
    private val vision = session(visionFile)
    private val embed = session(embedFile)
    private val decoder = session(decoderFile)
    val tokenizer = BpeTokenizer.load(tokenizerFile)

    private val numLayers = decoder.inputInfo.keys.count { it.startsWith("past_key_values.") && it.endsWith(".key") }
    private val kvShape = (decoder.inputInfo.getValue("past_key_values.0.key").info as ai.onnxruntime.TensorInfo).shape
    private val imageTokenId = tokenizer.tokenId("<image>")!!
    private val stopIds = setOfNotNull(tokenizer.tokenId("<end_of_utterance>"), tokenizer.tokenId("<|endoftext|>"), tokenizer.tokenId("<|im_end|>"))

    /** Idefics3 chat format with a single global image of [IMAGE_SEQ_LEN] tokens. */
    fun prompt(question: String): String =
        "<|im_start|>User:<fake_token_around_image><global-img>" + "<image>".repeat(IMAGE_SEQ_LEN) +
            "<fake_token_around_image>" + question + "<end_of_utterance>\nAssistant:"

    /**
     * Answers [question] about an image given as normalized CHW floats ([pixels], see [normalize]).
     * Greedy decoding with a repetition penalty — without it the 256M model loops on lists.
     */
    fun ask(pixels: FloatArray, question: String, maxNewTokens: Int = 40, repetitionPenalty: Float = 1.3f, shouldStop: (String) -> Boolean = { false }): String {
        val ids = tokenizer.encode(prompt(question))
        val features = runVision(pixels) // [64 * hidden]
        val hidden = features.size / IMAGE_SEQ_LEN

        // Prompt embeddings with image positions swapped for vision features.
        val embeds = embedIds(ids)
        var f = 0
        for (i in ids.indices) if (ids[i] == imageTokenId) {
            System.arraycopy(features, f * hidden, embeds, i * hidden, hidden); f++
        }

        val generated = ArrayList<Int>()
        var curEmbeds = embeds
        var curLen = ids.size
        var pos = 0L
        var past: Map<String, OnnxTensor> = emptyPast()
        var lastResult: OrtSession.Result? = null
        try {
            repeat(maxNewTokens) {
                val inputs = HashMap<String, OnnxTensor>(past)
                inputs["inputs_embeds"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(curEmbeds), longArrayOf(1, curLen.toLong(), hidden.toLong()))
                inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray((pos + curLen).toInt()) { 1L }), longArrayOf(1, pos + curLen))
                inputs["position_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(curLen) { pos + it }), longArrayOf(1, curLen.toLong()))

                val result = decoder.run(inputs)
                inputs["inputs_embeds"]!!.close(); inputs["attention_mask"]!!.close(); inputs["position_ids"]!!.close()
                if (lastResult == null) past.values.forEach { it.close() }
                lastResult?.close()
                lastResult = result

                val next = pickNext(result.get(0) as OnnxTensor, curLen, generated, repetitionPenalty)
                pos += curLen
                past = (0 until numLayers).flatMap { i ->
                    listOf(
                        "past_key_values.$i.key" to result.get(1 + 2 * i) as OnnxTensor,
                        "past_key_values.$i.value" to result.get(2 + 2 * i) as OnnxTensor,
                    )
                }.toMap()

                if (next in stopIds) return tokenizer.decode(generated.toIntArray())
                generated += next
                curEmbeds = embedIds(intArrayOf(next))
                curLen = 1
                if (shouldStop(tokenizer.decode(generated.toIntArray()))) return tokenizer.decode(generated.toIntArray())
            }
        } finally {
            if (lastResult == null) past.values.forEach { it.close() }
            lastResult?.close()
        }
        return tokenizer.decode(generated.toIntArray())
    }

    private fun runVision(pixels: FloatArray): FloatArray {
        val size = IMAGE_SIZE.toLong()
        val mask = ByteBuffer.allocateDirect(IMAGE_SIZE * IMAGE_SIZE).apply { repeat(IMAGE_SIZE * IMAGE_SIZE) { put(1) }; rewind() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 1, 3, size, size)).use { pv ->
            OnnxTensor.createTensor(env, mask, longArrayOf(1, 1, size, size), ai.onnxruntime.OnnxJavaType.BOOL).use { pm ->
                vision.run(mapOf("pixel_values" to pv, "pixel_attention_mask" to pm)).use { r ->
                    val buf = (r.get(0) as OnnxTensor).floatBuffer
                    return FloatArray(buf.remaining()).also { buf.get(it) }
                }
            }
        }
    }

    private fun embedIds(ids: IntArray): FloatArray =
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong())).use { t ->
            embed.run(mapOf("input_ids" to t)).use { r ->
                val buf = (r.get(0) as OnnxTensor).floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }

    private fun emptyPast(): Map<String, OnnxTensor> = (0 until numLayers).flatMap { i ->
        listOf("key", "value").map { kind ->
            "past_key_values.$i.$kind" to OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvShape[1], 0, kvShape[3]))
        }
    }.toMap()

    private fun pickNext(logits: OnnxTensor, seqLen: Int, generated: List<Int>, penalty: Float): Int {
        val buf = logits.floatBuffer
        val vocab = logits.info.shape[2].toInt()
        val offset = (seqLen - 1) * vocab
        val seen = generated.toHashSet()
        var best = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (v in 0 until vocab) {
            var x = buf.get(offset + v)
            if (v in seen) x = if (x > 0) x / penalty else x * penalty
            if (x > bestVal) { bestVal = x; best = v }
        }
        return best
    }

    override fun close() {
        vision.close(); embed.close(); decoder.close()
    }

    companion object {
        const val IMAGE_SIZE = 512
        const val IMAGE_SEQ_LEN = 64

        /** 512×512 ARGB pixels -> CHW floats scaled to [-1, 1] (rescale 1/255, mean 0.5, std 0.5). */
        fun normalize(argb: IntArray): FloatArray {
            val plane = IMAGE_SIZE * IMAGE_SIZE
            require(argb.size == plane)
            val out = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = argb[i]
                out[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
                out[plane + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                out[2 * plane + i] = (p and 0xFF) / 127.5f - 1f
            }
            return out
        }
    }
}
