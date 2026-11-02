package com.example.aarogya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aarogya.data.PinLock
import kotlinx.coroutines.delay

/** Creating a PIN: enter it, then confirm it. [onCancel] is shown when changing an existing PIN. */
@Composable
fun CreatePinScreen(contentPadding: PaddingValues, onCreated: (String) -> Unit, onCancel: (() -> Unit)? = null) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    PinPad(
        contentPadding = contentPadding,
        title = if (first == null) "Create a PIN for Weight" else "Confirm your PIN",
        subtitle = if (first == null) "Your weight stays private behind a ${PinLock.PIN_LENGTH}-digit PIN." else "Enter the same ${PinLock.PIN_LENGTH} digits again.",
        error = error,
        resetKey = first,
        onComplete = { pin ->
            val f = first
            when {
                f == null -> { first = pin; error = null }
                f == pin -> onCreated(pin)
                else -> { first = null; error = "PINs didn't match — start again." }
            }
        },
        footer = { onCancel?.let { TextButton(it) { Text("Cancel") } } },
    )
}

/** Entering the PIN to open the Weight tab, with a forgot-PIN reset. */
@Composable
fun UnlockPinScreen(contentPadding: PaddingValues, unlock: (String) -> PinLock.Result, lockedOutSeconds: () -> Int?, onForgot: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var lockedFor by remember { mutableStateOf(lockedOutSeconds()) }
    var confirmForgot by remember { mutableStateOf(false) }
    LaunchedEffect(lockedFor) {
        if ((lockedFor ?: 0) > 0) { delay(1000); lockedFor = lockedOutSeconds() }
    }
    if (confirmForgot) {
        AlertDialog(
            onDismissRequest = { confirmForgot = false },
            title = { Text("Reset PIN?") },
            text = { Text("This removes the PIN, deletes the weight readings stored in Aarogya and disconnects Google Health. Reconnect with your Google account to get your weights back, then set a new PIN.") },
            confirmButton = { TextButton({ confirmForgot = false; onForgot() }) { Text("Reset", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmForgot = false }) { Text("Cancel") } },
        )
    }
    PinPad(
        contentPadding = contentPadding,
        title = "Weight is locked",
        subtitle = "Enter your PIN",
        error = lockedFor?.let { "Too many tries — wait $it s." } ?: error,
        enabled = lockedFor == null,
        resetKey = attempt,
        onComplete = { pin ->
            when (val r = unlock(pin)) {
                PinLock.Result.Ok -> error = null
                is PinLock.Result.Wrong -> { error = "Wrong PIN — ${r.triesLeft} tr${if (r.triesLeft == 1) "y" else "ies"} left."; attempt++ }
                is PinLock.Result.LockedOut -> { lockedFor = r.secondsLeft; attempt++ }
            }
        },
        footer = { TextButton({ confirmForgot = true }) { Text("Forgot PIN?") } },
    )
}

/** Four dots and a 3×4 number pad. Calls [onComplete] once [PinLock.PIN_LENGTH] digits are in. */
@Composable
private fun PinPad(
    contentPadding: PaddingValues,
    title: String,
    subtitle: String,
    error: String?,
    resetKey: Any?,
    onComplete: (String) -> Unit,
    enabled: Boolean = true,
    footer: @Composable () -> Unit = {},
) {
    var digits by remember(resetKey) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(
            Modifier.padding(vertical = 24.dp).semantics { contentDescription = "${digits.length} of ${PinLock.PIN_LENGTH} digits entered" },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(PinLock.PIN_LENGTH) { i ->
                val filled = i < digits.length
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Text(
            error ?: " ", Modifier.height(20.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier.size(68.dp).clip(CircleShape)
                            .background(if (key.isEmpty()) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(enabled = enabled && key.isNotEmpty(), onClickLabel = if (key == "⌫") "Delete" else key) {
                                if (key == "⌫") digits = digits.dropLast(1)
                                else if (digits.length < PinLock.PIN_LENGTH) {
                                    digits += key
                                    if (digits.length == PinLock.PIN_LENGTH) onComplete(digits)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotEmpty()) Text(key, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        footer()
    }
}
