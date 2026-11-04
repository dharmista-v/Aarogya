package com.example.aarogya.widget

import com.example.aarogya.data.DayAnalysis
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the home-screen widget shows for a day, worded the same way as the Day screen's summary. */
data class WidgetBalance(val label: String, val value: String, val detail: String, val tone: Tone) {
    enum class Tone { DEFICIT, SURPLUS, NEUTRAL }

    companion object {
        private val fmt = NumberFormat.getIntegerInstance()
        private fun Double.n() = fmt.format(roundToInt())

        fun from(a: DayAnalysis): WidgetBalance {
            val net = a.net.roundToInt()
            // Nothing eaten yet: the whole burn would read as a "deficit", so show the burn instead.
            if (a.kcalIn <= 0) {
                val burned = if (a.hasFullBurn) a.kcalOut else a.kcalActive
                return WidgetBalance("Burned so far", burned.n(), "No food logged yet", Tone.NEUTRAL)
            }
            // Without a base burn or WHOOP, "Out" is only logged activities, so don't call it a deficit/surplus.
            if (!a.hasFullBurn) {
                return WidgetBalance("Net today", fmt.format(net), "In ${a.kcalIn.n()} · Active ${a.kcalActive.n()}", Tone.NEUTRAL)
            }
            val label = if (net <= 0) "Deficit today" else "Surplus today"
            val outLabel = if (a.whoopKcal != null) "Out (WHOOP)" else "Out"
            return WidgetBalance(
                label, fmt.format(abs(net)), "In ${a.kcalIn.n()} · $outLabel ${a.kcalOut.n()}",
                when { net < 0 -> Tone.DEFICIT; net > 0 -> Tone.SURPLUS; else -> Tone.NEUTRAL },
            )
        }
    }
}
