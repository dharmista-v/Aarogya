package com.example.aarogya.whoop

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.example.aarogya.BuildConfig
import com.example.aarogya.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * WHOOP connection: OAuth 2.0 (authorization code + refresh tokens) and syncing daily burn.
 * Client credentials come from local.properties (WHOOP_CLIENT_ID / WHOOP_CLIENT_SECRET) via BuildConfig.
 */
class Whoop(context: Context, private val store: Store) {
    private val prefs = context.getSharedPreferences("whoop", Context.MODE_PRIVATE)
    private val tokenLock = Mutex()
    private val syncLock = Mutex()

    data class State(val configured: Boolean, val connected: Boolean, val lastSync: Long, val syncing: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state

    private fun readState() = State(
        configured = BuildConfig.WHOOP_CLIENT_ID.isNotEmpty() && BuildConfig.WHOOP_CLIENT_SECRET.isNotEmpty(),
        connected = prefs.getString("refresh", null) != null,
        lastSync = prefs.getLong("last_sync", 0),
    )

    /** Browser URL for the WHOOP consent screen. */
    fun authUrl(): Uri {
        val state = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit { putString("pending_state", state) }
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", BuildConfig.WHOOP_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()
    }

    fun isCallback(uri: Uri?): Boolean = uri?.toString()?.startsWith(REDIRECT_URI) == true

    /** Handles aarogya://whoop-callback?code=…&state=… and stores the tokens. */
    suspend fun completeLogin(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            uri.getQueryParameter("error")?.let { throw IOException(uri.getQueryParameter("error_description") ?: it) }
            val expected = prefs.getString("pending_state", null)
            check(expected != null && uri.getQueryParameter("state") == expected) { "Login expired — please try connecting again." }
            val code = requireNotNull(uri.getQueryParameter("code")) { "WHOOP didn't return a login code." }
            saveTokens(
                post(
                    TOKEN_URL,
                    "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT_URI,
                    "client_id" to BuildConfig.WHOOP_CLIENT_ID, "client_secret" to BuildConfig.WHOOP_CLIENT_SECRET,
                )
            )
            prefs.edit { remove("pending_state") }
            _state.value = readState()
        }.onFailure { e -> _state.value = readState().copy(error = e.message) }
    }

    fun disconnect() {
        prefs.edit { clear() }
        store.clearWhoop()
        _state.value = readState()
    }

    /** Pulls the last [days] days of cycles and workouts. Safe to call often; days are overwritten. */
    suspend fun sync(days: Long = 14): Result<Unit> = withContext(Dispatchers.IO) {
        if (!readState().connected) return@withContext Result.success(Unit)
        // Overlapping triggers (app open + manual "Sync now") share the run already in progress.
        if (!syncLock.tryLock()) return@withContext Result.success(Unit)
        _state.value = readState().copy(syncing = true)
        try {
            runCatching {
                val start = Instant.now().minus(days, ChronoUnit.DAYS).toString()
                val cycles = paged("/v2/cycle", start) { WhoopParser.cycles(it).let { p -> p.items to p.nextToken } }
                val workouts = paged("/v2/activity/workout", start) { WhoopParser.workouts(it).let { p -> p.items to p.nextToken } }
                store.saveWhoop(WhoopParser.mergeByDay(cycles), workouts)
                prefs.edit { putLong("last_sync", System.currentTimeMillis()) }
                _state.value = readState()
            }.onFailure { e -> _state.value = readState().copy(error = e.message ?: "Sync failed") }
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun <T> paged(path: String, start: String, parse: (String) -> Pair<List<T>, String?>): List<T> {
        val all = ArrayList<T>()
        var next: String? = null
        do {
            val query = buildString {
                append("?limit=25&start=").append(enc(start))
                next?.let { append("&nextToken=").append(enc(it)) }
            }
            val (items, token) = parse(get(path + query))
            all += items
            next = token
        } while (next != null && all.size < 500)
        return all
    }

    private suspend fun get(pathAndQuery: String): String {
        repeat(2) { attempt ->
            val token = accessToken(forceRefresh = attempt > 0)
            val conn = (URL(API_BASE + pathAndQuery).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 15_000; readTimeout = 20_000
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> return conn.inputStream.bufferedReader().readText()
                    401 -> Unit // token revoked or expired early: refresh once and retry
                    429 -> throw IOException("WHOOP rate limit reached — try again in a minute.")
                    else -> throw IOException("WHOOP returned $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("WHOOP rejected the login — please reconnect.")
    }

    /** WHOOP rotates refresh tokens and a second concurrent refresh fails, so refreshes are serialized. */
    private suspend fun accessToken(forceRefresh: Boolean): String = tokenLock.withLock {
        val access = prefs.getString("access", null)
        if (!forceRefresh && access != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0) - 60_000) return access
        val refresh = prefs.getString("refresh", null) ?: throw IOException("Not connected to WHOOP.")
        saveTokens(
            post(
                TOKEN_URL,
                "grant_type" to "refresh_token", "refresh_token" to refresh, "scope" to "offline",
                "client_id" to BuildConfig.WHOOP_CLIENT_ID, "client_secret" to BuildConfig.WHOOP_CLIENT_SECRET,
            )
        )
        prefs.getString("access", null)!!
    }

    private fun saveTokens(json: JSONObject) {
        prefs.edit {
            putString("access", json.getString("access_token"))
            json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { putString("refresh", it) }
            putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
        }
    }

    private fun post(url: String, vararg form: Pair<String, String>): JSONObject {
        val body = form.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000; readTimeout = 20_000
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                if (code == 400 || code == 401) prefs.edit { remove("access"); remove("refresh") } // refresh token no longer valid
                throw IOException("WHOOP login failed ($code)")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val AUTH_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
        const val TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token"
        const val API_BASE = "https://api.prod.whoop.com/developer"
        const val REDIRECT_URI = "aarogya://whoop-callback"
        const val SCOPES = "offline read:cycles read:workout"
    }
}
