package com.example.aarogya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.aarogya.classify.ClassifyWorker
import com.example.aarogya.classify.FoodClassifier
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.FoodStatus
import com.example.aarogya.data.Food
import com.example.aarogya.data.Meal
import com.example.aarogya.data.QtyUnit
import com.example.aarogya.data.Suggestion
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Edit, delete, or re-queue one logged food. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFoodSheet(
    entry: FoodEntry,
    onSave: (FoodEntry) -> Unit,
    onDelete: (FoodEntry) -> Unit,
    onRetry: (FoodEntry) -> Unit,
    onApply: (FoodEntry, List<Suggestion>) -> Unit,
    derive: suspend (String) -> FoodClassifier.Parsed,
    /** The table food this entry matches, if any, so its amount can be set in units ("2 tacos"). */
    servingFood: Food?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(entry.id) { mutableStateOf(entry.name) }
    var kcal by rememberSaveable(entry.id) { mutableStateOf(entry.kcal.roundToInt().takeIf { it > 0 }?.toString() ?: "") }
    var grams by rememberSaveable(entry.id) { mutableStateOf(entry.grams?.roundToInt()?.toString() ?: "") }
    var meal by rememberSaveable(entry.id) { mutableStateOf(entry.meal) }
    var unit by rememberSaveable(entry.id) { mutableStateOf(if (servingFood != null) QtyUnit.SERVING else QtyUnit.G) }
    var confirmDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    val kcalValue = kcal.toDoubleOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.photo?.let { PhotoThumb(it, 56.dp); Spacer(Modifier.width(12.dp)) }
                Column {
                    Text("Edit food", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusNote(entry)
            if (entry.status == FoodStatus.FAILED) ResolveFailed(entry, onApply = { onApply(entry, it); onDismiss() }, derive = derive)

            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Food") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(
                kcal, { s -> kcal = s.filter { it.isDigit() }.take(5) }, Modifier.fillMaxWidth(),
                label = { Text("Calories") }, suffix = { Text("kcal") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val servingGrams = servingFood?.servingGrams
            val units = QtyUnit.available(hasServing = servingFood != null, hasGrams = servingFood != null || (entry.grams ?: 0.0) > 0)
            if (units.isNotEmpty()) {
                // Calories per gram of this entry (keeps e.g. Gemini's estimate), else the food table's.
                val perGram = entry.grams?.takeIf { it > 0 && entry.kcal > 0 }?.let { entry.kcal / it } ?: servingFood?.let { it.kcalPer100g / 100 }
                val g = grams.toDoubleOrNull() ?: servingGrams ?: 0.0
                val amount = g / (unit.gramsPer(servingGrams) ?: 1.0)
                fun setAmount(a: Double) {
                    val newGrams = a * (unit.gramsPer(servingGrams) ?: return)
                    grams = newGrams.roundToInt().toString()
                    perGram?.let { kcal = (newGrams * it).roundToInt().toString() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Amount", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton({ setAmount((amount - unit.step).coerceAtLeast(unit.step)) }, Modifier.size(40.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    DecimalField(amount, unit) { setAmount(it) }
                    IconButton({ setAmount(amount + unit.step) }, Modifier.size(40.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    UnitPicker(unit, units, amount, servingFood?.servingLabel) { unit = it }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Meal.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = meal == m, onClick = { meal = m }, shape = SegmentedButtonDefaults.itemShape(i, Meal.entries.size), icon = {},
                        label = { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            Button(
                onClick = {
                    val newName = name.trim().ifEmpty { entry.name }
                    val newKcal = kcalValue ?: 0.0
                    // Changing what the food is or its calories makes it the user's classification.
                    val edited = newName != entry.name || newKcal.roundToInt() != entry.kcal.roundToInt()
                    onSave(
                        entry.copy(
                            name = newName, kcal = newKcal, grams = grams.toDoubleOrNull()?.takeIf { it > 0 }, meal = meal,
                            source = if (edited) "manual" else entry.source,
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Save") }

            if (entry.status == FoodStatus.FAILED) {
                OutlinedButton({ onRetry(entry); onDismiss() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Retry classification")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (confirmDelete) {
                    TextButton({ confirmDelete = false }) { Text("Keep") }
                    TextButton({ onDelete(entry); onDismiss() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** For entries Gemini gave up on: pick on-device suggestions, or describe the meal and parse it locally. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResolveFailed(entry: FoodEntry, onApply: (List<Suggestion>) -> Unit, derive: suspend (String) -> FoodClassifier.Parsed) {
    val scope = rememberCoroutineScope()
    var picked by remember(entry.id) { mutableStateOf(setOf<Int>()) }
    var description by rememberSaveable(entry.id) { mutableStateOf(if (entry.photo == null) entry.name else "") }
    var deriving by remember(entry.id) { mutableStateOf(false) }
    var derived by remember(entry.id) { mutableStateOf<FoodClassifier.Parsed?>(null) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.suggestions.isNotEmpty()) {
                Text("On-device suggestions — pick what it was", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.suggestions.forEachIndexed { i, s ->
                        FilterChip(
                            selected = i in picked,
                            onClick = { picked = if (i in picked) picked - i else picked + i },
                            label = { Text("${s.name} · ${s.kcal.kcal()}") },
                        )
                    }
                }
                if (picked.isNotEmpty()) {
                    val chosen = picked.sorted().map { entry.suggestions[it] }
                    Button({ onApply(chosen) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Use ${chosen.size} selected · ${chosen.sumOf { it.kcal }.kcal()} kcal")
                    }
                }
            }

            Text("Or describe it", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                description, { description = it; derived = null }, Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. 2 rotis, dal and a bowl of rice") }, shape = RoundedCornerShape(12.dp),
            )
            OutlinedButton(
                onClick = {
                    deriving = true
                    scope.launch { derived = runCatching { derive(description) }.getOrNull(); deriving = false }
                },
                enabled = description.isNotBlank() && !deriving,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) {
                if (deriving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Working it out on-device…")
                } else Text("Work it out on-device")
            }
            derived?.let { d ->
                if (d.foods.isEmpty()) {
                    Text("Couldn't recognise any foods in that — try simpler names, or enter calories above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    d.foods.forEach { s ->
                        Row {
                            Text(s.name + (s.grams?.let { " · ${it.roundToInt()} g" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${s.kcal.kcal()} kcal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (d.unknown.isNotEmpty()) {
                        Text(
                            "Not in the food list (add separately): ${d.unknown.joinToString()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Button({ onApply(d.foods) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Use these · ${d.foods.sumOf { it.kcal }.kcal()} kcal")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusNote(entry: FoodEntry) {
    val (text, error) = when (entry.status) {
        FoodStatus.DONE -> return
        FoodStatus.PENDING -> "Pending classification — attempt ${entry.attempts} of ${ClassifyWorker.MAX_ATTEMPTS} so far. " +
            "Enter calories yourself to skip the queue." to false
        FoodStatus.FAILED -> "Gemini couldn't classify this" + (entry.error?.let { " ($it)" } ?: "") +
            ". Pick a suggestion, describe it, enter calories yourself, or retry." to true
    }
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Short status line used in lists. */
fun FoodEntry.statusLabel(): String? = when (status) {
    FoodStatus.DONE -> null
    FoodStatus.PENDING -> if (attempts == 0) "Pending classification…" else "Pending classification · attempt ${attempts + 1}/${ClassifyWorker.MAX_ATTEMPTS}"
    FoodStatus.FAILED -> "Couldn't classify — tap to fix"
}
