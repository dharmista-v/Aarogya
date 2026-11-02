package com.example.aarogya

import com.example.aarogya.reminders.ReminderTimes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimesTest {
    private val chicago = ZoneId.of("America/Chicago")
    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), chicago)

    @Test fun laterTodayOrTomorrow() {
        assertEquals(at("2026-11-02T13:00"), ReminderTimes.next(at("2026-11-02T09:15"), 13 * 60)) // later today
        assertEquals(at("2026-11-03T08:30"), ReminderTimes.next(at("2026-11-02T09:15"), 8 * 60 + 30)) // already passed
        assertEquals(at("2026-11-03T13:00"), ReminderTimes.next(at("2026-11-02T13:00"), 13 * 60)) // exactly now -> tomorrow
    }

    @Test fun keepsWallClockTimeAcrossDaylightSaving() {
        // DST ends 2026-11-01 in Chicago: the reminder stays at 8:30 AM local, not shifted by an hour.
        val next = ReminderTimes.next(at("2026-10-31T20:00"), 8 * 60 + 30)
        assertEquals(8, next.hour); assertEquals(30, next.minute); assertEquals(1, next.dayOfMonth)
        assertEquals(-6 * 3600, next.offset.totalSeconds) // now CST
    }

    @Test fun labels() {
        assertEquals("8:30 AM", ReminderTimes.label(8 * 60 + 30))
        assertEquals("12:00 PM", ReminderTimes.label(12 * 60))
        assertEquals("12:05 AM", ReminderTimes.label(5))
        assertEquals("8:00 PM", ReminderTimes.label(20 * 60))
    }
}
