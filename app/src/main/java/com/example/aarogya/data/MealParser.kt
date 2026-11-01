package com.example.aarogya.data

/** One food phrase pulled out of a meal description, before it is matched to the table. */
data class ParsedItem(val phrase: String, val quantity: Double, val unit: String)

/**
 * Turns "2 rotis, a bowl of dal and 1 glass buttermilk" into items.
 * The rule parser handles everyday phrasing instantly; [llmPrompt] / [parseLlmOutput]
 * let SmolLM2 handle the phrases the rules couldn't map to a known food.
 */
object MealParser {

    private val NUMBER_WORDS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "half" to 0.5, "quarter" to 0.25, "couple" to 2.0, "few" to 3.0, "dozen" to 12.0,
    )
    private val SIZE_WORDS = mapOf("small" to 0.7, "medium" to 1.0, "large" to 1.4, "big" to 1.4, "huge" to 1.8)
    private val UNITS = mapOf(
        "g" to "g", "gm" to "g", "gms" to "g", "gram" to "g", "grams" to "g", "kg" to "kg",
        "ml" to "ml", "l" to "l", "litre" to "l", "liter" to "l",
        "cup" to "cup", "cups" to "cup", "bowl" to "bowl", "bowls" to "bowl", "katori" to "bowl", "katoris" to "bowl",
        "plate" to "plate", "plates" to "plate", "glass" to "glass", "glasses" to "glass",
        "tbsp" to "tbsp", "tablespoon" to "tbsp", "tablespoons" to "tbsp", "spoon" to "tbsp", "spoons" to "tbsp",
        "tsp" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp",
        "slice" to "slice", "slices" to "slice", "piece" to "piece", "pieces" to "piece", "pc" to "piece", "pcs" to "piece",
        "handful" to "handful", "handfuls" to "handful", "can" to "can", "cans" to "can", "bottle" to "bottle", "bottles" to "bottle",
        "scoop" to "scoop", "scoops" to "scoop", "serving" to "serving", "servings" to "serving", "pack" to "pack", "packet" to "pack",
    )
    private val FILLER = setOf(
        "a", "an", "i", "had", "ate", "have", "eaten", "drank", "some", "of", "for", "breakfast", "lunch", "dinner", "snack",
        "my", "the", "then", "also", "later", "like", "about", "around", "approx", "just", "today", "in", "morning", "evening", "night",
    )
    private val SEPARATORS = Regex("""\s*(?:,|;|\+|&|\n|\band\b|\bwith\b|\balong with\b|\bplus\b)\s*""", RegexOption.IGNORE_CASE)

    fun parseRules(text: String): List<ParsedItem> =
        text.split(SEPARATORS).mapNotNull { parseSegment(it) }

    private fun parseSegment(segment: String): ParsedItem? {
        // "200g" -> "200 g", "1½" -> "1.5", "1/2" -> "0.5"
        val cleaned = segment.lowercase()
            .replace("½", ".5").replace("¼", ".25").replace("¾", ".75")
            .replace(Regex("""(\d+)\s*/\s*(\d+)""")) { m -> (m.groupValues[1].toDouble() / m.groupValues[2].toDouble()).toString() }
            .replace(Regex("""(\d)([a-z])"""), "$1 $2")
            .replace(Regex("""^\.(\d)"""), "0.$1")
        var qty: Double? = null
        var unit = ""
        var size = 1.0
        val name = ArrayList<String>()
        for (w in cleaned.split(Regex("""[^a-z0-9.]+""")).filter { it.isNotEmpty() && it != "." }) {
            val num = w.toDoubleOrNull() ?: NUMBER_WORDS[w]
            when {
                num != null && qty == null -> qty = num
                unit.isEmpty() && w in UNITS -> unit = UNITS.getValue(w)
                w in SIZE_WORDS -> size = SIZE_WORDS.getValue(w)
                w in FILLER || num != null -> Unit
                else -> name += w
            }
        }
        if (name.isEmpty()) return null
        return ParsedItem(name.joinToString(" "), (qty ?: 1.0) * size, unit)
    }

    /** Grams for a quantity, using the food's own serving size where the unit means "one of these". */
    fun grams(food: Food?, qty: Double, unit: String): Double {
        val serving = food?.servingGrams ?: 100.0
        val label = food?.servingLabel
        fun unitOrServing(default: Double) = if (label == unit) serving else default
        return qty * when (unit) {
            "g", "ml" -> 1.0
            "kg", "l" -> 1000.0
            "cup" -> unitOrServing(240.0) // US cup, same as the quantity picker
            "bowl" -> unitOrServing(200.0)
            "plate" -> unitOrServing(300.0)
            "glass" -> unitOrServing(250.0)
            "tbsp" -> unitOrServing(15.0)
            "tsp" -> unitOrServing(5.0)
            "slice" -> unitOrServing(30.0)
            "handful" -> unitOrServing(30.0)
            "can" -> unitOrServing(330.0)
            "bottle" -> unitOrServing(500.0)
            "scoop" -> unitOrServing(30.0)
            else -> serving // "", "piece", "serving", "pack", or the food's own label
        }
    }

    // --- SmolLM2 ---

    const val LLM_SYSTEM =
        "Extract foods from the meal. Output one line per food: food; quantity; unit. Units: g, ml, piece, cup, bowl, slice, tbsp."
    val LLM_SHOTS = listOf(
        "a cup of rice and 2 chapatis" to "rice; 1; cup\nchapati; 2; piece",
        "200g chicken breast with a glass of milk" to "chicken breast; 200; g\nmilk; 250; ml",
    )

    fun parseLlmOutput(output: String): List<ParsedItem> = output.lines().mapNotNull { line ->
        val parts = line.split(';', '|').map { it.trim() }
        val name = parts.getOrNull(0)?.lowercase()?.trim('-', '*', ' ', '.')
        if (name.isNullOrEmpty()) return@mapNotNull null
        val qty = parts.getOrNull(1)?.let { Regex("""\d+(\.\d+)?""").find(it)?.value?.toDouble() } ?: 1.0
        val unit = parts.getOrNull(2)?.lowercase()?.let { UNITS[it] ?: UNITS[it.removeSuffix("s")] } ?: ""
        ParsedItem(name, qty, unit)
    }
}
