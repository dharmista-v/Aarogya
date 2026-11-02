package com.example.aarogya.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 hashing so the PIN itself is never stored. Pure JVM for unit tests. */
object PinHash {
    private const val ITERATIONS = 20_000

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    /** Constant-time comparison. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean = MessageDigest.isEqual(hash(pin, salt), expected)
}

/**
 * PIN that guards the Weight tab. Unlocking lasts until the app goes to the background
 * ([lock] is called then). Five wrong tries pause entry for [LOCKOUT_MS].
 */
class PinLock(context: Context) {
    private val prefs = context.getSharedPreferences("pin_lock", Context.MODE_PRIVATE)
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    private val _isSet = MutableStateFlow(prefs.contains("hash"))
    val isSet: StateFlow<Boolean> = _isSet

    fun set(pin: String) {
        val salt = PinHash.newSalt()
        val enc = Base64.getEncoder()
        prefs.edit {
            putString("salt", enc.encodeToString(salt))
            putString("hash", enc.encodeToString(PinHash.hash(pin, salt)))
            putInt("fails", 0)
            remove("locked_until")
        }
        _isSet.value = true
        _unlocked.value = true
    }

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val triesLeft: Int) : Result
        data class LockedOut(val secondsLeft: Int) : Result
    }

    fun unlock(pin: String): Result {
        lockedOutSeconds()?.let { return Result.LockedOut(it) }
        val dec = Base64.getDecoder()
        val salt = prefs.getString("salt", null)?.let(dec::decode) ?: return Result.Wrong(MAX_TRIES)
        val hash = prefs.getString("hash", null)?.let(dec::decode) ?: return Result.Wrong(MAX_TRIES)
        if (PinHash.matches(pin, salt, hash)) {
            prefs.edit { putInt("fails", 0); remove("locked_until") }
            _unlocked.value = true
            return Result.Ok
        }
        val fails = prefs.getInt("fails", 0) + 1
        if (fails >= MAX_TRIES) {
            prefs.edit { putInt("fails", 0); putLong("locked_until", System.currentTimeMillis() + LOCKOUT_MS) }
            return Result.LockedOut((LOCKOUT_MS / 1000).toInt())
        }
        prefs.edit { putInt("fails", fails) }
        return Result.Wrong(MAX_TRIES - fails)
    }

    fun lockedOutSeconds(): Int? {
        val until = prefs.getLong("locked_until", 0)
        val left = until - System.currentTimeMillis()
        return if (left > 0) ((left + 999) / 1000).toInt() else null
    }

    fun lock() { _unlocked.value = false }

    /** Forgot-PIN reset: removes the PIN entirely (the caller also clears the protected data). */
    fun reset() {
        prefs.edit { clear() }
        _isSet.value = false
        _unlocked.value = false
    }

    companion object {
        const val PIN_LENGTH = 4
        private const val MAX_TRIES = 5
        private const val LOCKOUT_MS = 30_000L
    }
}
