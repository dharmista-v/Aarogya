package com.example.aarogya

import com.example.aarogya.data.DayAnalysis
import com.example.aarogya.widget.WidgetBalance
import com.example.aarogya.widget.WidgetBalance.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetBalanceTest {
    private fun day(inK: Double, active: Double = 0.0, resting: Int = 0, whoop: Double? = null) =
        DayAnalysis(inK, active, resting, 2250, emptyList(), whoop)

    @Test fun deficitWithWhoop() =
        assertEquals(WidgetBalance("Deficit today", "1,330", "In 770 · Out (WHOOP) 2,100", Tone.DEFICIT), WidgetBalance.from(day(770.0, whoop = 2100.0)))

    @Test fun surplusWithBaseBurn() =
        assertEquals(WidgetBalance("Surplus today", "250", "In 2,350 · Out 2,100", Tone.SURPLUS), WidgetBalance.from(day(2350.0, active = 200.0, resting = 1900)))

    @Test fun withoutBaseBurnItIsJustNet() =
        assertEquals(WidgetBalance("Net today", "500", "In 800 · Active 300", Tone.NEUTRAL), WidgetBalance.from(day(800.0, active = 300.0)))

    @Test fun nothingEatenShowsBurnNotDeficit() =
        assertEquals(WidgetBalance("Burned so far", "1,900", "No food logged yet", Tone.NEUTRAL), WidgetBalance.from(day(0.0, resting = 1900)))
}
