package com.example.aarogya.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * The app's single clock: everything ("today", which day a meal/cycle/weight belongs to, times shown)
 * uses the tracking time zone the user picked, not the phone's current zone, so travel doesn't
 * shift or split days.
 */
object AppTime {
    @Volatile var zone: ZoneId = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone)
    fun nowTime(): LocalTime = LocalTime.now(zone)
    fun dateOf(instant: Instant, zone: ZoneId = this.zone): LocalDate = instant.atZone(zone).toLocalDate()
    fun dateOf(epochMillis: Long): LocalDate = dateOf(Instant.ofEpochMilli(epochMillis))

    /** True when the phone is currently in a different zone (e.g. travelling). */
    fun phoneDiffers(): Boolean = phoneZone().rules.getOffset(Instant.now()) != zone.rules.getOffset(Instant.now())
    fun phoneZone(): ZoneId = ZoneId.systemDefault()

    /** "Chicago · GMT−05:00" */
    fun label(z: ZoneId): String {
        val city = z.id.substringAfterLast('/').replace('_', ' ').ifEmpty { z.id }
        return "$city · ${offsetLabel(z)}"
    }

    fun offsetLabel(z: ZoneId): String {
        val o = z.rules.getOffset(Instant.now())
        return if (o == ZoneOffset.UTC) "GMT" else "GMT" + o.id.replace("-", "−")
    }

    fun shortName(z: ZoneId): String = z.id.substringAfterLast('/').replace('_', ' ').ifEmpty { z.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

    /** Region zones (e.g. Asia/Kolkata), sorted by current UTC offset then name, for the picker. */
    fun allZones(): List<ZoneId> = ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
        .map(ZoneId::of)
        .sortedWith(compareBy({ it.rules.getOffset(Instant.now()).totalSeconds }, { it.id }))
}
