package com.example.aarogya

import com.example.aarogya.googlehealth.GoogleHealth
import com.example.aarogya.googlehealth.WeightParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Shapes follow the Google Health API v4 dataPoints.list reference (weight.weightGrams, sampleTime.*). */
class WeightParserTest {
    @Test fun parsesGramsToKgWithLocalDates() {
        val json = """
            {"dataPoints": [
              {"name": "users/me/dataTypes/weight/dataPoints/a1", "weight": {"weightGrams": 72450.5,
                "sampleTime": {"physicalTime": "2026-10-31T03:30:00Z", "utcOffset": "-18000s",
                  "civilTime": {"date": {"year": 2026, "month": 10, "day": 30}, "time": {"hours": 22, "minutes": 30}}}}},
              {"weight": {"weightGrams": 73100, "sampleTime": {"physicalTime": "2026-10-20T14:00:00Z", "utcOffset": "19800s"}}},
              {"weight": {"weightGrams": 0, "sampleTime": {"physicalTime": "2026-10-19T14:00:00Z", "utcOffset": "0s"}}},
              {"steps": {"count": 10}}
            ], "nextPageToken": "abc"}
        """.trimIndent()
        val page = WeightParser.parse(json, zone = java.time.ZoneOffset.of("-05:00"))
        assertEquals("abc", page.nextPageToken)
        assertEquals(2, page.items.size) // zero weight and non-weight points are skipped
        val first = page.items[0]
        assertEquals(72.4505, first.kg, 1e-9)
        assertEquals(LocalDate.of(2026, 10, 30), first.day) // tracking-zone date (Oct 30 22:30), not the UTC date
        assertEquals(Instant.parse("2026-10-31T03:30:00Z").toEpochMilli(), first.time)
        assertEquals(LocalDate.of(2026, 10, 20), page.items[1].day) // taken in India, still dated in the tracking zone
        assertEquals("Google Health", page.items[1].source)
    }

    @Test fun lastPageAndErrors() {
        assertNull(WeightParser.parse("""{"dataPoints": []}""").nextPageToken)
        assertEquals(
            "Open the Google Health app and sign in once, then sync again.",
            GoogleHealth.errorMessage(400, """{"error":{"code":400,"status":"FAILED_PRECONDITION","details":[{"reason":"ACCOUNT_NOT_LINKED"}]}}"""),
        )
        assertEquals(
            "Google Health API isn't enabled in your Cloud project — enable it, then sync again.",
            GoogleHealth.errorMessage(403, """{"error":{"code":403,"message":"Google Health API has not been used in project 540010034732 before or it is disabled."}}"""),
        )
    }
}
