package com.example.aarogya.data

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

private fun Int.n(): String = "%,d".format(this)
private fun Double.n(): String = roundToInt().n()

/** ~7700 kcal of energy imbalance corresponds to about 1 kg of body weight. */
const val KCAL_PER_KG = 7700.0

data class DayAnalysis(
    val kcalIn: Double,
    val kcalActive: Double,
    val restingBurn: Int,
    val goal: Int,
    val insights: List<String>,
    /** WHOOP's measured total burn for the day; replaces the base-burn estimate when present. */
    val whoopKcal: Double? = null,
) {
    /** Measured (WHOOP) or estimated (base burn) daily burn, plus manually logged activities. */
    val kcalOut: Double get() = (whoopKcal ?: restingBurn.toDouble()) + kcalActive
    /** True when "Out" includes the body's whole-day burn, so the net is a real deficit/surplus. */
    val hasFullBurn: Boolean get() = whoopKcal != null || restingBurn > 0
    /** Positive = surplus, negative = deficit. */
    val net: Double get() = kcalIn - kcalOut
    /** Intake budget: the goal plus whatever activity earned back. */
    val budget: Double get() = goal + kcalActive
    val remaining: Double get() = budget - kcalIn
}

object Analyzer {

    fun analyze(
        foods: List<FoodEntry>,
        burns: List<BurnEntry>,
        settings: Prefs.Settings,
        recent: List<DayTotals>,
        isToday: Boolean,
        whoop: WhoopDay? = null,
        whoopWorkouts: List<WhoopWorkout> = emptyList(),
    ): DayAnalysis {
        val kcalIn = foods.sumOf { it.kcal }
        val active = burns.sumOf { it.kcal }
        val base = DayAnalysis(kcalIn, active, settings.restingBurn, settings.goal, emptyList(), whoop?.kcal)
        if (foods.isEmpty() && burns.isEmpty() && whoop == null) {
            return base.copy(insights = listOf(if (isToday) "Nothing logged yet today. Add a meal or an activity to see your balance." else "Nothing was logged on this day."))
        }

        val out = mutableListOf<String>()
        val net = base.net.roundToInt()

        // 0. Entries still waiting for calories
        val pending = foods.count { it.status == FoodStatus.PENDING }
        val failed = foods.count { it.status == FoodStatus.FAILED }
        if (pending > 0) out += "$pending item${if (pending == 1) " is" else "s are"} still being classified — totals will update."
        if (failed > 0) out += "$failed item${if (failed == 1) "" else "s"} couldn't be classified — tap to add calories."

        // 1. Energy balance
        if (whoop != null) {
            out += "WHOOP measured ${whoop.kcal.n()} kcal burned${if (isToday) " so far" else ""}" +
                (whoop.strain?.let { " (strain ${"%.1f".format(it)})" } ?: "") + "."
        }
        if (base.hasFullBurn) {
            val grams = (abs(base.net) / KCAL_PER_KG * 1000).roundToInt()
            out += when {
                abs(net) < 100 -> "Roughly balanced: in and out are within 100 kcal."
                net < 0 -> "Deficit of ${(-net).n()} kcal (≈ $grams g of body weight${if (isToday) " so far" else ""})."
                else -> "Surplus of ${net.n()} kcal (≈ $grams g of body weight)."
            }
        } else {
            out += "In − logged activity = ${net.n()} kcal. Set your base burn in Settings to see your true deficit or surplus."
        }

        // 2. Goal
        val rem = base.remaining.roundToInt()
        out += if (rem >= 0) {
            "${rem.n()} kcal left of your ${base.budget.n()} kcal budget" +
                (if (active > 0) " (goal ${settings.goal.n()} + ${active.n()} earned from activity)." else ".")
        } else {
            "Over budget by ${(-rem).n()} kcal."
        }

        // 3. Where the calories came from
        if (kcalIn > 0) {
            val byMeal = foods.groupBy { it.meal }.mapValues { (_, v) -> v.sumOf { it.kcal } }
            val (topMeal, topKcal) = byMeal.maxBy { it.value }
            if (byMeal.size > 1) out += "${topMeal.label} was the biggest meal at ${(topKcal / kcalIn * 100).roundToInt()}% of intake."
            val topFood = foods.maxBy { it.kcal }
            if (foods.size > 1) out += "Biggest single item: ${topFood.name} (${topFood.kcal.n()} kcal)."
        }

        // 4. Versus the recent average (days that have any food logged)
        val logged = recent.filter { it.kcalIn > 0 }
        if (logged.size >= 3 && kcalIn > 0 && !isToday) {
            val avg = logged.map { it.kcalIn }.average()
            val pct = ((kcalIn - avg) / avg * 100).roundToInt()
            if (abs(pct) >= 10) out += "Intake was ${abs(pct)}% ${if (pct > 0) "above" else "below"} your recent average of ${avg.n()} kcal."
        }

        if (whoopWorkouts.isNotEmpty()) {
            out += "WHOOP workouts: " + whoopWorkouts.joinToString { "${it.sport} ${it.kcal.n()} kcal" } + " (already in WHOOP's total)."
        }
        if (active == 0.0 && whoop == null) out += "No activity logged — even a 30-min walk burns about 120–150 kcal."
        return base.copy(insights = out)
    }

    data class WeekSummary(val avgIn: Int, val avgOut: Int, val avgNet: Int, val projectedKgPerWeek: Double, val loggedDays: Int)

    /** A day's burn: WHOOP's measurement if synced, else the base-burn estimate, plus logged activities. */
    fun dayOut(d: DayTotals, restingBurn: Int): Double = (d.whoopKcal ?: restingBurn.toDouble()) + d.kcalActive

    /** In/out/net only mean something when both sides are known; days with either at 0 are left out. */
    fun isComplete(d: DayTotals, restingBurn: Int): Boolean = d.kcalIn > 0 && dayOut(d, restingBurn) > 0

    fun week(days: List<DayTotals>, restingBurn: Int): WeekSummary? {
        val logged = days.filter { isComplete(it, restingBurn) }
        if (logged.isEmpty()) return null
        val avgIn = logged.map { it.kcalIn }.average()
        val avgOut = logged.map { dayOut(it, restingBurn) }.average()
        val avgNet = avgIn - avgOut
        return WeekSummary(avgIn.roundToInt(), avgOut.roundToInt(), avgNet.roundToInt(), avgNet * 7 / KCAL_PER_KG, logged.size)
    }

    data class WeekStat(val weekStart: LocalDate, val avgIn: Double, val avgOut: Double, val days: Int) {
        val avgNet: Double get() = avgIn - avgOut
    }

    data class Overall(
        val completeDays: Int,
        val avgIn: Double,
        val avgOut: Double,
        /** Sum of daily net over complete days; negative = deficit. */
        val totalNet: Double,
        val weeks: List<WeekStat>,
        /** Average intake by day of week, Monday first; null when no day of that kind was logged. */
        val byWeekday: List<Double?>,
    ) {
        val avgNet: Double get() = avgIn - avgOut
        val estimatedKgChange: Double get() = totalNet / KCAL_PER_KG
    }

    /** All-time view over complete days (both in and out known), grouped into Monday-start weeks. */
    fun overall(days: List<DayTotals>, restingBurn: Int): Overall? {
        val complete = days.filter { isComplete(it, restingBurn) }
        if (complete.isEmpty()) return null
        val weeks = complete.groupBy { it.day.with(java.time.DayOfWeek.MONDAY) }.toSortedMap().map { (start, list) ->
            WeekStat(start, list.map { it.kcalIn }.average(), list.map { dayOut(it, restingBurn) }.average(), list.size)
        }
        val logged = days.filter { it.kcalIn > 0 }
        val byWeekday = java.time.DayOfWeek.entries.map { dow ->
            logged.filter { it.day.dayOfWeek == dow }.takeIf { it.isNotEmpty() }?.map { it.kcalIn }?.average()
        }
        return Overall(
            completeDays = complete.size,
            avgIn = complete.map { it.kcalIn }.average(),
            avgOut = complete.map { dayOut(it, restingBurn) }.average(),
            totalNet = complete.sumOf { it.kcalIn - dayOut(it, restingBurn) },
            weeks = weeks,
            byWeekday = byWeekday,
        )
    }
}
