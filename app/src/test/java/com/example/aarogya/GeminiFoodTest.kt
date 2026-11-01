package com.example.aarogya

import com.example.aarogya.gemini.GeminiFood
import com.example.aarogya.gemini.GeminiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GeminiFoodTest {
    @Test fun buildsInlineImageRequestWithJsonSchema() {
        val body = GeminiFood.requestBody(byteArrayOf(1, 2, 3))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val image = parts.getJSONObject(0).getJSONObject("inline_data")
        assertEquals("image/jpeg", image.getString("mime_type"))
        assertEquals("AQID", image.getString("data")) // base64 of 01 02 03
        assertTrue(parts.getJSONObject(1).getString("text").contains("every distinct food"))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("response_mime_type"))
        val item = config.getJSONObject("response_schema").getJSONObject("properties").getJSONObject("items").getJSONObject("items")
        assertEquals(setOf("name", "portion", "grams", "kcal"), item.getJSONObject("properties").keys().asSequence().toSet())
    }

    @Test fun parsesEveryFoodWithItsCalories() {
        val text = """{"items":[
            {"name":"dal tadka","portion":"1 katori","grams":150,"kcal":180},
            {"name":"jeera rice","portion":"1 cup","grams":160,"kcal":250},
            {"name":"roti","portion":"2 rotis","grams":80,"kcal":240},
            {"name":"water","portion":"1 glass","kcal":0},
            {"name":"","portion":"?","kcal":50},
            {"name":"mystery","portion":"?","kcal":-5}
        ]}"""
        // Thinking models may return a reasoning part first; it must be ignored.
        val response = """{"candidates":[{"content":{"parts":[{"text":"Let me look at the plate...","thought":true},{"text":${org.json.JSONObject.quote(text)},"thoughtSignature":"abc"}],"role":"model"},"finishReason":"STOP"}]}"""
        assertEquals(
            listOf(
                GeminiItem("Dal tadka", "1 katori", 150.0, 180.0),
                GeminiItem("Jeera rice", "1 cup", 160.0, 250.0),
                GeminiItem("Roti", "2 rotis", 80.0, 240.0),
                GeminiItem("Water", "1 glass", null, 0.0), // zero-calorie drinks are kept
            ),
            GeminiFood.parseResponse(response),
        )
    }

    @Test fun reportsUsefulErrors() {
        assertEquals(emptyList<GeminiItem>(), GeminiFood.parseResponse("""{"candidates":[{"content":{"parts":[{"text":"{\"items\":[]}"}]}}]}"""))
        val blocked = runCatching { GeminiFood.parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }.exceptionOrNull()
        assertTrue(blocked is IOException)
        assertEquals("Gemini API key is invalid or not allowed — check GEMINI_API_KEY.", GeminiFood.errorMessage(403, "{}"))
        // Only overload/rate-limit/server errors move on to the fallback model.
        assertTrue(com.example.aarogya.gemini.GeminiException(503, "").retryable)
        assertTrue(com.example.aarogya.gemini.GeminiException(429, "").retryable)
        assertTrue(!com.example.aarogya.gemini.GeminiException(400, "").retryable)
        // Real response from the live endpoint for a bad key (400, not 401).
        assertEquals(
            "Gemini API key is invalid or not allowed — check GEMINI_API_KEY.",
            GeminiFood.errorMessage(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""),
        )
        assertEquals("Gemini rejected the request: Image too large.", GeminiFood.errorMessage(400, """{"error":{"code":400,"message":"Image too large."}}"""))
    }
}

class SuggestionTest {
    @Test fun roundTripsThroughStorage() {
        val list = listOf(
            com.example.aarogya.data.Suggestion("Chole bhature", 300.0, 600.0),
            com.example.aarogya.data.Suggestion("Puri", null, 95.0),
        )
        assertEquals(list, com.example.aarogya.data.Suggestion.fromJson(com.example.aarogya.data.Suggestion.toJson(list)))
        assertEquals(emptyList<com.example.aarogya.data.Suggestion>(), com.example.aarogya.data.Suggestion.fromJson(null))
    }
}
