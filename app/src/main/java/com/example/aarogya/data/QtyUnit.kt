package com.example.aarogya.data

/**
 * Units a food amount can be shown and entered in. The amount is always stored as grams; a unit is
 * just a view of it. Volumes use ~1 g per ml (exact for water-based foods, close for most others).
 */
enum class QtyUnit(val label: String, private val gramsEach: Double?, val step: Double) {
    SERVING("unit", null, 0.5), // the food's own unit: "taco", "roti", "fillet"…
    G("g", 1.0, 10.0),
    KG("kg", 1000.0, 0.05),
    OZ("oz", 28.3495, 0.5),
    LB("lb", 453.592, 0.1),
    ML("ml", 1.0, 10.0),
    L("L", 1000.0, 0.05),
    FL_OZ("fl oz", 29.5735, 0.5),
    CUP("cup", 240.0, 0.25),
    TBSP("tbsp", 15.0, 0.5),
    TSP("tsp", 5.0, 0.5);

    /** Grams in one of this unit; [servingGrams] is the food's own unit (null when unknown). */
    fun gramsPer(servingGrams: Double?): Double? = gramsEach ?: servingGrams

    /** Display name for an amount: the food's unit pluralised ("2 tacos"), others as-is ("2 cup"→"2 cups"). */
    fun name(amount: Double, servingLabel: String?): String = when (this) {
        SERVING -> Portions.label(amount, servingLabel ?: "serving").substringAfter(' ')
        CUP -> if (amount <= 1.0) "cup" else "cups"
        else -> label
    }

    companion object {
        /** Units offered for a food: its own unit if known, then weight and volume units if grams are known. */
        fun available(hasServing: Boolean, hasGrams: Boolean): List<QtyUnit> =
            entries.filter { (it == SERVING && hasServing) || (it != SERVING && hasGrams) }

        /** The unit a typed amount was given in ("200g" → G, "1 cup" → CUP); null means the food's own unit. */
        fun fromParsed(unit: String): QtyUnit? = when (unit) {
            "g" -> G
            "kg" -> KG
            "ml" -> ML
            "l" -> L
            "cup" -> CUP
            "tbsp" -> TBSP
            "tsp" -> TSP
            else -> null
        }

        /** Rounds an amount for display: whole numbers for g/ml, otherwise up to two decimals. */
        fun format(amount: Double, unit: QtyUnit): String {
            val rounded = if (unit == G || unit == ML) Math.round(amount).toDouble() else Math.round(amount * 100) / 100.0
            return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString().trimEnd('0').trimEnd('.')
        }
    }
}
