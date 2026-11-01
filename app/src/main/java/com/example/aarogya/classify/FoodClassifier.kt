package com.example.aarogya.classify

import com.example.aarogya.AarogyaApp
import com.example.aarogya.BuildConfig
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.FoodStatus
import com.example.aarogya.data.MealParser
import com.example.aarogya.data.Suggestion
import com.example.aarogya.gemini.GeminiFood
import com.example.aarogya.llm.FoodVisionModel
import com.example.aarogya.llm.ModelOption
import com.example.aarogya.llm.ModelStatus
import com.example.aarogya.llm.PlateVisionModel
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/** Gemini couldn't classify the entry; the user picks from [suggestions] or describes it. */
class NeedsUserInput(val suggestions: List<Suggestion>, message: String) : IOException(message)

/**
 * Turns a pending entry (a meal photo, or typed food the table doesn't know) into foods with calories.
 * Each attempt asks Gemini (main model, then the fast fallback). When Gemini is off, or on the final
 * attempt, the on-device models are asked for suggestions instead and the user confirms them,
 * because their results are too unreliable to apply silently.
 */
class FoodClassifier(private val app: AarogyaApp) {

    /** Returns the classified foods, or throws [NeedsUserInput] / another exception with a short reason. */
    suspend fun classify(entry: FoodEntry, lastAttempt: Boolean): List<FoodEntry> {
        val geminiOn = BuildConfig.GEMINI_API_KEY.isNotEmpty() && app.prefs.state.value.useGemini
        if (!geminiOn) throw NeedsUserInput(localSuggestions(entry), "Gemini is off")

        val reason = try {
            val gemini = GeminiFood(BuildConfig.GEMINI_API_KEY, listOf(BuildConfig.GEMINI_MODEL, GeminiFood.FALLBACK_MODEL))
            val items = if (entry.photo != null) gemini.analyze(File(entry.photo).readBytes()) else gemini.analyzeText(entry.name)
            if (items.isNotEmpty()) return items.map { g -> done(entry, g.name, g.grams, g.kcal, "gemini") }
            "Gemini found no food"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Gemini failed"
        }
        if (lastAttempt) throw NeedsUserInput(localSuggestions(entry), reason)
        throw IOException(reason)
    }

    /** On-device guesses: dish recognizer + plate analyzer for photos, parser + SmolLM2 for text. */
    suspend fun localSuggestions(entry: FoodEntry): List<Suggestion> = runCatching {
        if (entry.photo == null) return@runCatching localParse(entry.name)
        val db = app.foodDb
        val found = LinkedHashMap<String, Suggestion>()
        fun add(f: com.example.aarogya.data.Food) {
            found.getOrPut(f.name) { Suggestion(f.name.replaceFirstChar { it.uppercase() }, f.servingGrams, f.kcalPerServing) }
        }
        val dishModel = FoodVisionModel.takeIf { app.models.status(it) == ModelStatus.Ready }?.let { app.models.file(it, FoodVisionModel.FILE) }
        app.photos.labels(entry.photo, dishModel).labels.forEach { label -> db.match(label)?.let(::add) }
        if (app.models.status(PlateVisionModel) == ModelStatus.Ready) db.mentions(app.plateAi.describe(entry.photo)).forEach(::add)
        found.values.take(6)
    }.getOrElse { if (it is CancellationException) throw it; emptyList() }

    /**
     * Derives foods and amounts from a description on-device: the rule parser first, then SmolLM2 for
     * phrases it couldn't place. Calories come from the food table (SmolLM2 is reliable at naming foods
     * and amounts, not at calorie numbers). Phrases with no table match are returned in [Parsed.unknown].
     */
    suspend fun localParseDetailed(text: String): Parsed {
        val db = app.foodDb
        val out = ArrayList<Suggestion>()
        val unknown = ArrayList<String>()
        fun addParsed(p: com.example.aarogya.data.ParsedItem): Boolean {
            val f = db.match(p.phrase) ?: return false
            val grams = MealParser.grams(f, p.quantity, p.unit)
            out += Suggestion(f.name.replaceFirstChar { it.uppercase() }, grams, grams * f.kcalPer100g / 100)
            return true
        }
        val rules = MealParser.parseRules(text)
        val unmatched = rules.filterNot(::addParsed).map { it.phrase }.ifEmpty { if (rules.isEmpty()) listOf(text.trim()) else emptyList() }
        if (unmatched.isNotEmpty()) {
            val option = ModelOption.entries.firstOrNull { it.id == app.prefs.state.value.model && app.models.status(it) == ModelStatus.Ready }
                ?: ModelOption.entries.firstOrNull { app.models.status(it) == ModelStatus.Ready }
            val llm = option?.let { app.mealAi.parse(it, unmatched.joinToString(", ")) }.orEmpty()
            val placed = llm.filter(::addParsed).map { it.phrase }
            unknown += unmatched.filterNot { u -> placed.any { p -> u.contains(p, true) || p.contains(u, true) } }
        }
        return Parsed(out, unknown)
    }

    data class Parsed(val foods: List<Suggestion>, val unknown: List<String>)

    private suspend fun localParse(text: String): List<Suggestion> = localParseDetailed(text).foods

    private fun done(entry: FoodEntry, name: String, grams: Double?, kcal: Double, source: String) = FoodEntry(
        day = entry.day, time = entry.time, meal = entry.meal, name = name, grams = grams?.takeIf { it > 0 },
        kcal = kcal, photo = entry.photo, source = source, status = FoodStatus.DONE,
    )
}
