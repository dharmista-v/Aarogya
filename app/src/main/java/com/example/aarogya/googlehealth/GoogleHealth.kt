package com.example.aarogya.googlehealth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.example.aarogya.data.Store
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads body weight from the Google Health API. Sign-in uses Play services' AuthorizationClient, which
 * identifies the app by its package + signing certificate (the Android OAuth client in the Cloud project),
 * so no client secret ships in the app. Access tokens are short-lived and fetched again (silently,
 * once consent was given) for every sync.
 */
class GoogleHealth(private val context: Context, private val store: Store) {
    private val prefs = context.getSharedPreferences("google_health", Context.MODE_PRIVATE)
    private val syncLock = Mutex()
    private val client get() = Identity.getAuthorizationClient(context)

    data class State(
        val connected: Boolean,
        val lastSync: Long,
        val syncing: Boolean = false,
        val error: String? = null,
        /** Consent lapsed or was revoked; the user has to tap Connect again. */
        val needsSignIn: Boolean = false,
    )

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<State> = _state
    private fun readState() = State(connected = prefs.getBoolean("connected", false), lastSync = prefs.getLong("last_sync", 0))

    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /**
     * Starts sign-in. Returns a PendingIntent when Google needs to show its consent screen (launch it,
     * then pass the result to [completeConnect]); returns null when access was granted straight away.
     */
    suspend fun beginConnect(): PendingIntent? {
        val result = runCatching { client.authorize(request).await() }.getOrElse { e ->
            _state.value = readState().copy(error = "Google sign-in failed: ${e.message}")
            return null
        }
        if (result.hasResolution()) return result.pendingIntent
        onAuthorized(result)
        return null
    }

    suspend fun completeConnect(data: Intent?) {
        val result = runCatching { client.getAuthorizationResultFromIntent(data) }.getOrElse { e ->
            _state.value = readState().copy(error = "Google sign-in was cancelled or failed (${e.message}).")
            return
        }
        onAuthorized(result)
    }

    private suspend fun onAuthorized(result: AuthorizationResult) {
        prefs.edit { putBoolean("connected", true) }
        sync(result.accessToken ?: return)
    }

    /** App-open sync: no UI. If consent is needed again, flags [State.needsSignIn] instead. */
    suspend fun syncSilently() {
        if (!readState().connected) return
        val result = runCatching { client.authorize(request).await() }.getOrElse { e ->
            _state.value = readState().copy(error = e.message); return
        }
        if (result.hasResolution()) {
            _state.value = readState().copy(needsSignIn = true)
            return
        }
        sync(result.accessToken ?: return)
    }

    fun disconnect() {
        prefs.edit { clear() }
        store.clearWeights()
        _state.value = readState()
    }

    /** Pulls the last [days] days of weight readings (one request covers up to 10,000 readings). */
    private suspend fun sync(token: String, days: Long = 730) = withContext(Dispatchers.IO) {
        if (!syncLock.tryLock()) return@withContext
        _state.value = readState().copy(syncing = true)
        try {
            runCatching {
                val since = Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
                val filter = "weight.sample_time.physical_time >= \"$since\""
                var pageToken: String? = null
                var pages = 0
                do {
                    val url = buildString {
                        append("$BASE/users/me/dataTypes/weight/dataPoints?pageSize=1000&filter=").append(enc(filter))
                        pageToken?.let { append("&pageToken=").append(enc(it)) }
                    }
                    val page = WeightParser.parse(get(url, token))
                    store.saveWeights(page.items)
                    pageToken = page.nextPageToken
                } while (pageToken != null && ++pages < 20)
                prefs.edit { putLong("last_sync", System.currentTimeMillis()) }
                _state.value = readState()
            }.onFailure { e -> _state.value = readState().copy(error = e.message ?: "Sync failed") }
        } finally {
            syncLock.unlock()
        }
    }

    private fun get(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000; readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code in 200..299) return body
            throw IOException(errorMessage(code, body))
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE = "https://health.googleapis.com/v4"
        const val SCOPE = "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly"

        fun errorMessage(code: Int, body: String): String {
            val detail = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
            return when {
                body.contains("ACCOUNT_NOT_LINKED") -> "Open the Google Health app and sign in once, then sync again."
                code == 401 -> "Google sign-in expired — tap Connect again."
                code == 403 && detail.contains("not been used", true) ->
                    "Google Health API isn't enabled in your Cloud project — enable it, then sync again."
                code == 403 -> "Google denied access${if (detail.isNotEmpty()) ": $detail" else "."} Check the scope and test users in your Cloud project."
                code == 429 -> "Google Health rate limit reached — try again in a minute."
                else -> "Google Health error $code${if (detail.isNotEmpty()) ": $detail" else ""}"
            }
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
