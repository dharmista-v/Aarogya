package com.example.aarogya

import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.BurnEntry
import com.example.aarogya.data.DayTotals
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.Meal
import com.example.aarogya.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalyzerTest {
    private val day = LocalDate.of(2026, 10, 30)
    private fun food(name: String, kcal: Double, meal: Meal) = FoodEntry(day = day, time = 0, meal = meal, name = name, grams = null, kcal = kcal, photo = null, source = "manual")

    @Test fun computesBalanceWithBaseBurn() {
        val a = Analyzer.analyze(
            foods = listOf(food("Poha", 300.0, Meal.BREAKFAST), food("Biryani", 900.0, Meal.DINNER)),
            burns = listOf(BurnEntry(day = day, time = 0, name = "Run", kcal = 400.0)),
            settings = Prefs.Settings(goal = 1800, restingBurn = 1900, model = null),
            recent = emptyList(),
            isToday = false,
        )
        assertEquals(1200.0, a.kcalIn, 0.0)
        assertEquals(2300.0, a.kcalOut, 0.0)
        assertEquals(-1100.0, a.net, 0.0)
        assertEquals(1000.0, a.remaining, 0.0) // 1800 + 400 - 1200
        assertTrue(a.insights.first().startsWith("Deficit of 1,100 kcal") || a.insights.first().startsWith("Deficit of 1100 kcal"))
        assertTrue(a.insights.any { "Dinner was the biggest meal at 75%" in it })
    }

    @Test fun daysWithInOrOutAtZeroAreIncomplete() {
        val d = LocalDate.of(2026, 10, 30)
        assertFalse(Analyzer.isComplete(DayTotals(d, kcalIn = 0.0, kcalActive = 300.0, whoopKcal = 2400.0), restingBurn = 0)) // no food
        assertFalse(Analyzer.isComplete(DayTotals(d, kcalIn = 1800.0, kcalActive = 0.0), restingBurn = 0)) // nothing burned known
        assertTrue(Analyzer.isComplete(DayTotals(d, kcalIn = 1800.0, kcalActive = 0.0), restingBurn = 1900))
        assertTrue(Analyzer.isComplete(DayTotals(d, kcalIn = 1800.0, kcalActive = 0.0, whoopKcal = 2400.0), restingBurn = 0))

        // Weekly averages only use complete days.
        val week = Analyzer.week(
            listOf(
                DayTotals(d, 2000.0, 0.0, whoopKcal = 2500.0),
                DayTotals(d.plusDays(1), 0.0, 0.0, whoopKcal = 2600.0), // WHOOP only, no food: skipped
                DayTotals(d.plusDays(2), 1500.0, 0.0), // food only, no burn known: skipped
            ),
            restingBurn = 0,
        )!!
        assertEquals(1, week.loggedDays)
        assertEquals(2000, week.avgIn)
        assertEquals(-500, week.avgNet)
    }

    @Test fun nudgesToSetBaseBurn() {
        val a = Analyzer.analyze(listOf(food("Tea", 50.0, Meal.SNACK)), emptyList(), Prefs.Settings(2000, 0, null), emptyList(), true)
        assertTrue(a.insights.first().contains("Set your base burn"))
    }
}

class OverallAnalysisTest {
    private val mon = LocalDate.of(2026, 10, 19) // a Monday

    @Test fun groupsCompleteDaysIntoWeeksAndWeekdays() {
        val days = listOf(
            DayTotals(mon, 2000.0, 0.0, whoopKcal = 2400.0),              // week 1, Mon
            DayTotals(mon.plusDays(2), 1800.0, 0.0, whoopKcal = 2300.0),  // week 1, Wed
            DayTotals(mon.plusDays(3), 0.0, 0.0, whoopKcal = 2500.0),     // no food: not complete
            DayTotals(mon.plusDays(7), 2600.0, 0.0, whoopKcal = 2400.0),  // week 2, Mon
        )
        val o = com.example.aarogya.data.Analyzer.overall(days, restingBurn = 0)!!
        assertEquals(3, o.completeDays)
        assertEquals(listOf(mon, mon.plusDays(7)), o.weeks.map { it.weekStart })
        assertEquals(1900.0, o.weeks[0].avgIn, 1e-9)
        assertEquals(-450.0, o.weeks[0].avgNet, 1e-9)
        assertEquals(200.0, o.weeks[1].avgNet, 1e-9)
        assertEquals(-700.0, o.totalNet, 1e-9) // -400 -500 +200
        assertEquals(-700.0 / 7700.0, o.estimatedKgChange, 1e-9)
        assertEquals(2300.0, o.byWeekday[0]!!, 1e-9) // Mondays: 2000 and 2600
        assertEquals(1800.0, o.byWeekday[2]!!, 1e-9) // Wednesday
        assertEquals(null, o.byWeekday[1])             // no Tuesday logged
    }

    @Test fun nothingCompleteMeansNoAnalysis() {
        assertEquals(null, com.example.aarogya.data.Analyzer.overall(listOf(DayTotals(mon, 0.0, 0.0, whoopKcal = 2000.0)), 0))
    }
}
