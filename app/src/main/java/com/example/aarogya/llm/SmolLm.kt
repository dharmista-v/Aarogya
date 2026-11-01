package com.example.aarogya.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Minimal greedy-decoding runner for SmolLM2 ONNX exports (transformers.js layout:
 * input_ids / attention_mask / position_ids / past_key_values.N.{key,value} -> logits / present.N.*).
 * Layer and head counts are read from the model itself, so 135M and 360M both work.
 */
class SmolLm(modelFile: File, tokenizerFile: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors())))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modelFile.absolutePath, opts)
    }
    val tokenizer = BpeTokenizer.load(tokenizerFile)

    private val numLayers: Int
    private val kvHeads: Long
    private val headDim: Long
    private val eosIds: Set<Int> = setOfNotNull(tokenizer.tokenId("<|im_end|>"), tokenizer.tokenId("<|endoftext|>"))

    init {
        val keyInputs = session.inputInfo.filterKeys { it.startsWith("past_key_values.") && it.endsWith(".key") }
        numLayers = keyInputs.size
        val shape = (keyInputs.values.first().info as ai.onnxruntime.TensorInfo).shape
        kvHeads = shape[1]
        headDim = shape[3]
    }

    /** ChatML prompt, as used by SmolLM2-Instruct. */
    fun chatPrompt(system: String, turns: List<Pair<String, String>>, user: String): String = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        for ((u, a) in turns) {
            append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
            append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n<|im_start|>assistant\n")
    }

    /**
     * Greedy decode. [shouldStop] sees the text so far after every token (callers use it to stream,
     * cancel, or stop on a blank line). [repetitionPenalty] > 1 discourages loops in longer answers.
     */
    fun generate(
        prompt: String,
        maxNewTokens: Int = 96,
        repetitionPenalty: Float = 1f,
        shouldStop: (String) -> Boolean = { false },
    ): String {
        val promptIds = tokenizer.encode(prompt)
        val generated = ArrayList<Int>()
        var past: Map<String, OnnxTensor> = emptyPast()
        var lastResult: OrtSession.Result? = null
        var cur = promptIds
        var pos = 0L
        try {
            repeat(maxNewTokens) {
                val n = cur.size
                val inputs = HashMap<String, OnnxTensor>(past)
                inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { cur[it].toLong() }), longArrayOf(1, n.toLong()))
                inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray((pos + n).toInt()) { 1L }), longArrayOf(1, pos + n))
                inputs["position_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { pos + it }), longArrayOf(1, n.toLong()))

                val result = session.run(inputs)
                inputs["input_ids"]!!.close(); inputs["attention_mask"]!!.close(); inputs["position_ids"]!!.close()
                if (lastResult == null) past.values.forEach { it.close() } // the initial empty tensors
                lastResult?.close()
                lastResult = result

                val next = argmaxLast(result.get(0) as OnnxTensor, n, generated, repetitionPenalty)
                pos += n
                past = (0 until numLayers).flatMap { i ->
                    listOf(
                        "past_key_values.$i.key" to result.get(1 + 2 * i) as OnnxTensor,
                        "past_key_values.$i.value" to result.get(2 + 2 * i) as OnnxTensor,
                    )
                }.toMap()

                if (next in eosIds) return tokenizer.decode(generated.toIntArray())
                generated += next
                cur = intArrayOf(next)
                if (shouldStop(tokenizer.decode(generated.toIntArray()))) return tokenizer.decode(generated.toIntArray())
            }
        } finally {
            if (lastResult == null) past.values.forEach { it.close() }
            lastResult?.close()
        }
        return tokenizer.decode(generated.toIntArray())
    }

    private fun emptyPast(): Map<String, OnnxTensor> = (0 until numLayers).flatMap { i ->
        listOf("key", "value").map { kind ->
            "past_key_values.$i.$kind" to OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, kvHeads, 0, headDim))
        }
    }.toMap()

    private fun argmaxLast(logits: OnnxTensor, seqLen: Int, generated: List<Int>, penalty: Float): Int {
        val seen = if (penalty != 1f) generated.toHashSet() else emptySet()
        val buf = logits.floatBuffer
        val vocab = (logits.info.shape[2]).toInt()
        val offset = (seqLen - 1) * vocab
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
        session.close()
    }
}
