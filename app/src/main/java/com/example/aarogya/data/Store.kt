package com.example.aarogya.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

enum class Meal(val label: String) { BREAKFAST("Breakfast"), LUNCH("Lunch"), SNACK("Snack"), DINNER("Dinner") }

/** Classification state of a food entry; PENDING ones are waiting in the background queue. */
enum class FoodStatus { DONE, PENDING, FAILED }

/** A food the on-device models proposed for an entry Gemini couldn't classify. */
data class Suggestion(val name: String, val grams: Double?, val kcal: Double) {
    companion object {
        fun toJson(list: List<Suggestion>): String = org.json.JSONArray(list.map { s ->
            org.json.JSONObject().put("name", s.name).put("grams", s.grams ?: org.json.JSONObject.NULL).put("kcal", s.kcal)
        }).toString()

        fun fromJson(json: String?): List<Suggestion> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = org.json.JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Suggestion(o.getString("name"), o.optDouble("grams").takeUnless { it.isNaN() }, o.getDouble("kcal"))
            }
        }
    }
}

data class FoodEntry(
    val id: Long = 0,
    val day: LocalDate,
    val time: Long,
    val meal: Meal,
    val name: String,
    val grams: Double?,
    val kcal: Double,
    val photo: String?,
    val source: String,
    val status: FoodStatus = FoodStatus.DONE,
    val attempts: Int = 0,
    val error: String? = null,
    /** Local-model suggestions shown when classification failed. */
    val suggestions: List<Suggestion> = emptyList(),
) {
    val isPending: Boolean get() = status == FoodStatus.PENDING

    /** How the food was identified, for the tag in the Foods list. */
    val classifiedBy: ClassifiedBy get() = when (source) {
        "gemini" -> ClassifiedBy.GEMINI
        "ai", "photo" -> ClassifiedBy.LLM // SmolLM2 / SmolVLM / dish recognizer / picked on-device suggestion
        else -> ClassifiedBy.MANUAL // food-list pick, own calories, or edited by the user
    }
}

enum class ClassifiedBy(val label: String) { GEMINI("Gemini"), LLM("LLM"), MANUAL("Manual") }

data class BurnEntry(val id: Long = 0, val day: LocalDate, val time: Long, val name: String, val kcal: Double)

/** WHOOP's measured total burn for a day (from its cycle), and workouts it tracked. */
data class WhoopDay(val day: LocalDate, val kcal: Double, val strain: Double?)
data class WhoopWorkout(
    val id: String,
    val day: LocalDate,
    val start: Long,
    val sport: String,
    val kcal: Double,
    val strain: Double?,
    val minutes: Int? = null,
    val avgHeartRate: Int? = null,
    val distanceMeters: Double? = null,
)

/** All-time totals for one kind of activity (WHOOP workouts and logged activities combined). */
data class ActivityStat(val name: String, val sessions: Int, val kcal: Double, val minutes: Int)

/** One body-weight reading (from Google Health). [time] is epoch millis and identifies the reading. */
data class WeightEntry(val time: Long, val day: LocalDate, val kg: Double, val source: String)

data class DayTotals(val day: LocalDate, val kcalIn: Double, val kcalActive: Double, val whoopKcal: Double? = null)

/** Tiny SQLite store; [version] ticks on every write so the UI can re-query. */
class Store(context: Context) : SQLiteOpenHelper(context, "aarogya.db", null, 6) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE food(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, time INTEGER NOT NULL,
               meal TEXT NOT NULL, name TEXT NOT NULL, grams REAL, kcal REAL NOT NULL, photo TEXT, source TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE burn(id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, time INTEGER NOT NULL,
               name TEXT NOT NULL, kcal REAL NOT NULL)"""
        )
        db.execSQL("CREATE INDEX food_day ON food(day)")
        db.execSQL("CREATE INDEX burn_day ON burn(day)")
        createWhoopTables(db)
        addClassificationColumns(db)
        db.execSQL("ALTER TABLE food ADD COLUMN suggestions TEXT")
        createWeightTable(db)
        addWorkoutDetailColumns(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createWhoopTables(db)
        if (oldVersion < 3) addClassificationColumns(db)
        if (oldVersion < 4) db.execSQL("ALTER TABLE food ADD COLUMN suggestions TEXT")
        if (oldVersion < 5) createWeightTable(db)
        if (oldVersion < 6) addWorkoutDetailColumns(db)
    }

    private fun addWorkoutDetailColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE whoop_workout ADD COLUMN minutes INTEGER")
        db.execSQL("ALTER TABLE whoop_workout ADD COLUMN avg_hr INTEGER")
        db.execSQL("ALTER TABLE whoop_workout ADD COLUMN distance_m REAL")
    }

    /**
     * Calories and sessions per activity across all time, largest first. WHOOP sport names and
     * logged activity names are merged case-insensitively ("Walking" and "walking" are one row).
     */
    fun activityTotals(): List<ActivityStat> = readableDatabase.rawQuery(
        """SELECT MAX(name), COUNT(*), SUM(kcal), SUM(minutes) FROM (
               SELECT sport AS name, kcal, COALESCE(minutes, 0) AS minutes FROM whoop_workout
               UNION ALL SELECT name, kcal, 0 FROM burn
           ) GROUP BY LOWER(name) ORDER BY SUM(kcal) DESC""",
        null,
    ).use { c -> buildList { while (c.moveToNext()) add(ActivityStat(c.getString(0), c.getInt(1), c.getDouble(2), c.getInt(3))) } }

    private fun createWeightTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE weight(time INTEGER PRIMARY KEY, day TEXT NOT NULL, kg REAL NOT NULL, source TEXT NOT NULL)")
    }

    /** All readings, newest first. */
    fun weights(): List<WeightEntry> =
        readableDatabase.rawQuery("SELECT time, day, kg, source FROM weight ORDER BY time DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(WeightEntry(c.getLong(0), LocalDate.parse(c.getString(1)), c.getDouble(2), c.getString(3))) }
        }

    /** Upserts readings (re-syncing the same reading just overwrites it). */
    fun saveWeights(entries: List<WeightEntry>) {
        writableDatabase.run {
            beginTransaction()
            try {
                for (e in entries) insertWithOnConflict("weight", null, ContentValues().apply {
                    put("time", e.time); put("day", e.day.toString()); put("kg", e.kg); put("source", e.source)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        _version.value++
    }

    fun clearWeights() {
        writableDatabase.delete("weight", null, null)
        _version.value++
    }

    private fun addClassificationColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE food ADD COLUMN status TEXT NOT NULL DEFAULT 'DONE'")
        db.execSQL("ALTER TABLE food ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE food ADD COLUMN error TEXT")
    }

    private fun createWhoopTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE whoop_day(day TEXT PRIMARY KEY, kcal REAL NOT NULL, strain REAL)")
        db.execSQL(
            """CREATE TABLE whoop_workout(id TEXT PRIMARY KEY, day TEXT NOT NULL, start INTEGER NOT NULL,
               sport TEXT NOT NULL, kcal REAL NOT NULL, strain REAL)"""
        )
        db.execSQL("CREATE INDEX whoop_workout_day ON whoop_workout(day)")
    }

    fun whoopDay(day: LocalDate): WhoopDay? =
        readableDatabase.rawQuery("SELECT kcal, strain FROM whoop_day WHERE day = ?", arrayOf(day.toString())).use { c ->
            if (c.moveToFirst()) WhoopDay(day, c.getDouble(0), if (c.isNull(1)) null else c.getDouble(1)) else null
        }

    fun whoopWorkouts(day: LocalDate): List<WhoopWorkout> =
        readableDatabase.rawQuery(
            "SELECT id, start, sport, kcal, strain, minutes, avg_hr, distance_m FROM whoop_workout WHERE day = ? ORDER BY start", arrayOf(day.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    WhoopWorkout(
                        c.getString(0), day, c.getLong(1), c.getString(2), c.getDouble(3),
                        strain = if (c.isNull(4)) null else c.getDouble(4),
                        minutes = if (c.isNull(5)) null else c.getInt(5),
                        avgHeartRate = if (c.isNull(6)) null else c.getInt(6),
                        distanceMeters = if (c.isNull(7)) null else c.getDouble(7),
                    )
                )
            }
        }

    /** Replaces WHOOP data for the synced days (re-syncing a day overwrites it). */
    fun saveWhoop(days: List<WhoopDay>, workouts: List<WhoopWorkout>) {
        writableDatabase.run {
            beginTransaction()
            try {
                for (d in days) insertWithOnConflict("whoop_day", null, ContentValues().apply {
                    put("day", d.day.toString()); put("kcal", d.kcal); put("strain", d.strain)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                for (w in workouts) insertWithOnConflict("whoop_workout", null, ContentValues().apply {
                    put("id", w.id); put("day", w.day.toString()); put("start", w.start); put("sport", w.sport); put("kcal", w.kcal); put("strain", w.strain)
                    put("minutes", w.minutes); put("avg_hr", w.avgHeartRate); put("distance_m", w.distanceMeters)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        _version.value++
    }

    fun clearWhoop() {
        writableDatabase.delete("whoop_day", null, null)
        writableDatabase.delete("whoop_workout", null, null)
        _version.value++
    }

    fun foods(day: LocalDate): List<FoodEntry> = queryFoods("WHERE day = ? ORDER BY time", day.toString())

    /** Everything ever logged, newest first; [search] filters by name. */
    fun allFoods(search: String = ""): List<FoodEntry> =
        if (search.isBlank()) queryFoods("ORDER BY day DESC, time DESC")
        else queryFoods("WHERE name LIKE ? ORDER BY day DESC, time DESC", "%${search.trim()}%")

    fun pendingFoods(): List<FoodEntry> = queryFoods("WHERE status = 'PENDING' ORDER BY time")

    fun food(id: Long): FoodEntry? = queryFoods("WHERE id = ?", id.toString()).firstOrNull()

    private fun queryFoods(where: String, vararg args: String): List<FoodEntry> =
        readableDatabase.rawQuery(
            "SELECT id, day, time, meal, name, grams, kcal, photo, source, status, attempts, error, suggestions FROM food $where", args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    FoodEntry(
                        id = c.getLong(0), day = LocalDate.parse(c.getString(1)), time = c.getLong(2), meal = Meal.valueOf(c.getString(3)),
                        name = c.getString(4), grams = if (c.isNull(5)) null else c.getDouble(5), kcal = c.getDouble(6),
                        photo = c.getString(7), source = c.getString(8), status = FoodStatus.valueOf(c.getString(9)),
                        attempts = c.getInt(10), error = c.getString(11), suggestions = Suggestion.fromJson(c.getString(12)),
                    )
                )
            }
        }

    private fun values(e: FoodEntry) = ContentValues().apply {
        put("day", e.day.toString()); put("time", e.time); put("meal", e.meal.name); put("name", e.name)
        put("grams", e.grams); put("kcal", e.kcal); put("photo", e.photo); put("source", e.source)
        put("status", e.status.name); put("attempts", e.attempts); put("error", e.error)
        put("suggestions", e.suggestions.takeIf { it.isNotEmpty() }?.let(Suggestion::toJson))
    }

    fun updateFood(e: FoodEntry) {
        writableDatabase.update("food", values(e), "id = ?", arrayOf(e.id.toString()))
        _version.value++
    }

    /** Swaps a pending entry for the foods it was classified into, atomically. */
    fun replacePending(id: Long, items: List<FoodEntry>) = replace(id, items, onlyIfStatus = FoodStatus.PENDING)

    /** Swaps any entry (e.g. a failed one the user resolved) for [items], atomically. */
    fun replaceEntry(id: Long, items: List<FoodEntry>) = replace(id, items, onlyIfStatus = null)

    private fun replace(id: Long, items: List<FoodEntry>, onlyIfStatus: FoodStatus?) {
        writableDatabase.run {
            beginTransaction()
            try {
                // A user edit/delete may have resolved the entry meanwhile; then leave it alone.
                val deleted = if (onlyIfStatus != null) delete("food", "id = ? AND status = ?", arrayOf(id.toString(), onlyIfStatus.name))
                else delete("food", "id = ?", arrayOf(id.toString()))
                if (deleted == 0) return
                for (e in items) insert("food", null, values(e))
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        _version.value++
    }

    fun burns(day: LocalDate): List<BurnEntry> =
        readableDatabase.rawQuery("SELECT id, time, name, kcal FROM burn WHERE day = ? ORDER BY time", arrayOf(day.toString())).use { c ->
            buildList { while (c.moveToNext()) add(BurnEntry(c.getLong(0), day, c.getLong(1), c.getString(2), c.getDouble(3))) }
        }

    /** Per-day totals for [from]..[to] inclusive, with zero rows for empty days. */
    fun totals(from: LocalDate, to: LocalDate): List<DayTotals> {
        val inMap = sumByDay("food", from, to)
        val outMap = sumByDay("burn", from, to)
        val whoopMap = sumByDay("whoop_day", from, to)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { DayTotals(it, inMap[it.toString()] ?: 0.0, outMap[it.toString()] ?: 0.0, whoopMap[it.toString()]) }
            .toList()
    }

    private fun sumByDay(table: String, from: LocalDate, to: LocalDate): Map<String, Double> =
        readableDatabase.rawQuery("SELECT day, SUM(kcal) FROM $table WHERE day BETWEEN ? AND ? GROUP BY day", arrayOf(from.toString(), to.toString())).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1)) }
        }

    /** Earliest day with any food, activity or WHOOP data. */
    fun firstDay(): LocalDate? = readableDatabase.rawQuery(
        "SELECT MIN(day) FROM (SELECT day FROM food UNION ALL SELECT day FROM burn UNION ALL SELECT day FROM whoop_day)", null,
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null }

    /** All-time calories per meal (classified entries only). */
    fun mealTotals(): Map<Meal, Double> =
        readableDatabase.rawQuery("SELECT meal, SUM(kcal) FROM food WHERE status = 'DONE' GROUP BY meal", null).use { c ->
            buildMap { while (c.moveToNext()) put(Meal.valueOf(c.getString(0)), c.getDouble(1)) }
        }

    data class FoodStat(val name: String, val times: Int, val kcal: Double)

    /** Foods that contributed the most calories overall (names compared case-insensitively). */
    fun topFoods(limit: Int): List<FoodStat> = readableDatabase.rawQuery(
        "SELECT MAX(name), COUNT(*), SUM(kcal) FROM food WHERE status = 'DONE' GROUP BY LOWER(name) ORDER BY SUM(kcal) DESC LIMIT ?",
        arrayOf(limit.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(FoodStat(c.getString(0), c.getInt(1), c.getDouble(2))) } }

    fun addFoods(entries: List<FoodEntry>) {
        writableDatabase.run {
            beginTransaction()
            try {
                for (e in entries) insert("food", null, values(e))
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
        _version.value++
    }

    fun addBurn(e: BurnEntry) {
        writableDatabase.insert("burn", null, ContentValues().apply {
            put("day", e.day.toString()); put("time", e.time); put("name", e.name); put("kcal", e.kcal)
        })
        _version.value++
    }

    /**
     * Returns the photo path once no other entry uses it (several foods can share one meal photo),
     * so the caller can delete the file.
     */
    fun deleteFood(id: Long): String? {
        val photo = readableDatabase.rawQuery("SELECT photo FROM food WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
        writableDatabase.delete("food", "id = ?", arrayOf(id.toString()))
        _version.value++
        if (photo == null) return null
        val stillUsed = readableDatabase.rawQuery("SELECT 1 FROM food WHERE photo = ? LIMIT 1", arrayOf(photo)).use { it.moveToFirst() }
        return photo.takeUnless { stillUsed }
    }

    /** Deletes many entries at once; returns photo paths no remaining entry uses, for the caller to delete. */
    fun deleteFoods(ids: Collection<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val args = ids.map { it.toString() }.toTypedArray()
        val marks = ids.joinToString(",") { "?" }
        val photos = readableDatabase.rawQuery("SELECT DISTINCT photo FROM food WHERE id IN ($marks) AND photo IS NOT NULL", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        writableDatabase.delete("food", "id IN ($marks)", args)
        _version.value++
        return photos.filterNot { p -> readableDatabase.rawQuery("SELECT 1 FROM food WHERE photo = ? LIMIT 1", arrayOf(p)).use { it.moveToFirst() } }
    }

    fun deleteBurn(id: Long) {
        writableDatabase.delete("burn", "id = ?", arrayOf(id.toString()))
        _version.value++
    }
}

/** US Central time (CST in winter, CDT in summer). */
const val DEFAULT_TIME_ZONE = "America/Chicago"

/** User settings kept in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state

    data class Settings(
        val goal: Int,
        val restingBurn: Int,
        val model: String?,
        val useGemini: Boolean = true,
        /** Tracking time zone id; US Central by default, fixed until the user changes it. */
        val timeZone: String = DEFAULT_TIME_ZONE,
        val reminders: Map<Meal, com.example.aarogya.reminders.Reminder> = emptyMap(),
    )

    private fun read(): Settings {
        // First run pins the tracking zone to US Central; travelling later doesn't change it.
        // commit(), not apply(): this one-time write must survive even if the app is killed right after.
        if (!sp.contains("time_zone")) sp.edit().putString("time_zone", DEFAULT_TIME_ZONE).commit()
        return Settings(
            goal = sp.getInt("goal", 2000),
            restingBurn = sp.getInt("resting", 0),
            model = sp.getString("model", null),
            useGemini = sp.getBoolean("use_gemini", true),
            timeZone = sp.getString("time_zone", null) ?: DEFAULT_TIME_ZONE,
            reminders = reminders(),
        ).also { s -> runCatching { AppTime.zone = java.time.ZoneId.of(s.timeZone) } }
    }

    /** Per-meal reminder settings (off by default, at the default times). */
    fun reminders(): Map<Meal, com.example.aarogya.reminders.Reminder> = Meal.entries.associateWith { m ->
        com.example.aarogya.reminders.Reminder(
            enabled = sp.getBoolean("rem_${m.name}_on", false),
            minutes = sp.getInt("rem_${m.name}_min", com.example.aarogya.reminders.ReminderTimes.DEFAULTS.getValue(m)),
        )
    }

    fun setReminder(meal: Meal, reminder: com.example.aarogya.reminders.Reminder) {
        sp.edit().putBoolean("rem_${meal.name}_on", reminder.enabled).putInt("rem_${meal.name}_min", reminder.minutes).apply()
        _state.value = read()
    }

    fun update(
        goal: Int = state.value.goal,
        restingBurn: Int = state.value.restingBurn,
        model: String? = state.value.model,
        useGemini: Boolean = state.value.useGemini,
        timeZone: String = state.value.timeZone,
    ) {
        sp.edit().putInt("goal", goal).putInt("resting", restingBurn).putString("model", model).putBoolean("use_gemini", useGemini)
            .putString("time_zone", timeZone).apply()
        _state.value = read()
    }
}
