package com.example.aarogya.gemini

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** One food Gemini found in a photo, with its own calorie estimate for the visible portion. */
data class GeminiItem(val name: String, val portion: String, val grams: Double?, val kcal: Double)

/** An HTTP failure; [retryable] errors (overload, rate limit, server) may succeed on another model. */
class GeminiException(val code: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = code == 429 || code >= 500
}

/**
 * Sends a meal photo to the Gemini API (REST generateContent, structured JSON output) and returns
 * every food/drink it sees with a per-item calorie estimate. Pure JVM so it can be unit-tested.
 */
class GeminiFood(private val apiKey: String, private val models: List<String>) {

    /**
     * Tries each model in order, moving on only when one is overloaded, rate-limited or erroring.
     * Demand spikes are short, so if every model is busy it waits briefly and goes round once more.
     */
    fun analyze(jpeg: ByteArray): List<GeminiItem> = send(requestBody(jpeg).toString().toByteArray())

    /** Same as [analyze] for a typed description ("veg thali", "a plate of that spicy paneer thing"). */
    fun analyzeText(description: String): List<GeminiItem> = send(textRequestBody(description).toString().toByteArray())

    private fun send(body: ByteArray): List<GeminiItem> {
        var last: GeminiException? = null
        repeat(2) { round ->
            if (round > 0) Thread.sleep(RETRY_DELAY_MS)
            for (model in models.distinct()) {
                try {
                    return call(model, body)
                } catch (e: GeminiException) {
                    if (!e.retryable) throw e
                    last = e
                }
            }
        }
        throw last ?: IOException("No Gemini model configured.")
    }

    private fun call(model: String, body: ByteArray): List<GeminiItem> {
        val conn = (URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey) // header, so the key never appears in URLs or logs
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw GeminiException(code, errorMessage(code, text))
            return parseResponse(text)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Used when the configured model is overloaded (503s happen during demand spikes). */
        const val FALLBACK_MODEL = "gemini-3.5-flash-lite"
        private const val RETRY_DELAY_MS = 2_000L

        const val PROMPT =
            "Identify every distinct food and drink visible in this meal photo. Use specific dish names, " +
                "including regional names where they apply (for example \"dal tadka\", \"aloo paratha\", \"masala dosa\", " +
                "\"chicken enchiladas\", \"pozole\"). List each item separately (a thali is several items). For each item give " +
                "the visible portion in everyday words (for example \"2 rotis\", \"1 katori\", \"1 cup\"), its estimated weight " +
                "in grams, and estimated calories (kcal) for that portion as served, including cooking oil or ghee. " +
                "If the photo contains no food, return an empty list."

        const val TEXT_PROMPT =
            "A person described what they ate. Split it into distinct foods and drinks using specific dish names " +
                "(regional names where they apply). For each give the portion in everyday words, estimated weight in grams " +
                "and estimated calories (kcal) for that portion as typically served in a home or restaurant. " +
                "If no portion is given assume one normal serving. If it is not food, return an empty list. Description: "

        fun requestBody(jpeg: ByteArray): JSONObject = body(
            JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.getEncoder().encodeToString(jpeg))))
                .put(JSONObject().put("text", PROMPT)),
        )

        fun textRequestBody(description: String): JSONObject =
            body(JSONArray().put(JSONObject().put("text", TEXT_PROMPT + description.trim())))

        private fun body(parts: JSONArray): JSONObject {
            val item = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties", JSONObject()
                        .put("name", JSONObject().put("type", "STRING"))
                        .put("portion", JSONObject().put("type", "STRING"))
                        .put("grams", JSONObject().put("type", "NUMBER"))
                        .put("kcal", JSONObject().put("type", "NUMBER")),
                )
                .put("required", JSONArray(listOf("name", "portion", "kcal")))
            val schema = JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().put("items", JSONObject().put("type", "ARRAY").put("items", item)))
                .put("required", JSONArray(listOf("items")))
            return JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put(
                    "generationConfig", JSONObject()
                        .put("response_mime_type", "application/json")
                        .put("response_schema", schema)
                        .put("temperature", 0.2),
                )
        }

        /** Reads candidates[0].content.parts[*].text (skipping "thought" parts), which holds the JSON matching our schema. */
        fun parseResponse(body: String): List<GeminiItem> {
            val candidate = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                ?: throw IOException("Gemini returned no answer (the photo may have been blocked).")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
            val text = (0 until parts.length()).map { parts.getJSONObject(it) }
                .filterNot { it.optBoolean("thought") }
                .joinToString("") { it.optString("text") }
                .trim()
            if (text.isEmpty()) return emptyList()
            val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val name = o.optString("name").trim()
                val kcal = o.optDouble("kcal")
                if (name.isEmpty() || kcal.isNaN() || kcal < 0) return@mapNotNull null
                GeminiItem(
                    name = name.replaceFirstChar { it.uppercase() },
                    portion = o.optString("portion").trim(),
                    grams = o.optDouble("grams").takeIf { !it.isNaN() && it > 0 },
                    kcal = kcal,
                )
            }
        }

        fun errorMessage(code: Int, body: String): String {
            val detail = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
            return when (code) {
                // Google reports a bad key as 400 INVALID_ARGUMENT, not 401.
                400 if detail.contains("API key", ignoreCase = true) -> "Gemini API key is invalid or not allowed — check GEMINI_API_KEY."
                400 -> "Gemini rejected the request${if (detail.isNotEmpty()) ": $detail" else "."}"
                401, 403 -> "Gemini API key is invalid or not allowed — check GEMINI_API_KEY."
                404 -> "Gemini model not found — check GEMINI_MODEL."
                429 -> "Gemini quota reached — try again shortly."
                503 -> "Gemini is overloaded right now — try again in a minute."
                in 500..599 -> "Gemini is unavailable right now — try again."
                else -> "Gemini error $code"
            }
        }
    }
}
