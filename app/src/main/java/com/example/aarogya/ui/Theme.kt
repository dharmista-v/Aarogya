package com.example.aarogya.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.text.NumberFormat
import kotlin.math.roundToInt

private val Light = lightColorScheme(
    primary = Color(0xFF2E7D5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBDB), onPrimaryContainer = Color(0xFF0B3B26),
    secondary = Color(0xFF52635A), tertiary = Color(0xFFB4582A),
    tertiaryContainer = Color(0xFFFFDBCB), onTertiaryContainer = Color(0xFF3B1300),
    background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7),
    surfaceContainer = Color(0xFFEDF1EC), surfaceContainerHigh = Color(0xFFE6ECE6),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FD6B0), onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF115236), onPrimaryContainer = Color(0xFFCDEBDB),
    secondary = Color(0xFFB7CCC0), tertiary = Color(0xFFFFB693),
    tertiaryContainer = Color(0xFF5B2A0E), onTertiaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF111412), surface = Color(0xFF111412),
    surfaceContainer = Color(0xFF1D211F), surfaceContainerHigh = Color(0xFF272B29),
)

@Composable
fun AarogyaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Calories in = primary, calories out = tertiary, used consistently across screens. */
object Palette {
    val inColor: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val outColor: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
}

private val fmt = NumberFormat.getIntegerInstance()
fun Double.kcal(): String = fmt.format(roundToInt())
fun Int.kcal(): String = fmt.format(this)
fun Double.trim1(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
