package com.example.aarogya.data

/** Counting foods in natural units: "2 rotis", "1 fillet", "1.5 cups". */
object Portions {
    data class Parsed(val count: Double, val unit: String)

    private val NUMBER_WORDS = mapOf("a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "half" to 0.5)
    private val PREPOSITIONS = setOf("of", "in", "with", "on", "from")

    /** Splits a portion like "2 rotis", "1/2 cup", "a katori", "1 large serving in a pan" into count + singular unit. */
    fun parse(portion: String): Parsed {
        val text = portion.trim().replace("½", "0.5").replace("¼", "0.25").replace("¾", "0.75")
        if (text.isEmpty()) return Parsed(1.0, "serving")
        val words = text.split(Regex("\\s+")).toMutableList()
        val first = words.first().lowercase()
        val count = first.toDoubleOrNull()
            ?: Regex("""(\d+)/(\d+)""").matchEntire(first)?.let { m -> m.groupValues[1].toDouble() / m.groupValues[2].toDouble() }
            ?: NUMBER_WORDS[first]
        if (count == null) return Parsed(1.0, singularPhrase(text))
        words.removeAt(0)
        val unit = words.joinToString(" ").ifBlank { "serving" }
        return Parsed(count.takeIf { it > 0 } ?: 1.0, singularPhrase(unit))
    }

    /** "1 taco", "2 tacos", "1.5 cups", "2 large servings in a pan". */
    fun label(count: Double, unit: String): String {
        val n = (Math.round(count * 10) / 10.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
        return "$n ${if (count <= 1.0) unit else pluralPhrase(unit)}"
    }

    /** Pluralises the head noun: the last word, or the word before a preposition ("serving in a pan"). */
    fun pluralPhrase(unit: String): String = transformHead(unit, ::plural)
    private fun singularPhrase(unit: String): String = transformHead(unit, ::singular)

    private fun transformHead(unit: String, f: (String) -> String): String {
        val words = unit.split(" ").toMutableList()
        val prep = words.indexOfFirst { it.lowercase() in PREPOSITIONS }
        val head = if (prep > 0) prep - 1 else words.lastIndex
        words[head] = f(words[head])
        return words.joinToString(" ")
    }

    fun singular(w: String): String = when {
        w.length <= 3 -> w
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("oes") -> w.dropLast(2)
        Regex("(ss|sh|ch|x)es$").containsMatchIn(w) -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun plural(w: String): String = when {
        w.isEmpty() -> w
        w.endsWith("y") && w.length > 1 && w[w.length - 2] !in "aeiou" -> w.dropLast(1) + "ies"
        Regex("(s|sh|ch|x|z)$").containsMatchIn(w) -> w + "es"
        else -> w + "s"
    }
}
