package com.example.aarogya.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything the chat knows about the user, gathered once per question. [weights] is null while
 * the Weight tab is locked, so the PIN also protects weight from the chat.
 */
data class HealthSnapshot(
    val today: LocalDate,
    val goal: Int,
    val todayFoods: List<FoodEntry>,
    val todayIn: Double,
    /** Today's burn if known (WHOOP or base burn + activities), else null. */
    val todayOut: Double?,
    val burnSource: String,
    /** Last 7 days, oldest first. */
    val recent: List<DayTotals>,
    val restingBurn: Int,
    val overall: Analyzer.Overall?,
    val topFoods: List<Store.FoodStat>,
    val byCategory: List<Pair<FoodCategory, Double>>,
    val weights: List<WeightEntry>?,
    /** Foods from the table, used to suggest what fits the remaining budget. */
    val foodTable: List<Food>,
    /** Activities in the last 7 days (WHOOP workouts and logged activities), oldest first. */
    val activities: List<ActivityLine> = emptyList(),
)

data class ActivityLine(val day: LocalDate, val name: String, val kcal: Double, val minutes: Int?, val fromWhoop: Boolean)

/**
 * The chat's exact-answer engine. Common questions about the user's own numbers are answered
 * directly from data (they can't be wrong); [answer] returns null for anything else, which then
 * goes to the on-device LLM with [context] as grounding.
 */
object HealthChat {
    private fun n(v: Double) = "%,d".format(v.roundToInt())
    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }

    fun answer(question: String, s: HealthSnapshot): String? {
        val q = " " + question.lowercase().replace(Regex("[^a-z0-9 ]"), " ") + " "
        return when {
            has(q, " weight", " weigh", " kg ", " lost ", " lose ", " gained ", " heavier", " lighter") -> weight(s)
            has(q, " what should i eat", " suggest", " recommend", " ideas", " what can i eat", " what to eat") -> suggest(s)
            has(q, " activit", " workout", " exercise", " sport", " walk", " run", " gym", " badminton", " trained", " training", " active ") -> activities(q, s)
            has(q, " left ", " remaining", " how much more", " how many more", " can i still", " budget") -> remaining(s)
            has(q, " what did i eat", " what have i eaten", " what i ate", " my meals", " eaten today", " ate today", " list ") -> todaysFoods(s)
            has(q, " biggest", " most calories", " top food", " source", " main food", " eat the most", " eat most") -> biggest(s)
            has(q, " food type", " category", " categories", " kind of food", " types of food", " carbs", " grains") -> types(s)
            has(q, " week", " 7 days", " seven days", " lately", " recently", " average") -> week(s)
            has(q, " overall", " all time", " since i started", " so far in total", " progress", " trend") -> overall(s)
            has(q, " today", " doing", " deficit", " surplus", " balance", " burned", " burnt", " calories in", " how am i") -> today(s)
            has(q, " protein", " carb ", " fat ", " sugar", " sodium", " fiber", " fibre", " vitamin", " macro") ->
                "Aarogya tracks calories only, so I don't have protein, carb or fat numbers for your meals."
            else -> null
        }
    }

    private fun today(s: HealthSnapshot): String {
        if (s.todayFoods.isEmpty() && s.todayOut == null) return "Nothing is logged for today yet. Add a meal and I can tell you how the day is going."
        val left = s.goal - s.todayIn
        val base = "Today you've eaten ${n(s.todayIn)} kcal of your ${n(s.goal.toDouble())} kcal goal" +
            (if (left >= 0) " (${n(left)} left)." else " (${n(-left)} over).")
        val out = s.todayOut ?: return "$base Your calories burned aren't known yet — connect WHOOP or set a base burn in Settings to see your deficit."
        val net = s.todayIn - out
        return "$base You've burned ${n(out)} kcal (${s.burnSource}), so you're at a " +
            (if (net <= 0) "deficit of ${n(-net)} kcal." else "surplus of ${n(net)} kcal.")
    }

    private fun remaining(s: HealthSnapshot): String {
        val left = s.goal - s.todayIn
        return if (left >= 0) "You have ${n(left)} kcal left of your ${n(s.goal.toDouble())} kcal goal today (eaten ${n(s.todayIn)} so far)."
        else "You're ${n(-left)} kcal over your ${n(s.goal.toDouble())} kcal goal today (eaten ${n(s.todayIn)})."
    }

    private fun todaysFoods(s: HealthSnapshot): String {
        if (s.todayFoods.isEmpty()) return "You haven't logged any food today yet."
        val byMeal = s.todayFoods.groupBy { it.meal }.toSortedMap()
        return "Today: " + byMeal.entries.joinToString("; ") { (meal, list) ->
            meal.label + " — " + list.joinToString(", ") { "${it.name} ${n(it.kcal)} kcal" }
        } + ". Total ${n(s.todayIn)} kcal."
    }

    private fun week(s: HealthSnapshot): String {
        val summary = Analyzer.week(s.recent, s.restingBurn)
            ?: return "There aren't any complete days (with both food and calories burned) in the last 7 days yet."
        val kg = abs(summary.projectedKgPerWeek)
        return "Over the last 7 days (${summary.loggedDays} complete day${if (summary.loggedDays == 1) "" else "s"}) you ate ${n(summary.avgIn.toDouble())} kcal and burned " +
            "${n(summary.avgOut.toDouble())} kcal a day on average — a ${if (summary.avgNet <= 0) "deficit" else "surplus"} of ${n(abs(summary.avgNet).toDouble())} kcal/day, " +
            "about ${if (summary.projectedKgPerWeek <= 0) "−" else "+"}${"%.1f".format(kg)} kg per week at this pace."
    }

    private fun overall(s: HealthSnapshot): String {
        val o = s.overall ?: return "I need some days with both food and calories burned logged before I can show your overall progress."
        return "Across ${o.completeDays} complete days you've averaged ${n(o.avgIn)} kcal in and ${n(o.avgOut)} kcal out — " +
            "a net ${if (o.avgNet <= 0) "deficit" else "surplus"} of ${n(abs(o.avgNet))} kcal/day, roughly " +
            "${if (o.estimatedKgChange <= 0) "−" else "+"}${"%.1f".format(abs(o.estimatedKgChange))} kg in total."
    }

    private fun biggest(s: HealthSnapshot): String {
        if (s.topFoods.isEmpty()) return "You haven't logged enough food yet for me to find your biggest calorie sources."
        val total = s.byCategory.sumOf { it.second }.coerceAtLeast(1.0)
        val foods = s.topFoods.take(3).joinToString(", ") { "${it.name} (${n(it.kcal)} kcal over ${it.times} time${if (it.times == 1) "" else "s"})" }
        val type = s.byCategory.firstOrNull()?.let { (c, v) -> " By food type, ${c.label.lowercase()} lead with ${(v / total * 100).roundToInt()}% of your calories." } ?: ""
        return "Your biggest calorie sources overall are $foods.$type"
    }

    private fun types(s: HealthSnapshot): String {
        if (s.byCategory.isEmpty()) return "You haven't logged enough food yet to break it down by type."
        val total = s.byCategory.sumOf { it.second }.coerceAtLeast(1.0)
        return "Your calories by food type: " + s.byCategory.take(5).joinToString(", ") { (c, v) -> "${c.label} ${(v / total * 100).roundToInt()}%" } + "."
    }

    private fun activities(q: String, s: HealthSnapshot): String {
        val todayOnly = q.contains(" today")
        val list = if (todayOnly) s.activities.filter { it.day == s.today } else s.activities
        val period = if (todayOnly) "today" else "in the last 7 days"
        if (list.isEmpty()) return "No activities recorded $period. Connect WHOOP or add an activity on the Day screen."
        val fmt = DateTimeFormatter.ofPattern("EEE")
        val byName = list.groupBy { it.name.lowercase() }.values
            .map { group -> Triple(group.first().name, group.sumOf { it.kcal }, group) }
            .sortedByDescending { it.second }
        val details = byName.joinToString("; ") { (name, kcal, group) ->
            val mins = group.mapNotNull { it.minutes }.sum()
            val days = if (todayOnly) "" else " on " + group.map { it.day.format(fmt) }.distinct().joinToString("/")
            "$name ${n(kcal)} kcal" + (if (mins > 0) " over $mins min" else "") + days
        }
        val note = if (list.any { it.fromWhoop }) " WHOOP activities are already part of WHOOP's daily burn." else ""
        return "Activities $period: $details. Total ${n(list.sumOf { it.kcal })} kcal.$note"
    }

    private fun weight(s: HealthSnapshot): String {
        val w = s.weights ?: return "Your weight is locked. Unlock the Weight tab with your PIN and ask again."
        if (w.isEmpty()) return "I don't have any weight readings yet — connect Google Health in the Weight tab."
        val latest = w.first()
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        val monthAgo = w.firstOrNull { !it.day.isAfter(latest.day.minusDays(30)) }
        val change = monthAgo?.let { latest.kg - it.kg }
        return "Your latest weight is ${"%.1f".format(latest.kg)} kg (${latest.day.format(fmt)})." + when {
            change == null -> ""
            abs(change) < 0.05 -> " That's unchanged from ${monthAgo.day.format(fmt)}."
            change < 0 -> " You're down ${"%.1f".format(-change)} kg since ${monthAgo.day.format(fmt)}."
            else -> " You're up ${"%.1f".format(change)} kg since ${monthAgo.day.format(fmt)}."
        }
    }

    /** Suggestions grounded in what the user actually eats, sized to fit what's left today. */
    private fun suggest(s: HealthSnapshot): String {
        val left = s.goal - s.todayIn
        if (left <= 100) return "You've already reached your goal for today (${n(s.todayIn)} of ${n(s.goal.toDouble())} kcal). A light option like a salad, soup or fruit would keep you close to it."
        val usual = s.topFoods.mapNotNull { stat -> s.foodTable.firstOrNull { it.name.equals(stat.name, true) || it.aliases.any { a -> a.equals(stat.name, true) } } }
        val fits = usual.filter { it.kcalPerServing <= left }.take(4)
        val picks = fits.ifEmpty { s.foodTable.filter { it.kcalPerServing in 150.0..left.coerceAtMost(600.0) }.shuffled(java.util.Random(s.today.toEpochDay())).take(3) }
        if (picks.isEmpty()) return "You have ${n(left)} kcal left today — something light would fit best."
        return "You have ${n(left)} kcal left today. From foods you usually eat, these fit: " +
            picks.joinToString(", ") { f ->
                // "1 roti", but "1 bowl of dal"
                val portion = if (f.name.contains(f.servingLabel, ignoreCase = true)) "1 ${f.name}" else "1 ${f.servingLabel} of ${f.name}"
                "$portion (~${n(f.kcalPerServing)} kcal)"
            } + "."
    }

    /** Compact plain-text grounding for the LLM (kept short so a small model stays on track). */
    fun context(s: HealthSnapshot): String = buildString {
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM")
        appendLine("Today is ${s.today.format(fmt)}. Daily calorie goal: ${n(s.goal.toDouble())} kcal.")
        append("Today: ate ${n(s.todayIn)} kcal")
        s.todayOut?.let { out -> val net = s.todayIn - out; append(", burned ${n(out)} kcal (${s.burnSource}), ${if (net <= 0) "deficit ${n(-net)}" else "surplus ${n(net)}"} kcal") }
        appendLine(".")
        if (s.todayFoods.isNotEmpty()) appendLine("Today's foods: " + s.todayFoods.joinToString(", ") { "${it.name} ${n(it.kcal)} kcal (${it.meal.label})" } + ".")
        Analyzer.week(s.recent, s.restingBurn)?.let {
            appendLine("Last 7 days: average ate ${n(it.avgIn.toDouble())} kcal/day, burned ${n(it.avgOut.toDouble())} kcal/day, average ${if (it.avgNet <= 0) "deficit" else "surplus"} ${n(abs(it.avgNet).toDouble())} kcal/day.")
        }
        if (s.activities.isNotEmpty()) appendLine("Activities in the last 7 days: " + s.activities.joinToString(", ") { "${it.name} ${n(it.kcal)} kcal" } + ".")
        if (s.topFoods.isNotEmpty()) appendLine("Most eaten foods: " + s.topFoods.take(5).joinToString(", ") { "${it.name} (${it.times} times)" } + ".")
        val total = s.byCategory.sumOf { it.second }
        if (total > 0) appendLine("Calories by food type: " + s.byCategory.take(4).joinToString(", ") { (c, v) -> "${c.label} ${(v / total * 100).roundToInt()}%" } + ".")
        when {
            s.weights == null -> appendLine("Weight: locked by the user.")
            s.weights.isNotEmpty() -> appendLine("Latest weight: ${"%.1f".format(s.weights.first().kg)} kg.")
        }
    }.trim()

    const val SYSTEM =
        "You are Aarogya, a friendly health assistant in a calorie-tracking app. Talk to the user as \"you\". " +
            "Answer in 1-3 short sentences. Use only the numbers in the user's data; never invent numbers or calories. " +
            "If the data doesn't answer the question, give brief general guidance and say it's general advice."
}
