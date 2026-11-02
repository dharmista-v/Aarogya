package com.example.aarogya.ui

import com.example.aarogya.data.AppTime
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aarogya.MainViewModel
import com.example.aarogya.R
import com.example.aarogya.data.WeightEntry
import com.example.aarogya.googlehealth.GoogleHealth
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Body weight from Google Health, in kg: latest, recent change, trend and every reading. */
@Composable
fun WeightScreen(
    vm: MainViewModel,
    state: GoogleHealth.State,
    weights: List<WeightEntry>,
    contentPadding: PaddingValues,
    onLock: () -> Unit,
    onChangePin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.completeGoogleHealthConnect(result.data)
    }
    val connect: () -> Unit = {
        scope.launch {
            vm.beginGoogleHealthConnect()?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "pin") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onChangePin) { Text("Change PIN") }
                TextButton(onLock) { Text("Lock") }
            }
        }
        if (!state.connected || state.needsSignIn) {
            item(key = "connect") { ConnectCard(state, connect) }
        }
        if (state.connected) item(key = "status") {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.syncing -> "Syncing with Google Health…"
                        state.lastSync == 0L -> "Not synced yet"
                        else -> "Google Health · synced " + DateUtils.getRelativeTimeSpanString(state.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    },
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else TextButton({ vm.syncGoogleHealth() }) { Text("Sync") }
                TextButton({ vm.disconnectGoogleHealth() }) { Text("Disconnect") }
            }
        }
        state.error?.let { err ->
            item(key = "error") { Text(err, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (state.connected && weights.isEmpty() && !state.syncing && state.error == null) {
            item(key = "none") {
                Text(
                    "No weight readings found in Google Health for the last two years. Log one in the Google Health app or a connected scale.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (weights.isNotEmpty()) {
            item(key = "summary") { SummaryCard(weights) }
            item(key = "chart") { TrendCard(weights) }
            weights.groupBy { it.day.withDayOfMonth(1) }.forEach { (month, list) ->
                item(key = "m$month") { SectionTitle(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), "${list.size} reading${if (list.size == 1) "" else "s"}") }
                items(list, key = { "w${it.time}" }) { WeightRow(it) }
            }
        }
    }
}

@Composable
private fun ConnectCard(state: GoogleHealth.State, onConnect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_scale), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                if (state.needsSignIn) "Reconnect Google Health" else "Track your weight",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (state.needsSignIn) "Google needs you to approve access again to keep syncing your weight."
                else "Connect Google Health to see your weight in kg and how it changes alongside your calorie balance. Read-only — Aarogya never changes your data.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onConnect, shape = RoundedCornerShape(12.dp)) { Text(if (state.needsSignIn) "Reconnect" else "Connect Google Health") }
        }
    }
}

private fun kg(v: Double) = "%.1f".format(v)

@Composable
private fun SummaryCard(weights: List<WeightEntry>) {
    val latest = weights.first()
    fun changeSince(days: Long): Double? {
        val cutoff = latest.day.minusDays(days)
        // The reading closest to (at or before) the cutoff, so the change spans the whole period.
        val past = weights.firstOrNull { !it.day.isAfter(cutoff) } ?: return null
        return latest.kg - past.kg
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(kg(latest.kg), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                Text(" kg", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Latest · " + latest.day.let { if (it == AppTime.today()) "today" else it.format(DateTimeFormatter.ofPattern("EEE, d MMM")) },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                ChangeStat("7 days", changeSince(7), Modifier.weight(1f))
                ChangeStat("30 days", changeSince(30), Modifier.weight(1f))
                ChangeStat("Lowest", null, Modifier.weight(1f), absolute = weights.minOf { it.kg })
            }
        }
    }
}

@Composable
private fun ChangeStat(label: String, change: Double?, modifier: Modifier, absolute: Double? = null) {
    val text = when {
        absolute != null -> "${kg(absolute)} kg"
        change == null -> "—"
        abs(change) < 0.05 -> "±0.0 kg"
        change > 0 -> "+${kg(change)} kg"
        else -> "−${kg(-change)} kg"
    }
    Stat(label, text, MaterialTheme.colorScheme.onSurface, modifier)
}

/** Line chart of the last 90 days of readings (oldest left), with the min/max labelled. */
@Composable
private fun TrendCard(weights: List<WeightEntry>) {
    val recent = weights.filter { !it.day.isBefore(AppTime.today().minusDays(90)) }.reversed()
    if (recent.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val minKg = recent.minOf { it.kg }
    val maxKg = recent.maxOf { it.kg }
    val span = (maxKg - minKg).coerceAtLeast(0.5)
    val t0 = recent.first().time.toDouble()
    val t1 = recent.last().time.toDouble().coerceAtLeast(t0 + 1)
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row {
                Text("Last 90 days", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${kg(minKg)}–${kg(maxKg)} kg", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 16.dp)) {
                fun point(e: WeightEntry) = Offset(
                    ((e.time - t0) / (t1 - t0)).toFloat() * size.width,
                    size.height - ((e.kg - (minKg - span * 0.1)) / (span * 1.2)).toFloat() * size.height,
                )
                val path = Path().apply {
                    recent.forEachIndexed { i, e -> point(e).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                }
                drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                recent.forEach { drawCircle(line, 3.dp.toPx(), point(it)) }
            }
        }
    }
}

@Composable
private fun WeightRow(e: WeightEntry) {
    val time = Instant.ofEpochMilli(e.time).atZone(AppTime.zone).format(DateTimeFormatter.ofPattern("h:mm a"))
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.day.format(DateTimeFormatter.ofPattern("EEE, d MMM")), style = MaterialTheme.typography.bodyLarge)
                Text("$time · ${e.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${kg(e.kg)} kg", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
