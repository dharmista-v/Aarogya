package com.example.aarogya.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SmolVLM-256M: describes what's on the plate in a photo. */
object PlateVisionModel : Downloadable {
    override val id = "smolvlm-256m"
    override val title = "Plate analyzer"
    override val sizeMb = 263
    const val VISION = "vision_encoder_int8.onnx"
    const val EMBED = "embed_tokens_int8.onnx"
    const val DECODER = "decoder_model_merged_int8.onnx"
    private const val REPO = "https://huggingface.co/HuggingFaceTB/SmolVLM-256M-Instruct/resolve/main"
    override val files = listOf(
        "$REPO/onnx/$VISION" to VISION,
        "$REPO/onnx/$EMBED" to EMBED,
        "$REPO/onnx/$DECODER" to DECODER,
        "$REPO/${ModelManager.TOKENIZER_FILE}" to ModelManager.TOKENIZER_FILE,
    )
}

/** Keeps SmolVLM loaded between photos. [onLoad] lets the app unload other models first to cap memory. */
class PlateAi(private val models: ModelManager, private val onLoad: () -> Unit) {
    private val lock = Mutex()
    private var vlm: SmolVlm? = null
    // Closing a session mid-inference crashes natively, so a release requested while busy waits for the run to end.
    private var busy = false
    private var releaseWhenIdle = false

    suspend fun describe(photoPath: String): String = withContext(Dispatchers.Default) {
        lock.withLock {
            synchronized(this@PlateAi) { busy = true }
            try {
                describeLocked(photoPath)
            } finally {
                synchronized(this@PlateAi) { busy = false; if (releaseWhenIdle) release() }
            }
        }
    }

    private suspend fun describeLocked(photoPath: String): String {
        val model = vlm ?: run {
            onLoad()
            val m = PlateVisionModel
            SmolVlm(
                models.file(m, PlateVisionModel.VISION), models.file(m, PlateVisionModel.EMBED),
                models.file(m, PlateVisionModel.DECODER), models.file(m, ModelManager.TOKENIZER_FILE),
            ).also { vlm = it }
        }
        val pixels = loadPixels(photoPath) ?: return ""
        val ctx = kotlin.coroutines.coroutineContext
        return model.ask(pixels, QUESTION) { ctx.ensureActive(); false }.trim()
    }

    @Synchronized
    fun release() {
        if (busy) { releaseWhenIdle = true; return }
        releaseWhenIdle = false
        vlm?.close()
        vlm = null
    }

    /** Photo -> 512×512 (stretched, as the Idefics3 processor does without tiling) -> normalized CHW floats. */
    private fun loadPixels(path: String): FloatArray? {
        val size = SmolVlm.IMAGE_SIZE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, size, size, true)
        val argb = IntArray(size * size)
        scaled.getPixels(argb, 0, size, 0, 0, size, size)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return SmolVlm.normalize(argb)
    }

    companion object {
        const val QUESTION = "Name the foods and dishes in this photo as a short comma-separated list."
    }
}
