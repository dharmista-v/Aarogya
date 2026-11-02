package com.example.aarogya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aarogya.OverallUi
import com.example.aarogya.TrendsUi
import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.AppTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

enum class TrendsMode(val label: String, val description: String) {
    CURRENT("This week", "Last 7 days and recent daily balance"),
    OVERALL("Overall", "All-time trends, weekly patterns and top foods"),
}

/** Dropdown at the top of Trends to switch between the current week and the all-time analysis. */
@Composable
private fun ModePicker(mode: TrendsMode, onChange: (TrendsMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(bottom = 12.dp)) {
        OutlinedButton({ open = true }, shape = RoundedCornerShape(12.dp)) {
            Text(mode.label, style = MaterialTheme.typography.titleSmall)
            Icon(Icons.Filled.ArrowDropDown, "Choose view")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrendsMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.label, fontWeight = if (m == mode) FontWeight.SemiBold else FontWeight.Normal)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onChange(m); open = false },
                )
            }
        }
    }
}

@Composable
fun TrendsScreen(ui: TrendsUi, overall: OverallUi?, contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("EEE, d MMM")
    var mode by rememberSaveable { mutableStateOf(TrendsMode.CURRENT) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item(key = "mode") { ModePicker(mode) { mode = it } }
        if (mode == TrendsMode.OVERALL) {
            overall?.let { overallItems(it) }
            return@LazyColumn
        }
        item(key = "chart") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(20.dp)) {
                    Text("Last 7 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TrendChart(ui.days.takeLast(7), ui.restingBurn, ui.goal, Modifier.padding(top = 16.dp))
                }
            }
        }
        item(key = "week") {
            val w = ui.week
            Card(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (w == null) {
                        Text("Log a few days of meals to see your weekly balance.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            Stat("Avg in", w.avgIn.kcal(), Palette.inColor, Modifier.weight(1f))
                            Stat("Avg out", w.avgOut.kcal(), Palette.outColor, Modifier.weight(1f))
                            Stat(if (w.avgNet <= 0) "Avg deficit" else "Avg surplus", abs(w.avgNet).kcal(), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        }
                        val grams = (abs(w.projectedKgPerWeek) * 1000).roundToInt()
                        val line = when {
                            ui.restingBurn == 0 && ui.days.none { it.whoopKcal != null } -> "Connect WHOOP or set your base burn in Settings for a weight projection."
                            grams < 50 -> "At this pace your weight should stay about the same."
                            w.projectedKgPerWeek < 0 -> "At this pace: about −$grams g per week."
                            else -> "At this pace: about +$grams g per week."
                        }
                        Text(
                            "$line Based on ${w.loggedDays} complete day${if (w.loggedDays == 1) "" else "s"} (both in and out logged).",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item(key = "hdr") { SectionTitle("Daily balance", "in · out · net") }
        items(ui.days.reversed(), key = { it.day.toString() }) { d ->
            val empty = !Analyzer.isComplete(d, ui.restingBurn)
            val out = if (empty) 0.0 else Analyzer.dayOut(d, ui.restingBurn)
            val net = (d.kcalIn - out).roundToInt()
            Column(Modifier.clickable { onOpenDay(d.day) }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (d.day == AppTime.today()) "Today" else d.day.format(fmt), Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                    if (empty) {
                        Text("—", Modifier.weight(2f), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(d.kcalIn.kcal(), Modifier.weight(0.7f), textAlign = TextAlign.End, color = Palette.inColor, style = MaterialTheme.typography.bodyMedium)
                        Text(out.kcal(), Modifier.weight(0.7f), textAlign = TextAlign.End, color = Palette.outColor, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            (if (net > 0) "+" else "") + net.kcal(), Modifier.weight(0.7f), textAlign = TextAlign.End,
                            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}
