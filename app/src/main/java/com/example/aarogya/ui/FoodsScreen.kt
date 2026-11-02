package com.example.aarogya.ui

import com.example.aarogya.data.AppTime
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aarogya.R
import com.example.aarogya.data.ClassifiedBy
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.FoodStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Every food ever logged, newest first, grouped by day. Tap to edit; long-press to start selecting
 * several for bulk delete.
 */
@Composable
fun FoodsScreen(
    foods: List<FoodEntry>,
    search: String,
    onSearch: (String) -> Unit,
    contentPadding: PaddingValues,
    onOpen: (FoodEntry) -> Unit,
    onDeleteMany: (Set<Long>) -> Unit,
) {
    val fmt = DateTimeFormatter.ofPattern("EEE, d MMM")
    val today = AppTime.today()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Drop ids that no longer exist (deleted, or reclassified into new entries).
    LaunchedEffect(foods) { selected = selected intersect foods.map { it.id }.toSet() }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} food${if (selected.size == 1) "" else "s"}?") },
            text = { Text("They'll be removed from your log and totals. This can't be undone.") },
            confirmButton = {
                TextButton({ onDeleteMany(selected); selected = emptySet(); confirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item(key = "search") {
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ selected = emptySet() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                        Text("${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton({ selected = if (selected.size == foods.size) emptySet() else foods.map { it.id }.toSet() }) {
                            Text(if (selected.size == foods.size) "Clear" else "Select all")
                        }
                        TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else {
                OutlinedTextField(
                    search, onSearch, Modifier.fillMaxWidth(),
                    placeholder = { Text("Search your foods") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (search.isNotEmpty()) IconButton({ onSearch("") }) { Icon(Icons.Filled.Clear, "Clear search") } },
                )
            }
        }
        val pending = foods.count { it.status == FoodStatus.PENDING }
        val failed = foods.count { it.status == FoodStatus.FAILED }
        if (pending + failed > 0) item(key = "queue") {
            Text(
                listOfNotNull(
                    pending.takeIf { it > 0 }?.let { "$it pending classification" },
                    failed.takeIf { it > 0 }?.let { "$it need your attention" },
                ).joinToString(" · "),
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge,
                color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (foods.isEmpty()) item(key = "empty") {
            Text(
                if (search.isBlank()) "Nothing logged yet." else "No foods match “$search”.",
                Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        foods.groupBy { it.day }.forEach { (day, entries) ->
            item(key = "d$day") {
                SectionTitle(
                    when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(fmt) },
                    "${entries.sumOf { it.kcal }.kcal()} kcal",
                )
            }
            items(entries, key = { "f${it.id}" }) { e ->
                FoodListRow(
                    e = e,
                    selecting = selecting,
                    selected = e.id in selected,
                    onClick = { if (selecting) selected = if (e.id in selected) selected - e.id else selected + e.id else onOpen(e) },
                    onLongClick = { selected = selected + e.id },
                )
            }
        }
    }
}

/** Small pill showing who classified the food: Gemini, an on-device LLM, or the user. */
@Composable
private fun SourceTag(by: ClassifiedBy) {
    val (bg, fg) = when (by) {
        ClassifiedBy.GEMINI -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ClassifiedBy.LLM -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ClassifiedBy.MANUAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        by.label,
        Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = fg,
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FoodListRow(e: FoodEntry, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() })
            // While selecting, tapping the photo selects instead of opening the viewer.
            if (e.photo != null) PhotoThumb(e.photo, 40.dp, zoomable = !selecting)
            else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_meal), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.status == FoodStatus.DONE) {
                        Spacer(Modifier.width(8.dp))
                        SourceTag(e.classifiedBy)
                    }
                }
                val status = e.statusLabel()
                Text(
                    status ?: listOfNotNull(e.meal.label, e.grams?.let { "${it.roundToInt()} g" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (e.status) {
                        FoodStatus.FAILED -> MaterialTheme.colorScheme.error
                        FoodStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                        FoodStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                if (e.status == FoodStatus.DONE || e.kcal > 0) "${e.kcal.kcal()} kcal" else "—",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
