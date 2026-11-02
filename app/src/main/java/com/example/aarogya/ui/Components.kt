package com.example.aarogya.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.DayTotals
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ring showing intake against the day's budget; turns tertiary when over. */
@Composable
fun CalorieRing(consumed: Double, budget: Double, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val target = if (budget <= 0) 0f else (consumed / budget).toFloat().coerceIn(0f, 1f)
    val progress by animateFloatAsState(target, tween(600), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val color = if (consumed > budget) Palette.outColor else Palette.inColor
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(color, 135f, 270f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Connection indicator pill: green when [ok] (connected and last sync fine), red otherwise. */
@Composable
fun StatusDot(label: String, ok: Boolean, onClick: () -> Unit) {
    val dot = if (ok) Color(0xFF2E9E5B) else Color(0xFFD93B3B)
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label ${if (ok) "connected" else "not connected or failing"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val thumbCache = LruCache<String, Bitmap>(24)

/**
 * Small rounded photo, decoded off the main thread at thumbnail size.
 * When [zoomable], tapping it opens the full photo in [PhotoViewer].
 */
@Composable
fun PhotoThumb(path: String, size: Dp, modifier: Modifier = Modifier, zoomable: Boolean = true) {
    var viewing by remember { mutableStateOf(false) }
    if (viewing) PhotoViewer(path) { viewing = false }
    val clickModifier = if (zoomable) Modifier.clickable(onClickLabel = "View photo") { viewing = true } else Modifier
    // produceState keeps its previous value when the key changes, so always resolve the new path.
    val bmp by produceState(thumbCache.get(path), path) {
        value = thumbCache.get(path) ?: withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, opts)?.also { thumbCache.put(path, it) }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), "Meal photo", modifier.size(size).clip(shape).then(clickModifier), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

/** Full-screen photo with pinch-to-zoom/pan; double-tap toggles 2.5× zoom. */
@Composable
fun PhotoViewer(path: String, onDismiss: () -> Unit) {
    val full by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            full?.let { b ->
                Image(
                    b.asImageBitmap(), "Meal photo",
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            })
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                    contentScale = ContentScale.Fit,
                )
            } ?: CircularProgressIndicator(color = Color.White)
            IconButton(onDismiss, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close photo", tint = Color.White)
            }
        }
    }
}

/** Paired in/out bars per day with a dashed goal line. */
@Composable
fun TrendChart(days: List<DayTotals>, restingBurn: Int, goal: Int, modifier: Modifier = Modifier) {
    val inC = Palette.inColor
    val outC = Palette.outColor
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = maxOf(goal.toDouble(), days.maxOfOrNull { maxOf(it.kcalIn, Analyzer.dayOut(it, restingBurn)) } ?: 0.0, 1.0) * 1.1
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val slot = size.width / days.size
            val bar = (slot * 0.3f).coerceAtMost(14.dp.toPx())
            val r = CornerRadius(bar / 2)
            val goalY = size.height * (1 - (goal / max).toFloat())
            drawLine(grid, Offset(0f, goalY), Offset(size.width, goalY), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            days.forEachIndexed { i, d ->
                val cx = slot * i + slot / 2
                if (!Analyzer.isComplete(d, restingBurn)) return@forEachIndexed // in or out is 0: nothing to compare
                val out = Analyzer.dayOut(d, restingBurn)
                val hIn = (d.kcalIn / max).toFloat() * size.height
                val hOut = (out / max).toFloat() * size.height
                if (hIn > 0) drawRoundRect(inC, Offset(cx - bar - 1.dp.toPx(), size.height - hIn), Size(bar, hIn), r)
                if (hOut > 0) drawRoundRect(outC, Offset(cx + 1.dp.toPx(), size.height - hOut), Size(bar, hOut), r)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            days.forEach {
                Text(
                    it.day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(inC, "In")
            Legend(outC, if (restingBurn > 0 || days.any { it.whoopKcal != null }) "Out (WHOOP or base + activity)" else "Out (activity)")
            Legend(grid, "Goal", dashed = true)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = if (dashed) 14.dp else 10.dp, height = if (dashed) 2.dp else 10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
