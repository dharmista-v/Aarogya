package com.example.aarogya.ui

import com.example.aarogya.data.AppTime
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aarogya.DayUi
import com.example.aarogya.R
import com.example.aarogya.data.BurnEntry
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.FoodStatus
import com.example.aarogya.data.Meal
import com.example.aarogya.data.WhoopWorkout
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TodayScreen(
    ui: DayUi,
    contentPadding: PaddingValues,
    onShiftDay: (Long) -> Unit,
    onAddFood: () -> Unit,
    onAddActivity: () -> Unit,
    onDeleteFood: (Long) -> Unit,
    onEditFood: (FoodEntry) -> Unit,
    onDeleteBurn: (Long) -> Unit,
    onUsePhoneZone: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        if (AppTime.phoneDiffers()) item(key = "travel") { TravelNotice(onUsePhoneZone) }
        item(key = "day") { DaySwitcher(ui.day, onShiftDay) }
        item(key = "summary") { SummaryCard(ui) }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onAddFood, Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Icon(painterResource(R.drawable.ic_meal), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add food")
                }
                FilledTonalButton(onAddActivity, Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Icon(painterResource(R.drawable.ic_fire), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add activity")
                }
            }
        }
        item(key = "analysis") { AnalysisCard(ui.analysis.insights) }

        val byMeal = ui.foods.groupBy { it.meal }
        for (meal in Meal.entries) {
            val entries = byMeal[meal] ?: continue
            item(key = "meal-${meal.name}") { SectionTitle(meal.label, "${entries.sumOf { it.kcal }.kcal()} kcal") }
            items(entries, key = { "f${it.id}" }) { FoodRow(it, onDeleteFood, onEditFood) }
        }
        if (ui.burns.isNotEmpty() || ui.whoopWorkouts.isNotEmpty()) {
            item(key = "activity") {
                SectionTitle("Activity", ui.burns.takeIf { it.isNotEmpty() }?.let { b -> "−${b.sumOf { it.kcal }.kcal()} kcal logged" })
            }
            items(ui.whoopWorkouts, key = { "w${it.id}" }) { WhoopWorkoutRow(it) }
            items(ui.burns, key = { "b${it.id}" }) { BurnRow(it, onDeleteBurn) }
        }
    }
}

@Composable
private fun DaySwitcher(day: LocalDate, onShift: (Long) -> Unit) {
    val today = AppTime.today()
    val label = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        IconButton({ onShift(1) }, enabled = day < today) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
    }
}

@Composable
private fun SummaryCard(ui: DayUi) {
    val a = ui.analysis
    val net = a.net.roundToInt()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CalorieRing(a.kcalIn, a.budget, Modifier.size(176.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val rem = a.remaining.roundToInt()
                    Text(abs(rem).kcal(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(if (rem >= 0) "kcal left" else "kcal over", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("In", a.kcalIn.kcal(), Palette.inColor, Modifier.weight(1f))
                Stat(
                    when { a.whoopKcal != null -> "Out · WHOOP"; a.restingBurn > 0 -> "Out"; else -> "Active" },
                    a.kcalOut.kcal(), Palette.outColor, Modifier.weight(1f),
                )
                // Without a base burn "Out" is only workouts, so don't call the difference a deficit/surplus.
                Stat(
                    when { !a.hasFullBurn -> "Net"; net <= 0 -> "Deficit"; else -> "Surplus" },
                    if (!a.hasFullBurn) net.kcal() else abs(net).kcal(),
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(insights: List<String>) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp).animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Daily analysis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            insights.forEach { line ->
                Row {
                    Text("•", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun FoodRow(e: FoodEntry, onDelete: (Long) -> Unit, onEdit: (FoodEntry) -> Unit) {
    EntryRow(
        leading = {
            if (e.photo != null) PhotoThumb(e.photo, 40.dp)
            else Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.primaryContainer) {}
                Icon(painterResource(R.drawable.ic_meal), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        title = e.name,
        subtitle = e.statusLabel() ?: e.grams?.let { "${it.roundToInt()} g" },
        subtitleColor = when (e.status) {
            FoodStatus.FAILED -> MaterialTheme.colorScheme.error
            FoodStatus.PENDING -> MaterialTheme.colorScheme.tertiary
            FoodStatus.DONE -> null
        },
        kcal = if (e.status == FoodStatus.DONE || e.kcal > 0) e.kcal.kcal() else "—",
        onDelete = { onDelete(e.id) },
        onClick = { onEdit(e) },
    )
}

@Composable
private fun BurnRow(e: BurnEntry, onDelete: (Long) -> Unit) {
    EntryRow(
        leading = {
            Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.tertiaryContainer) {}
                Icon(painterResource(R.drawable.ic_fire), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        },
        title = e.name,
        subtitle = null,
        kcal = "−${e.kcal.kcal()}",
        onDelete = { onDelete(e.id) },
    )
}

/** Read-only: tracked by WHOOP and already part of its daily total, so it isn't counted again. */
@Composable
private fun WhoopWorkoutRow(w: WhoopWorkout) {
    val time = Instant.ofEpochMilli(w.start).atZone(AppTime.zone).format(DateTimeFormatter.ofPattern("h:mm a"))
    EntryRow(
        leading = {
            Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.tertiaryContainer) {}
                Icon(painterResource(R.drawable.ic_fire), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        },
        title = w.sport,
        subtitle = listOfNotNull(
            "WHOOP · $time",
            w.minutes?.let { "$it min" },
            w.distanceMeters?.let { "%.1f km".format(it / 1000) },
            w.avgHeartRate?.let { "avg HR $it" },
            w.strain?.let { "strain ${"%.1f".format(it)}" },
        ).joinToString(" · "),
        kcal = w.kcal.kcal(),
        onDelete = null,
    )
}

@Composable
private fun EntryRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    kcal: String,
    onDelete: (() -> Unit)?,
    subtitleColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (confirming) {
                TextButton({ confirming = false }) { Text("Keep") }
                TextButton({ onDelete?.invoke() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            } else {
                Text("$kcal kcal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (onDelete != null) IconButton({ confirming = true }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Spacer(Modifier.width(36.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
