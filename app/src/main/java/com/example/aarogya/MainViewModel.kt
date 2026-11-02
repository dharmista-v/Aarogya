package com.example.aarogya

import com.example.aarogya.data.AppTime
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.BurnEntry
import com.example.aarogya.data.DayAnalysis
import com.example.aarogya.data.DayTotals
import com.example.aarogya.data.Food
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.Meal
import com.example.aarogya.data.MealParser
import com.example.aarogya.classify.ClassifyWorker
import com.example.aarogya.classify.FoodClassifier
import com.example.aarogya.data.Suggestion
import com.example.aarogya.data.FoodStatus
import com.example.aarogya.data.Portions
import com.example.aarogya.data.QtyUnit
import com.example.aarogya.data.FoodCategories
import com.example.aarogya.data.FoodCategory
import com.example.aarogya.data.HealthChat
import com.example.aarogya.data.HealthSnapshot
import com.example.aarogya.data.Prefs
import com.example.aarogya.data.Store
import com.example.aarogya.reminders.MealReminders
import com.example.aarogya.reminders.Reminder
import com.example.aarogya.data.WeightEntry
import com.example.aarogya.data.WhoopWorkout
import com.example.aarogya.gemini.GeminiFood
import com.example.aarogya.gemini.GeminiItem
import kotlin.math.roundToInt
import com.example.aarogya.llm.Downloadable
import com.example.aarogya.llm.FoodVisionModel
import com.example.aarogya.llm.ModelOption
import com.example.aarogya.llm.ModelStatus
import com.example.aarogya.llm.PlateVisionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

data class DayUi(
    val day: LocalDate,
    val foods: List<FoodEntry>,
    val burns: List<BurnEntry>,
    val analysis: DayAnalysis,
    val whoopWorkouts: List<WhoopWorkout> = emptyList(),
)

data class TrendsUi(val days: List<DayTotals>, val week: Analyzer.WeekSummary?, val restingBurn: Int, val goal: Int)

/** One chat bubble. [fromData] answers are computed exactly; LLM ones are marked as possibly inaccurate. */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val kind: Kind = Kind.USER,
    val streaming: Boolean = false,
    /** Shown while there's no text yet, e.g. "Loading the on-device model…". */
    val status: String? = null,
) {
    enum class Kind { USER, FROM_DATA, LLM, INFO }
}

/** All-time analysis for the Trends "Overall" view. */
data class OverallUi(
    val overall: Analyzer.Overall?,
    val mealTotals: Map<Meal, Double>,
    val topFoods: List<Store.FoodStat>,
    val firstDay: LocalDate?,
    /** All-time calories per food type, largest first. */
    val byCategory: List<Pair<FoodCategory, Double>> = emptyList(),
)

/** An item on the plate being composed in the Add food sheet. */
data class PlateItem(
    val key: Long,
    val name: String,
    val food: Food?,
    val grams: Double,
    val customKcal: Double?,
    val source: String,
    /** Portion as described by Gemini ("2 rotis"); its grams/kcal are estimates the user can edit. */
    val portion: String? = null,
    /** Unit the amount is shown and entered in; the amount itself is always [grams]. */
    val qtyUnit: QtyUnit = QtyUnit.SERVING,
    /** Gemini items: one unit ("roti") and its share of grams/kcal, so the count can be changed. */
    val unitLabel: String? = null,
    val unitGrams: Double? = null,
    val unitKcal: Double? = null,
    val count: Double = 1.0,
) {
    val kcal: Double get() = food?.let { grams * it.kcalPer100g / 100.0 } ?: customKcal ?: 0.0
    val isValid: Boolean get() = food != null || (customKcal != null && customKcal >= 0)

    /** Natural unit: the table food's serving ("taco", "fillet") or Gemini's ("roti"). */
    val unit: String? get() = food?.servingLabel ?: unitLabel?.takeIf { unitKcal != null }
    val canCount: Boolean get() = unit != null
    val quantity: Double get() = food?.let { grams / it.servingGrams } ?: count

    /** Grams in one of the food's own units (table serving, or Gemini's per-unit share). */
    private val servingGrams: Double? get() = food?.servingGrams ?: unitGrams
    /** Units this item can be entered in: its own unit, plus weight/volume when its grams are known. */
    val units: List<QtyUnit> get() = QtyUnit.available(hasServing = unit != null, hasGrams = food != null || (unitGrams ?: 0.0) > 0)

    /** The amount in [qtyUnit] ("1.5" tacos, "150" g, "5.3" oz). */
    val amount: Double get() = if (qtyUnit == QtyUnit.SERVING) quantity else grams / (qtyUnit.gramsPer(servingGrams) ?: 1.0)

    fun withAmount(a: Double): PlateItem {
        if (qtyUnit == QtyUnit.SERVING) return withQuantity(a)
        val g = a * (qtyUnit.gramsPer(servingGrams) ?: return this)
        return if (food != null) copy(grams = g) else withQuantity(g / (unitGrams ?: return this))
    }

    fun withQuantity(q: Double): PlateItem = when {
        food != null -> copy(grams = q * food.servingGrams)
        unitKcal != null -> copy(count = q, customKcal = unitKcal * q, grams = (unitGrams ?: 0.0) * q)
        else -> this
    }

    /** User typed calories: they become the user's own, and the per-unit share follows. */
    fun withKcal(k: Double): PlateItem =
        if (customKcal?.roundToInt() == k.roundToInt()) this
        else copy(customKcal = k, unitKcal = unitKcal?.let { k / count }, source = "manual")

    companion object {
        fun fromGemini(key: Long, g: GeminiItem): PlateItem {
            val p = Portions.parse(g.portion)
            return PlateItem(
                key, g.name, null, g.grams ?: 0.0, g.kcal, "gemini", portion = g.portion.ifEmpty { null },
                unitLabel = p.unit, unitGrams = g.grams?.div(p.count), unitKcal = g.kcal / p.count, count = p.count,
            )
        }
    }
}

/** Gemini's progress on the current photo; [error] means it failed and on-device models were used. */
data class GeminiState(val running: Boolean, val found: Int = 0, val error: String? = null)

/** What SmolVLM said about the photo, and the foods from the table it mentioned. */
data class PlateAnalysis(val running: Boolean, val description: String = "", val foods: List<Food> = emptyList(), val modelMissing: Boolean = false)

data class Draft(
    val meal: Meal = defaultMeal(),
    val items: List<PlateItem> = emptyList(),
    val photo: String? = null,
    val labels: List<String>? = null, // null = still looking at the photo
    val thinking: Boolean = false,
    val message: String? = null,
    val dishModelMissing: Boolean = false,
    val plate: PlateAnalysis? = null,
    val gemini: GeminiState? = null,
    /** Typed foods still being worked out; saving now queues them as pending. */
    val unresolved: List<String> = emptyList(),
) {
    val total: Double get() = items.sumOf { it.kcal }
    /** Items without calories (and a photo with no items yet) are saved as pending and classified in the background. */
    val canSave: Boolean get() = items.isNotEmpty() || photo != null || unresolved.isNotEmpty()
    val pendingCount: Int get() = items.count { !it.isValid } + unresolved.size + if (photo != null && items.isEmpty()) 1 else 0

    companion object {
        fun defaultMeal(): Meal = AppTime.nowTime().hour.let {
            when {
                it < 11 -> Meal.BREAKFAST
                it < 16 -> Meal.LUNCH
                it < 19 -> Meal.SNACK
                else -> Meal.DINNER
            }
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AarogyaApp
    private val store = app.store
    val foodDb get() = app.foodDb

    val settings: StateFlow<Prefs.Settings> = app.prefs.state

    private val _day = MutableStateFlow(AppTime.today())
    val day: StateFlow<LocalDate> = _day

    val dayUi: StateFlow<DayUi?> = combine(_day, store.version, settings) { d, _, s ->
        val foods = store.foods(d)
        val burns = store.burns(d)
        val recent = store.totals(d.minusDays(14), d.minusDays(1))
        val whoopDay = store.whoopDay(d)
        val workouts = store.whoopWorkouts(d)
        DayUi(d, foods, burns, Analyzer.analyze(foods, burns, s, recent, d == AppTime.today(), whoopDay, workouts), workouts)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trends: StateFlow<TrendsUi?> = combine(store.version, settings) { _, s ->
        val today = AppTime.today()
        val days = store.totals(today.minusDays(13), today)
        TrendsUi(days, Analyzer.week(days.takeLast(7), s.restingBurn), s.restingBurn, s.goal)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val overall: StateFlow<OverallUi?> = combine(store.version, settings) { _, s ->
        val today = AppTime.today()
        val first = store.firstDay()
        val days = first?.let { store.totals(minOf(it, today), today) }.orEmpty()
        val byCategory = store.topFoods(Int.MAX_VALUE)
            .groupBy({ FoodCategories.categorize(it.name) }, { it.kcal })
            .map { (c, kcals) -> c to kcals.sum() }
            .sortedByDescending { it.second }
        OverallUi(Analyzer.overall(days, s.restingBurn), store.mealTotals(), store.topFoods(8), first, byCategory)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectDay(d: LocalDate) { if (!d.isAfter(AppTime.today())) _day.value = d }
    fun shiftDay(by: Long) = selectDay(_day.value.plusDays(by))

    fun deleteFood(id: Long) = viewModelScope.launch(Dispatchers.IO) { app.photos.delete(store.deleteFood(id)) }
    fun deleteFoods(ids: Collection<Long>) = viewModelScope.launch(Dispatchers.IO) {
        store.deleteFoods(ids).forEach(app.photos::delete)
    }

    fun deleteBurn(id: Long) = viewModelScope.launch(Dispatchers.IO) { store.deleteBurn(id) }

    fun addBurn(name: String, kcal: Double) = viewModelScope.launch(Dispatchers.IO) {
        store.addBurn(BurnEntry(day = _day.value, time = System.currentTimeMillis(), name = name, kcal = kcal))
    }

    // ---------------- Google Health (weight) ----------------

    val googleHealth = app.googleHealth.state
    val weights: StateFlow<List<WeightEntry>?> = store.version.map { store.weights() }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Returns Google's consent screen to launch, or null if access was granted (and syncing started). */
    suspend fun beginGoogleHealthConnect(): PendingIntent? = app.googleHealth.beginConnect()
    fun completeGoogleHealthConnect(data: Intent?) = viewModelScope.launch { app.googleHealth.completeConnect(data) }
    fun syncGoogleHealth() = viewModelScope.launch { app.googleHealth.syncSilently() }
    fun disconnectGoogleHealth() = viewModelScope.launch(Dispatchers.IO) { app.googleHealth.disconnect() }

    // ---------------- Chat (on-device) ----------------

    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chat: StateFlow<List<ChatMessage>> = _chat
    private var chatJob: Job? = null
    private var chatId = 0L

    val chatBusy: Boolean get() = chatJob?.isActive == true

    private fun chatModel(): ModelOption? =
        ModelOption.entries.firstOrNull { it.id == settings.value.model && app.models.status(it) == ModelStatus.Ready }
            ?: ModelOption.entries.firstOrNull { app.models.status(it) == ModelStatus.Ready }

    /** Called when the Chat tab opens: load SmolLM2 in the background so it's ready for the first question. */
    fun warmUpChat() = viewModelScope.launch {
        val model = withContext(Dispatchers.IO) { chatModel() } ?: return@launch
        if (!app.mealAi.isLoaded(model)) runCatching { app.mealAi.preload(model) }
    }

    fun newChat() {
        chatJob?.cancel()
        _chat.value = emptyList()
    }

    /** Exact answer from the data when the question is a known kind; otherwise SmolLM2 with the data as context. */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || chatBusy) return
        _chat.update { it + ChatMessage(chatId++, true, q) }
        chatJob = viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { healthSnapshot() }
            HealthChat.answer(q, snapshot)?.let { exact ->
                _chat.update { it + ChatMessage(chatId++, false, exact, ChatMessage.Kind.FROM_DATA) }
                return@launch
            }
            val model = withContext(Dispatchers.IO) { chatModel() }
            if (model == null) {
                _chat.update {
                    it + ChatMessage(chatId++, false, "For open questions I need the on-device AI — download SmolLM2 in Settings → On-device AI (it runs entirely on your phone).", ChatMessage.Kind.INFO)
                }
                return@launch
            }
            val id = chatId++
            val status = if (app.mealAi.isLoaded(model)) null else "Loading the on-device model — the first answer takes a moment…"
            _chat.update { it + ChatMessage(id, false, "", ChatMessage.Kind.LLM, streaming = true, status = status) }
            // Recent turns give follow-ups some memory; kept short so the small model stays focused.
            val turns = _chat.value.dropLast(2).zipWithNext()
                .filter { (u, a) -> u.fromUser && !a.fromUser && a.kind != ChatMessage.Kind.INFO }
                .takeLast(2).map { (u, a) -> u.text to a.text }
            val system = HealthChat.SYSTEM + "\n\nUser's data:\n" + HealthChat.context(snapshot)
            val reply = runCatching {
                app.mealAi.chat(model, system, turns, q) { partial -> _chat.update { list -> list.map { m -> if (m.id == id) m.copy(text = partial) else m } } }
            }.getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e; "Sorry, the on-device AI couldn't answer (${e.message})." }
            _chat.update { list -> list.map { m -> if (m.id == id) m.copy(text = reply.ifBlank { "I'm not sure about that." }, streaming = false) else m } }
        }
    }

    /** Gathers what the chat may use. Weight is only included while the Weight tab is unlocked. */
    private fun healthSnapshot(): HealthSnapshot {
        val s = settings.value
        val today = AppTime.today()
        val foods = store.foods(today)
        val burns = store.burns(today)
        val whoopDay = store.whoopDay(today)
        val analysis = Analyzer.analyze(foods, burns, s, emptyList(), true, whoopDay)
        val todayOut = if (analysis.hasFullBurn || analysis.kcalActive > 0) analysis.kcalOut else null
        val first = store.firstDay()
        val allDays = first?.let { store.totals(minOf(it, today), today) }.orEmpty()
        val top = store.topFoods(Int.MAX_VALUE)
        return HealthSnapshot(
            today = today,
            goal = s.goal,
            todayFoods = foods.filter { it.status == FoodStatus.DONE },
            todayIn = analysis.kcalIn,
            todayOut = todayOut,
            burnSource = when { whoopDay != null -> "WHOOP"; s.restingBurn > 0 -> "base burn + activities"; else -> "logged activities" },
            recent = store.totals(today.minusDays(6), today),
            restingBurn = s.restingBurn,
            overall = Analyzer.overall(allDays, s.restingBurn),
            topFoods = top.take(8),
            byCategory = top.groupBy({ FoodCategories.categorize(it.name) }, { it.kcal }).map { (c, k) -> c to k.sum() }.sortedByDescending { it.second },
            weights = if (app.pinLock.unlocked.value) store.weights() else null,
            foodTable = foodDb.foods,
        )
    }

    // ---------------- Weight PIN ----------------

    val pinSet = app.pinLock.isSet
    val pinUnlocked = app.pinLock.unlocked
    fun setPin(pin: String) = app.pinLock.set(pin)
    fun unlockPin(pin: String) = app.pinLock.unlock(pin)
    fun pinLockedOutSeconds() = app.pinLock.lockedOutSeconds()
    fun lockWeight() = app.pinLock.lock()

    /** Forgot PIN: drop the PIN and the data it protected; reconnecting Google proves identity again. */
    fun forgotPin() = viewModelScope.launch(Dispatchers.IO) {
        app.googleHealth.disconnect()
        app.pinLock.reset()
    }

    // ---------------- WHOOP ----------------

    val whoop = app.whoop.state

    fun whoopAuthUrl(): Uri = app.whoop.authUrl()

    fun onWhoopCallback(uri: Uri) = viewModelScope.launch {
        if (app.whoop.completeLogin(uri).isSuccess) app.whoop.sync()
    }

    /** Manual "Sync now"; automatic syncing on app open lives in [AarogyaApp]. */
    fun syncWhoop() = viewModelScope.launch {
        val s = app.whoop.state.value
        if (s.connected && !s.syncing) app.whoop.sync()
    }

    fun disconnectWhoop() = viewModelScope.launch(Dispatchers.IO) { app.whoop.disconnect() }

    /**
     * Switches the tracking time zone. Logged meals keep their day; WHOOP and weight data are
     * cleared and re-synced so they're re-dated in the new zone (no duplicates under old dates).
     */
    fun setTimeZone(id: String) = viewModelScope.launch(Dispatchers.IO) {
        if (id == settings.value.timeZone) return@launch
        app.prefs.update(timeZone = id)
        _day.value = AppTime.today()
        store.clearWhoop()
        store.clearWeights()
        launch { app.whoop.sync() }
        launch { app.googleHealth.syncSilently() }
        MealReminders.scheduleAll(app) // reminder times are in the tracking zone
    }

    // ---------------- Meal reminders ----------------

    fun setReminder(meal: Meal, reminder: Reminder) = viewModelScope.launch(Dispatchers.IO) {
        app.prefs.setReminder(meal, reminder)
        MealReminders.schedule(app, meal, reminder)
    }

    /** Set when a reminder notification is tapped: the UI opens Add food for this meal. */
    private val _addFoodRequest = MutableStateFlow<Meal?>(null)
    val addFoodRequest: StateFlow<Meal?> = _addFoodRequest
    fun requestAddFood(meal: Meal) {
        _day.value = AppTime.today() // a reminder is about today, whatever day was being viewed
        _addFoodRequest.value = meal
    }
    fun consumeAddFoodRequest(): Meal? = _addFoodRequest.value.also { _addFoodRequest.value = null }

    fun startDraft(meal: Meal) { _draft.value = Draft(meal = meal) }

    fun updateSettings(goal: Int, restingBurn: Int) = app.prefs.update(goal = goal, restingBurn = restingBurn)

    // ---------------- Add food draft ----------------

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft
    private var nextKey = 0L
    private var parseJob: Job? = null

    fun startDraft() { _draft.value = Draft() }

    fun discardDraft() {
        parseJob?.cancel()
        app.photos.delete(_draft.value.photo)
        _draft.value = Draft()
    }

    fun setMeal(meal: Meal) = _draft.update { it.copy(meal = meal) }

    fun addFood(food: Food, source: String = "manual", grams: Double = food.servingGrams, unit: QtyUnit = QtyUnit.SERVING) =
        _draft.update { it.copy(items = it.items + PlateItem(nextKey++, food.name.replaceFirstChar { it.uppercase() }, food, grams, null, source, qtyUnit = unit), message = null) }

    fun addCustom(name: String, source: String = "manual") =
        _draft.update { it.copy(items = it.items + PlateItem(nextKey++, name.trim().replaceFirstChar { c -> c.uppercase() }, null, 0.0, null, source)) }

    fun updateItem(key: Long, transform: (PlateItem) -> PlateItem) =
        _draft.update { d -> d.copy(items = d.items.map { if (it.key == key) transform(it) else it }) }

    fun removeItem(key: Long) = _draft.update { d -> d.copy(items = d.items.filterNot { it.key == key }) }

    /**
     * Understands a free-text meal: rules first (instant), then SmolLM2 for whatever the rules
     * couldn't map to a known food, then anything left becomes a custom item to fill in.
     */
    fun understand(text: String) {
        if (text.isBlank()) return
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            val parsed = MealParser.parseRules(text)
            val unmatched = mutableListOf<String>()
            for (p in parsed) {
                val food = foodDb.match(p.phrase)
                // A typed unit ("200g", "1 cup") becomes the picker's unit, unless it's the food's own ("1 bowl" of dal).
                val unit = QtyUnit.fromParsed(p.unit)?.takeIf { p.unit != food?.servingLabel } ?: QtyUnit.SERVING
                if (food != null) addFood(food, "text", MealParser.grams(food, p.quantity, p.unit), unit)
                else unmatched += p.phrase
            }
            if (parsed.isEmpty()) unmatched += text.trim()
            if (unmatched.isEmpty()) return@launch

            // While these are being worked out, Save stays available: they'd be queued as pending.
            _draft.update { it.copy(unresolved = it.unresolved + unmatched) }
            try {
                resolveUnmatched(unmatched)
            } finally {
                _draft.update { d -> d.copy(thinking = false, unresolved = d.unresolved - unmatched.toSet()) }
            }
        }
    }

    /** Gemini (text) when enabled, else SmolLM2 if downloaded; anything left becomes a custom item. */
    private suspend fun resolveUnmatched(unmatched: List<String>) {
        if (geminiEnabled()) {
            _draft.update { it.copy(thinking = true, message = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GeminiFood(BuildConfig.GEMINI_API_KEY, listOf(BuildConfig.GEMINI_MODEL, GeminiFood.FALLBACK_MODEL)).analyzeText(unmatched.joinToString(", "))
                }
            }
            val items = result.getOrNull()
            if (!items.isNullOrEmpty()) {
                _draft.update { d ->
                    d.copy(items = d.items + items.map { g -> PlateItem.fromGemini(nextKey++, g) })
                }
                return
            }
        }
        val model = withContext(Dispatchers.IO) { readyModel() }
        if (model != null) {
            _draft.update { it.copy(thinking = true, message = null) }
            val aiItems = runCatching { app.mealAi.parse(model, unmatched.joinToString(", ")) }.getOrDefault(emptyList())
            val stillUnknown = unmatched.toMutableList()
            for (p in aiItems) {
                val food = foodDb.match(p.phrase) ?: continue
                addFood(food, "ai", MealParser.grams(food, p.quantity, p.unit))
                stillUnknown.removeAll { u -> foodDb.match(u) == food || u.contains(p.phrase, ignoreCase = true) || p.phrase.contains(u, ignoreCase = true) }
            }
            stillUnknown.forEach { addCustom(it) }
        } else {
            unmatched.forEach { addCustom(it) }
        }
    }

    private var photoJob: Job? = null

    /** [cleanup] runs once processing ends (e.g. deleting the camera's temporary file). */
    fun onPhoto(uri: Uri, cleanup: () -> Unit = {}) {
        photoJob?.cancel()
        photoJob = viewModelScope.launch { processPhoto(uri) }.also { it.invokeOnCompletion { cleanup() } }
    }

    private suspend fun processPhoto(uri: Uri) {
        val path = app.photos.importPhoto(uri) ?: run {
            _draft.update { it.copy(message = "Couldn't read that photo.") }
            return
        }
        app.photos.delete(_draft.value.photo)
        _draft.update { it.copy(photo = path, labels = null, plate = null, gemini = null) }
        if (geminiEnabled() && analyzeWithGemini(path)) return
        // Gemini off or failed: on-device dish recognizer + plate analyzer.
        val dishModel = withContext(Dispatchers.IO) {
            FoodVisionModel.takeIf { app.models.status(it) == ModelStatus.Ready }?.let { app.models.file(it, FoodVisionModel.FILE) }
        }
        _draft.update { it.copy(dishModelMissing = dishModel == null) }
        val result = app.photos.labels(path, dishModel)
        // The generic labeler knows ~450 everyday things ("Flower", "Plant", "Bread"), not dishes:
        // keep only labels that are actually foods in the table.
        val labels = if (result.dishLevel) result.labels else result.labels.filter { foodDb.match(it) != null }
        _draft.update { if (it.photo == path) it.copy(labels = labels) else it }
        analyzePlate(path)
    }

    /** Runs SmolVLM on the photo if it's downloaded; its findings are offered as suggestions, never auto-added. */
    private suspend fun analyzePlate(path: String) {
        val ready = withContext(Dispatchers.IO) { app.models.status(PlateVisionModel) == ModelStatus.Ready }
        if (!ready) {
            _draft.update { if (it.photo == path) it.copy(plate = PlateAnalysis(running = false, modelMissing = true)) else it }
            return
        }
        _draft.update { if (it.photo == path) it.copy(plate = PlateAnalysis(running = true)) else it }
        val description = runCatching { app.plateAi.describe(path) }.getOrDefault("")
        val foods = foodDb.mentions(description)
        _draft.update { if (it.photo == path) it.copy(plate = PlateAnalysis(running = false, description = description, foods = foods)) else it }
    }

    // ---------------- Gemini (cloud) ----------------

    val geminiConfigured: Boolean get() = BuildConfig.GEMINI_API_KEY.isNotEmpty()
    private fun geminiEnabled() = geminiConfigured && settings.value.useGemini
    fun setUseGemini(on: Boolean) = app.prefs.update(useGemini = on)

    /** Every food Gemini sees goes on the plate with its calorie estimate. Returns false on failure. */
    private suspend fun analyzeWithGemini(path: String): Boolean {
        _draft.update { if (it.photo == path) it.copy(gemini = GeminiState(running = true)) else it }
        val result = withContext(Dispatchers.IO) {
            runCatching { GeminiFood(BuildConfig.GEMINI_API_KEY, listOf(BuildConfig.GEMINI_MODEL, GeminiFood.FALLBACK_MODEL)).analyze(File(path).readBytes()) }
        }
        if (_draft.value.photo != path) return true // user moved on to another photo
        result.onSuccess { items ->
            _draft.update { d ->
                d.copy(
                    gemini = GeminiState(running = false, found = items.size),
                    items = d.items + items.map { g ->
                        PlateItem.fromGemini(nextKey++, g)
                    },
                    labels = emptyList(),
                )
            }
        }.onFailure { e ->
            _draft.update { it.copy(gemini = GeminiState(running = false, error = e.message ?: "Gemini failed")) }
        }
        return result.isSuccess
    }

    fun newCameraFile(): File = app.photos.newCameraFile()
    fun discardFile(file: File) = file.delete()

    fun saveDraft() {
        val d = _draft.value
        if (!d.canSave) return
        photoJob?.cancel() // anything unfinished is handed to the background queue instead
        parseJob?.cancel()
        val now = System.currentTimeMillis()
        val entries = d.items.map {
            FoodEntry(
                day = _day.value, time = now, meal = d.meal, name = it.name,
                grams = it.grams.takeIf { g -> g > 0 }, kcal = it.kcal,
                photo = d.photo, source = it.source,
                status = if (it.isValid) FoodStatus.DONE else FoodStatus.PENDING,
            )
        } + d.unresolved.map {
            FoodEntry(day = _day.value, time = now, meal = d.meal, name = it.replaceFirstChar { c -> c.uppercase() }, grams = null, kcal = 0.0, photo = null, source = "text", status = FoodStatus.PENDING)
        }.let { typed ->
            typed.ifEmpty {
                // A photo that hasn't been classified yet becomes one pending "meal photo" entry.
                listOfNotNull(d.photo?.let { FoodEntry(day = _day.value, time = now, meal = d.meal, name = "Meal photo", grams = null, kcal = 0.0, photo = it, source = "photo", status = FoodStatus.PENDING) })
            }
        }
        _draft.value = Draft() // the photo now belongs to the saved entries
        viewModelScope.launch(Dispatchers.IO) {
            store.addFoods(entries)
            if (entries.any { it.isPending }) ClassifyWorker.enqueue(app)
        }
    }

    // ---------------- All foods (Foods tab) ----------------

    private val _foodSearch = MutableStateFlow("")
    val foodSearch: StateFlow<String> = _foodSearch
    fun setFoodSearch(q: String) { _foodSearch.value = q }

    val allFoods: StateFlow<List<FoodEntry>?> = combine(store.version, _foodSearch) { _, q -> store.allFoods(q) }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun foodById(id: Long): FoodEntry? = allFoods.value?.firstOrNull { it.id == id } ?: dayUi.value?.foods?.firstOrNull { it.id == id }

    /** Saving an edit with calories set resolves a pending/failed entry. */
    fun updateFood(e: FoodEntry) = viewModelScope.launch(Dispatchers.IO) {
        store.updateFood(if (e.kcal > 0 && e.status != FoodStatus.DONE) e.copy(status = FoodStatus.DONE, error = null) else e)
    }

    /** Replaces a failed entry with the foods the user picked or described. */
    fun applySuggestions(e: FoodEntry, picks: List<Suggestion>) = viewModelScope.launch(Dispatchers.IO) {
        store.replaceEntry(e.id, picks.map { s ->
            FoodEntry(day = e.day, time = e.time, meal = e.meal, name = s.name, grams = s.grams, kcal = s.kcal, photo = e.photo, source = "ai")
        })
    }

    /** On-device only: rule parser + SmolLM2 for foods/amounts, food table for calories. */
    suspend fun deriveLocally(text: String): FoodClassifier.Parsed =
        withContext(Dispatchers.Default) { app.classifier.localParseDetailed(text) }

    /** Puts a failed entry back in the queue with a fresh set of attempts. */
    fun retryClassification(e: FoodEntry) = viewModelScope.launch(Dispatchers.IO) {
        store.updateFood(e.copy(status = FoodStatus.PENDING, attempts = 0, error = null, suggestions = emptyList()))
        ClassifyWorker.enqueue(app)
    }

    // ---------------- On-device model ----------------

    private fun readyModel(): ModelOption? =
        ModelOption.entries.firstOrNull { it.id == settings.value.model }?.takeIf { app.models.status(it) == ModelStatus.Ready }

    private val _modelStatus = MutableStateFlow<Map<Downloadable, ModelStatus>>(emptyMap())
    val modelStatus: StateFlow<Map<Downloadable, ModelStatus>> = _modelStatus
    private var pollJob: Job? = null

    /** Polls download progress while the settings sheet is open. */
    fun watchModels(active: Boolean) {
        pollJob?.cancel()
        if (!active) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val statuses = withContext(Dispatchers.IO) { ALL_MODELS.associateWith { app.models.status(it) } }
                _modelStatus.value = statuses
                // Auto-select the first language model that finishes downloading.
                if (settings.value.model == null) ModelOption.entries.firstOrNull { statuses[it] == ModelStatus.Ready }?.let { selectModel(it) }
                delay(if (statuses.values.any { it is ModelStatus.Downloading }) 600 else 2_000)
            }
        }
    }

    fun downloadModel(option: Downloadable) = viewModelScope.launch(Dispatchers.IO) {
        app.models.download(option)
        _modelStatus.update { it + (option to ModelStatus.Downloading(0f)) }
    }

    fun deleteModel(option: Downloadable) = viewModelScope.launch(Dispatchers.IO) {
        if (option == PlateVisionModel) app.plateAi.release()
        if (option is ModelOption && settings.value.model == option.id) {
            app.mealAi.release()
            app.prefs.update(model = null)
        }
        app.models.clear(option)
        _modelStatus.update { it + (option to ModelStatus.NotInstalled) }
    }

    fun selectModel(option: ModelOption) {
        app.mealAi.release()
        app.prefs.update(model = option.id)
    }

    private companion object {
        val ALL_MODELS: List<Downloadable> = ModelOption.entries + FoodVisionModel + PlateVisionModel
    }

    val modelLabel: StateFlow<String?> = settings.map { s -> ModelOption.entries.firstOrNull { it.id == s.model }?.title }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
