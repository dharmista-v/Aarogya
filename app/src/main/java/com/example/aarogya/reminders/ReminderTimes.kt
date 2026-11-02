package com.example.aarogya.reminders

import com.example.aarogya.data.Meal
import java.time.ZonedDateTime

/** A meal reminder: on/off and the time of day (minutes after midnight, tracking time zone). */
data class Reminder(val enabled: Boolean, val minutes: Int) {
    val hour: Int get() = minutes / 60
    val minute: Int get() = minutes % 60
}

object ReminderTimes {
    val DEFAULTS = mapOf(
        Meal.BREAKFAST to 8 * 60 + 30,
        Meal.LUNCH to 13 * 60,
        Meal.SNACK to 17 * 60,
        Meal.DINNER to 20 * 60,
    )

    /** Next time [minutes] past midnight occurs strictly after [now], in now's zone (DST-safe). */
    fun next(now: ZonedDateTime, minutes: Int): ZonedDateTime {
        val today = now.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(minutes / 60, minutes % 60).atZone(now.zone)
    }

    /** "8:30 AM" */
    fun label(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
    }
}
