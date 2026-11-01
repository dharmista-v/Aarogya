package com.example.aarogya.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Labels for a photo; [dishLevel] is false when they came from ML Kit's generic (non-food) labeler. */
data class PhotoLabels(val labels: List<String>, val dishLevel: Boolean)

/** Stores meal photos (downscaled) and asks ML Kit what's in them. */
class FoodPhotos(private val context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val genericLabeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()) }
    private var dishLabeler: Pair<String, ImageLabeler>? = null

    /** Dish-level labeler from the downloaded food model; low threshold because the top few guesses are offered as choices. */
    private fun dishLabeler(model: File): ImageLabeler {
        dishLabeler?.takeIf { it.first == model.absolutePath }?.let { return it.second }
        val options = CustomImageLabelerOptions.Builder(LocalModel.Builder().setAbsoluteFilePath(model.absolutePath).build())
            .setConfidenceThreshold(0.08f)
            .setMaxResultCount(4)
            .build()
        return ImageLabeling.getClient(options).also { dishLabeler = model.absolutePath to it }
    }

    fun newCameraFile(): File = File(dir, "cam_${System.currentTimeMillis()}.jpg")

    /** Copies/downscales the picked or captured image into app storage and returns its path. */
    suspend fun importPhoto(source: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            val bmp = context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val rotation = context.contentResolver.openInputStream(source)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
            val upright = if (rotation != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) else bmp
            val out = File(dir, "meal_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            out.absolutePath
        }.getOrNull()
    }

    /**
     * Food labels for a photo, most confident first. Uses the dish recognizer when [dishModel] is
     * downloaded (names like "Chole bhature", "Enchilada"), otherwise ML Kit's generic labels.
     */
    suspend fun labels(path: String, dishModel: File?): PhotoLabels = withContext(Dispatchers.IO) {
        val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(File(path))) }.getOrNull()
            ?: return@withContext PhotoLabels(emptyList(), dishLevel = false)
        val dishes = dishModel?.let { model -> runCatching { dishLabeler(model) }.getOrNull()?.let { run(it, image) } }.orEmpty()
        val dishLevel = dishes.isNotEmpty()
        val labels = dishes.ifEmpty { run(genericLabeler, image) }
            .sortedByDescending { it.confidence }
            .map { it.text }
            .filter { it.lowercase() !in GENERIC }
        PhotoLabels(labels, dishLevel)
    }

    private suspend fun run(labeler: ImageLabeler, image: InputImage): List<ImageLabel> = suspendCancellableCoroutine { cont ->
        labeler.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    fun delete(path: String?) {
        if (path != null && path.startsWith(dir.absolutePath)) File(path).delete()
    }

    companion object {
        private const val MAX_EDGE = 1280
        private val GENERIC = setOf(
            "food", "dish", "cuisine", "tableware", "plate", "recipe", "ingredient", "meal", "cutlery", "table",
            "bowl", "fast food", "junk food", "dishware", "serveware", "produce", "natural foods", "staple food",
            "__background__", "hand", "finger", "room", "sitting", "lunch", "dinner", "breakfast", "cool", "fun", "smile", "pattern",
        )
    }
}
