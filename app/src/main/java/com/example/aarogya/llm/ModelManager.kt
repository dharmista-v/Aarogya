package com.example.aarogya.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.io.File

/** Something the app downloads on demand: a folder of files fetched from fixed URLs. */
interface Downloadable {
    val id: String
    val title: String
    val sizeMb: Int
    /** remote URL -> local file name */
    val files: List<Pair<String, String>>
}

enum class ModelOption(
    override val id: String,
    override val title: String,
    val blurb: String,
    override val sizeMb: Int,
    val repo: String,
) : Downloadable {
    STANDARD("smollm2-360m", "SmolLM2 360M", "Recommended · better at messy descriptions", 365, "HuggingFaceTB/SmolLM2-360M-Instruct"),
    LITE("smollm2-135m", "SmolLM2 135M", "Lite · smaller and faster, less accurate", 137, "HuggingFaceTB/SmolLM2-135M-Instruct");

    override val files: List<Pair<String, String>>
        get() = listOf(
            "https://huggingface.co/$repo/resolve/main/onnx/${ModelManager.MODEL_FILE}" to ModelManager.MODEL_FILE,
            "https://huggingface.co/$repo/resolve/main/${ModelManager.TOKENIZER_FILE}" to ModelManager.TOKENIZER_FILE,
        )
}

/** Google's AIY food classifier: ~2,000 dishes, incl. many Indian and Mexican ones. Labels are embedded. */
object FoodVisionModel : Downloadable {
    override val id = "food-v1"
    override val title = "Dish recognizer"
    override val sizeMb = 21
    const val FILE = "food_v1.tflite"
    override val files = listOf("https://tfhub.dev/google/lite-model/aiy/vision/classifier/food_V1/1?lite-format=tflite" to FILE)
}

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val progress: Float) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

/** Downloads optional models on demand via the system DownloadManager (survives app restarts, shows a notification). */
class ModelManager(private val context: Context) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    private fun dir(option: Downloadable) = File(context.getExternalFilesDir("models"), option.id)
    fun file(option: Downloadable, name: String) = File(dir(option), name)
    fun modelFile(option: ModelOption) = file(option, MODEL_FILE)
    fun tokenizerFile(option: ModelOption) = file(option, TOKENIZER_FILE)
    private fun readyMarker(option: Downloadable) = File(dir(option), ".ready")

    fun status(option: Downloadable): ModelStatus {
        if (readyMarker(option).exists()) return ModelStatus.Ready
        val ids = downloadIds(option)
        if (ids.isEmpty()) return ModelStatus.NotInstalled

        var done = 0L
        var total = 0L
        var allOk = true
        dm.query(DownloadManager.Query().setFilterById(*ids)).use { c ->
            if (c.count < ids.size) { clear(option); return ModelStatus.Failed("Download was cancelled") }
            while (c.moveToNext()) {
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        clear(option)
                        return ModelStatus.Failed("Download failed (code $reason)")
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> Unit
                    else -> allOk = false
                }
                done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                total += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
            }
        }
        if (allOk && option.files.all { (_, name) -> file(option, name).length() > 0 }) {
            readyMarker(option).createNewFile()
            prefs.edit { remove(option.id) }
            return ModelStatus.Ready
        }
        val expected = maxOf(total, option.sizeMb * 1_000_000L)
        return ModelStatus.Downloading((done.toFloat() / expected).coerceIn(0f, 1f))
    }

    fun download(option: Downloadable) {
        clear(option)
        dir(option).mkdirs()
        val ids = option.files.map { (url, local) ->
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("${option.title} for Aarogya")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, "models", "${option.id}/$local")
            dm.enqueue(req)
        }
        prefs.edit { putString(option.id, ids.joinToString(",")) }
    }

    /** Cancels any in-flight download and deletes the files. */
    fun clear(option: Downloadable) {
        downloadIds(option).takeIf { it.isNotEmpty() }?.let { dm.remove(*it) }
        prefs.edit { remove(option.id) }
        dir(option).deleteRecursively()
    }

    private fun downloadIds(option: Downloadable): LongArray =
        prefs.getString(option.id, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toLongArray() ?: LongArray(0)

    companion object {
        const val MODEL_FILE = "model_int8.onnx"
        const val TOKENIZER_FILE = "tokenizer.json"
    }
}
