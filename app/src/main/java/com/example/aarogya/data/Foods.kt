package com.example.aarogya.data

import java.io.InputStream

data class Food(
    val id: Int,
    val name: String,
    val aliases: List<String>,
    val kcalPer100g: Double,
    val servingGrams: Double,
    val servingLabel: String,
) {
    val kcalPerServing: Double get() = kcalPer100g * servingGrams / 100.0
}

/** The bundled nutrition table plus fuzzy lookup by name. */
class FoodDb(val foods: List<Food>) {
    private val keys: List<Pair<String, Food>> = foods.flatMap { f ->
        (listOf(f.name) + f.aliases).map { normalize(it) to f }
    }
    private val exact: Map<String, Food> = keys.associate { it }

    /** Type-ahead search for the picker. */
    fun search(query: String, limit: Int = 8): List<Food> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return keys.asSequence()
            .filter { (k, _) -> k.contains(q) }
            .sortedWith(compareBy({ !it.first.startsWith(q) }, { it.first.length }))
            .map { it.second }
            .distinct()
            .take(limit)
            .toList()
    }

    /**
     * Foods named anywhere in free text, e.g. a model's "rice, chicken and vegetables".
     * Longest names win and words are not reused ("chicken biryani" beats "chicken").
     */
    fun mentions(text: String): List<Food> {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }
        val found = LinkedHashSet<Food>()
        var i = 0
        while (i < words.size) {
            val hit = (minOf(4, words.size - i) downTo 1).firstNotNullOfOrNull { n ->
                exact[words.subList(i, i + n).joinToString(" ")]?.let { it to n }
            }
            if (hit != null) { found += hit.first; i += hit.second } else i++
        }
        return found.toList()
    }

    /** Best match for a free-text food phrase, or null if nothing is reasonably close. */
    fun match(phrase: String): Food? {
        val q = normalize(phrase)
        if (q.isEmpty()) return null
        exact[q]?.let { return it }
        val qWords = q.split(' ').toSet()

        // Prefer the most specific table entry whose words all appear in the phrase
        // ("2 boiled eggs with toast" -> "boiled egg", not "egg").
        keys.filter { (k, _) -> k.split(' ').all { it in qWords } }
            .maxByOrNull { it.first.length }
            ?.let { return it.second }

        // Tolerate small typos on single words ("chapatti", "bannana").
        if (qWords.size <= 2) {
            // Short words differ by one letter too easily (dish/fish), so only longer words get typo tolerance.
            for (w in qWords.filter { it.length >= 5 }) {
                keys.firstOrNull { (k, _) -> !k.contains(' ') && editDistance(k, w) <= if (w.length >= 7) 2 else 1 }
                    ?.let { return it.second }
            }
        }
        return null
    }

    companion object {
        fun load(input: InputStream): FoodDb {
            val foods = input.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapIndexed { i, line ->
                    val p = line.split('|')
                    Food(
                        id = i,
                        name = p[0].trim(),
                        aliases = p[1].split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        kcalPer100g = p[2].trim().toDouble(),
                        servingGrams = p[3].trim().toDouble(),
                        servingLabel = p[4].trim(),
                    )
                }
            return FoodDb(foods)
        }

        fun normalize(s: String): String = s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { singular(it) }

        private fun singular(w: String): String = when {
            w.length <= 3 -> w
            w.endsWith("ies") -> w.dropLast(3) + "y"
            w.endsWith("oes") -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }

        private fun editDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
