package com.example.aarogya

import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.DayTotals
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.Meal
import com.example.aarogya.data.Prefs
import com.example.aarogya.data.WhoopDay
import com.example.aarogya.whoop.WhoopParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WhoopParserTest {
    private val CHICAGO = java.time.ZoneOffset.of("-05:00")

    // Sample cycle from developer.whoop.com (v2 /cycle), plus an in-progress and an unscored cycle.
    private val cyclesJson = """
        {"records": [
          {"id": 93846, "user_id": 10129, "start": "2022-04-25T03:10:00.000Z", "timezone_offset": "-05:00",
           "score_state": "SCORED", "score": {"strain": 3.1, "kilojoule": 4184.0, "average_heart_rate": 60, "max_heart_rate": 101}},
          {"id": 93845, "user_id": 10129, "created_at": "2022-04-24T11:25:44.774Z", "updated_at": "2022-04-24T14:25:44.774Z",
           "start": "2022-04-24T02:25:44.774Z", "end": "2022-04-24T10:25:44.774Z", "timezone_offset": "-05:00",
           "score_state": "SCORED", "score": {"strain": 5.2951527, "kilojoule": 8288.297, "average_heart_rate": 68, "max_heart_rate": 141}, "step_count": 8234},
          {"id": 93844, "start": "2022-04-23T03:00:00.000Z", "end": "2022-04-24T02:25:44.774Z", "timezone_offset": "-05:00", "score_state": "PENDING_SCORE"}
        ], "next_token": "MTIzOjEyMzEyMw"}
    """.trimIndent()

    @Test fun parsesScoredCyclesIntoLocalDaysInKcal() {
        val page = WhoopParser.cycles(cyclesJson, now = Instant.parse("2022-04-25T15:10:00Z"), zone = CHICAGO)
        assertEquals("MTIzOjEyMzEyMw", page.nextToken)
        assertEquals(2, page.items.size) // PENDING_SCORE has no score and is skipped

        // In-progress cycle (no end): 22:10 Apr 24 local -> now 10:10 Apr 25 local; midpoint lands on Apr 25.
        val current = page.items[0]
        assertEquals(LocalDate.of(2022, 4, 25), current.day)
        assertEquals(1000.0, current.kcal, 0.001) // 4184 kJ = 1000 kcal

        // Docs sample: 21:25 Apr 23 -> 05:25 Apr 24 local (-05:00); midpoint 01:25 Apr 24.
        val sample = page.items[1]
        assertEquals(LocalDate.of(2022, 4, 24), sample.day)
        assertEquals(8288.297 / 4.184, sample.kcal, 0.001)
        assertEquals(5.2951527, sample.strain!!, 1e-6)
    }

    @Test fun parsesWorkoutsAndLastPage() {
        val json = """
            {"records": [{"id": "ecfc6a15-4661-442f-a9a4-f160dd7afae8", "v1_id": 1043, "user_id": 9012,
              "start": "2022-04-24T02:25:44.774Z", "end": "2022-04-24T10:25:44.774Z", "timezone_offset": "-05:00",
              "sport_name": "running", "score_state": "SCORED",
              "score": {"strain": 8.2463, "average_heart_rate": 123, "max_heart_rate": 146, "kilojoule": 1569.34033203125, "percent_recorded": 100}, "sport_id": 1}],
             "next_token": null}
        """.trimIndent()
        val page = WhoopParser.workouts(json, zone = CHICAGO)
        assertNull(page.nextToken)
        val w = page.items.single()
        assertEquals("Running", w.sport)
        assertEquals(LocalDate.of(2022, 4, 23), w.day) // 21:25 local on Apr 23
        assertEquals(375.08, w.kcal, 0.01)
        assertEquals(480, w.minutes)          // 02:25 -> 10:25
        assertEquals(123, w.avgHeartRate)
        assertEquals(null, w.distanceMeters)  // not in this sample
    }

    @Test fun travelKeepsCyclesOnTheTrackingZonesDay() {
        // Cycle recorded in India (+05:30); midpoint 2026-10-30T20:00Z is Oct 30 in Chicago but Oct 31 in India.
        val json = """
            {"records": [{"id": 1, "start": "2026-10-30T08:00:00Z", "end": "2026-10-31T08:00:00Z", "timezone_offset": "+05:30",
              "score_state": "SCORED", "score": {"strain": 9.0, "kilojoule": 8368.0}}]}
        """.trimIndent()
        assertEquals(LocalDate.of(2026, 10, 30), WhoopParser.cycles(json, zone = CHICAGO).items.single().day)
        assertEquals(LocalDate.of(2026, 10, 31), WhoopParser.cycles(json, zone = java.time.ZoneId.of("Asia/Kolkata")).items.single().day)
    }

    @Test fun readsDistanceAndNamesSports() {
        val json = """
            {"records": [{"id": "a", "start": "2026-11-01T23:00:00Z", "end": "2026-11-01T23:52:00Z", "timezone_offset": "-06:00",
              "sport_name": "badminton", "score_state": "SCORED",
              "score": {"strain": 11.2, "average_heart_rate": 138, "kilojoule": 1715.4, "distance_meter": 3120.5}}]}
        """.trimIndent()
        val w = WhoopParser.workouts(json, zone = CHICAGO).items.single()
        assertEquals("Badminton", w.sport)
        assertEquals(52, w.minutes)
        assertEquals(138, w.avgHeartRate)
        assertEquals(3120.5, w.distanceMeters!!, 1e-9)
        assertEquals(410.0, w.kcal, 0.1)
        assertEquals("Functional fitness", WhoopParser.sportName("functional_fitness"))
        assertEquals("Workout", WhoopParser.sportName(""))
        assertEquals("Workout", WhoopParser.sportName("activity"))
    }

    @Test fun mergesCyclesOnTheSameDay() {
        val d = LocalDate.of(2026, 10, 30)
        val merged = WhoopParser.mergeByDay(listOf(WhoopDay(d, 1200.0, 4.0), WhoopDay(d, 900.0, 9.5)))
        assertEquals(listOf(WhoopDay(d, 2100.0, 9.5)), merged)
    }

    @Test fun whoopBurnReplacesBaseBurnInAnalysis() {
        val day = LocalDate.of(2026, 10, 30)
        val food = FoodEntry(day = day, time = 0, meal = Meal.LUNCH, name = "Biryani", grams = 300.0, kcal = 1800.0, photo = null, source = "text")
        val a = Analyzer.analyze(listOf(food), emptyList(), Prefs.Settings(2000, restingBurn = 1900, model = null), emptyList(), false, WhoopDay(day, 2500.0, 11.0))
        assertEquals(2500.0, a.kcalOut, 0.0) // WHOOP, not 1900 base burn
        assertEquals(-700.0, a.net, 0.0)
        assertTrue(a.insights.first().startsWith("WHOOP measured 2,500 kcal burned (strain 11.0)"))
        assertTrue(a.insights[1].startsWith("Deficit of 700 kcal"))

        // Trends use the same rule: WHOOP when synced, otherwise base burn, plus logged activities.
        assertEquals(2600.0, Analyzer.dayOut(DayTotals(day, 1800.0, 100.0, whoopKcal = 2500.0), 1900), 0.0)
        assertEquals(2000.0, Analyzer.dayOut(DayTotals(day, 1800.0, 100.0, whoopKcal = null), 1900), 0.0)
    }
}
