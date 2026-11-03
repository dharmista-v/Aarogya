package com.example.aarogya.whoop

import com.example.aarogya.data.AppTime
import com.example.aarogya.data.WhoopDay
import com.example.aarogya.data.WhoopWorkout
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Parses WHOOP v2 API pages into per-day burn. Energy comes as kilojoules (`score.kilojoule`). */
object WhoopParser {
    const val KJ_PER_KCAL = 4.184

    data class Page<T>(val items: List<T>, val nextToken: String?)

    /**
     * Cycles run sleep-to-sleep, not midnight-to-midnight, so each one is assigned to the date of its
     * midpoint (a cycle starting 11 pm Oct 30 is "Oct 31"), in the app's tracking time zone rather than
     * wherever the user was, so travel doesn't split days. The current cycle has no `end`; [now]
     * stands in. Only SCORED cycles carry a score.
     */
    fun cycles(json: String, now: Instant = Instant.now(), zone: ZoneId = AppTime.zone): Page<WhoopDay> {
        val root = JSONObject(json)
        val records = root.optJSONArray("records")
        val days = buildList {
            if (records != null) for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                if (r.optString("score_state") != "SCORED") continue
                val score = r.optJSONObject("score") ?: continue
                if (!score.has("kilojoule") || score.isNull("kilojoule")) continue
                val start = Instant.parse(r.getString("start"))
                val end = r.optString("end").takeIf { it.isNotEmpty() && it != "null" }?.let(Instant::parse) ?: now
                val mid = start.plus(Duration.between(start, end).dividedBy(2))
                add(
                    WhoopDay(
                        day = mid.atZone(zone).toLocalDate(),
                        kcal = score.getDouble("kilojoule") / KJ_PER_KCAL,
                        strain = score.optDouble("strain").takeUnless { it.isNaN() },
                    )
                )
            }
        }
        return Page(days, root.optString("next_token").takeIf { it.isNotEmpty() && it != "null" })
    }

    fun workouts(json: String, zone: ZoneId = AppTime.zone): Page<WhoopWorkout> {
        val root = JSONObject(json)
        val records = root.optJSONArray("records")
        val items = buildList {
            if (records != null) for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                if (r.optString("score_state") != "SCORED") continue
                val score = r.optJSONObject("score") ?: continue
                if (!score.has("kilojoule") || score.isNull("kilojoule")) continue
                val start = Instant.parse(r.getString("start"))
                val end = r.optString("end").takeIf { it.isNotEmpty() && it != "null" }?.let(Instant::parse)
                add(
                    WhoopWorkout(
                        id = r.get("id").toString(),
                        day = start.atZone(zone).toLocalDate(),
                        start = start.toEpochMilli(),
                        sport = sportName(r.optString("sport_name")),
                        kcal = score.getDouble("kilojoule") / KJ_PER_KCAL,
                        strain = score.optDouble("strain").takeUnless { it.isNaN() },
                        minutes = end?.let { Duration.between(start, it).toMinutes().toInt() }?.takeIf { it > 0 },
                        avgHeartRate = score.optInt("average_heart_rate").takeIf { it > 0 },
                        distanceMeters = score.optDouble("distance_meter").takeIf { !it.isNaN() && it > 0 },
                    )
                )
            }
        }
        return Page(items, root.optString("next_token").takeIf { it.isNotEmpty() && it != "null" })
    }

    /** "functional_fitness" -> "Functional fitness", "" -> "Workout" (WHOOP's generic activity). */
    fun sportName(raw: String): String {
        val name = raw.trim().replace('-', ' ').replace('_', ' ').lowercase()
        return if (name.isEmpty() || name == "activity") "Workout" else name.replaceFirstChar { it.uppercase() }
    }

    /** Several cycles can land on one date (e.g. a split night); their burn adds up. */
    fun mergeByDay(days: List<WhoopDay>): List<WhoopDay> = days.groupBy { it.day }.map { (day, list) ->
        WhoopDay(day, list.sumOf { it.kcal }, list.mapNotNull { it.strain }.maxOrNull())
    }
}
