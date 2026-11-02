package com.example.aarogya.googlehealth

import com.example.aarogya.data.AppTime
import com.example.aarogya.data.WeightEntry
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Parses Google Health API v4 `users/me/dataTypes/weight/dataPoints` pages.
 * Each point: `weight.weightGrams` and `weight.sampleTime.physicalTime`, dated in the tracking time zone.
 */
object WeightParser {
    data class Page(val items: List<WeightEntry>, val nextPageToken: String?)

    fun parse(json: String, zone: ZoneId = AppTime.zone): Page {
        val root = JSONObject(json)
        val points = root.optJSONArray("dataPoints")
        val items = buildList {
            if (points != null) for (i in 0 until points.length()) {
                val p = points.getJSONObject(i)
                val w = p.optJSONObject("weight") ?: continue
                val grams = w.optDouble("weightGrams")
                if (grams.isNaN() || grams <= 0) continue
                val sample = w.optJSONObject("sampleTime") ?: continue
                val time = runCatching { Instant.parse(sample.getString("physicalTime")) }.getOrNull() ?: continue
                add(
                    WeightEntry(
                        time = time.toEpochMilli(),
                        day = time.atZone(zone).toLocalDate(), // tracking zone, not where the reading was taken
                        kg = grams / 1000.0,
                        source = sourceLabel(p.optJSONObject("dataSource")),
                    )
                )
            }
        }
        return Page(items, root.optString("nextPageToken").takeIf { it.isNotEmpty() && it != "null" })
    }


    /** Best effort: the docs don't spell out DataSource's fields verbatim, so anything missing falls back. */
    private fun sourceLabel(source: JSONObject?): String {
        if (source == null) return "Google Health"
        val app = source.optJSONObject("application")
        val device = source.optJSONObject("device")
        return listOfNotNull(
            device?.optString("displayName")?.takeIf { it.isNotBlank() },
            device?.optString("model")?.takeIf { it.isNotBlank() },
            app?.optString("displayName")?.takeIf { it.isNotBlank() },
            app?.optString("packageName")?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "Google Health"
    }
}
