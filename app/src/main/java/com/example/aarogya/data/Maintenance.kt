package com.example.aarogya.data

import kotlin.math.roundToInt

/** Rule-of-thumb maintenance intake: body weight in pounds × 14–18 kcal depending on activity. */
enum class ActivityLevel(val label: String, val kcalPerLb: Int) {
    SEDENTARY("Sedentary", 14),
    MODERATE("Moderate", 15),
    ACTIVE("Active", 17),
    VERY_ACTIVE("Very active", 18),
}

object Maintenance {
    const val LB_PER_KG = 2.20462

    fun kcal(weight: Double, inKg: Boolean, level: ActivityLevel): Int {
        val lb = if (inKg) weight * LB_PER_KG else weight
        return (lb * level.kcalPerLb).roundToInt()
    }
}
