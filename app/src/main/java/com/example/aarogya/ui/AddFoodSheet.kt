package com.example.aarogya.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aarogya.MainViewModel
import com.example.aarogya.GeminiState
import com.example.aarogya.PlateAnalysis
import com.example.aarogya.PlateItem
import com.example.aarogya.R
import com.example.aarogya.data.Meal
import com.example.aarogya.data.Portions
import com.example.aarogya.data.QtyUnit
import com.example.aarogya.llm.FoodVisionModel
import com.example.aarogya.llm.PlateVisionModel
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddFoodSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingCamera?.let(::File) ?: return@rememberLauncherForActivityResult
        if (ok) vm.onPhoto(Uri.fromFile(file)) { vm.discardFile(file) } else vm.discardFile(file)
        pendingCamera = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::onPhoto) }

    fun submitText() {
        if (text.isNotBlank()) { vm.understand(text); text = "" }
    }

    ModalBottomSheet(
        onDismissRequest = { vm.discardDraft(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 16.dp).animateContentSize(),
        ) {
            Text("Add food", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Meal.entries.forEachIndexed { i, meal ->
                    SegmentedButton(
                        selected = draft.meal == meal,
                        onClick = { vm.setMeal(meal) },
                        shape = SegmentedButtonDefaults.itemShape(i, Meal.entries.size),
                        icon = {},
                        label = { Text(meal.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                // Multi-line fields swallow the Done action, so treat Enter as submit.
                onValueChange = { if ('\n' in it) { text = it.replace("\n", " "); submitText() } else text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. 2 rotis and a bowl of dal") },
                label = { Text("What did you eat?") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitText() }),
                trailingIcon = {
                    Row {
                        IconButton({
                            val file = vm.newCameraFile()
                            pendingCamera = file.absolutePath
                            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                        }) { Icon(painterResource(R.drawable.ic_camera), "Take photo") }
                        IconButton({ gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(painterResource(R.drawable.ic_image), "Choose photo")
                        }
                    }
                },
            )

            // Live matches from the food list, plus "understand the whole sentence".
            AnimatedVisibility(text.isNotBlank()) {
                val matches = remember(text) { vm.foodDb.search(text, 6) }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { submitText() },
                        label = { Text(text.trim().let { if (it.length <= 24) "Add “$it”" else "Add this meal" }) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                    matches.forEach { f ->
                        SuggestionChip(
                            onClick = { vm.addFood(f); text = "" },
                            label = { Text("${f.name.replaceFirstChar { it.uppercase() }} · ${f.kcalPerServing.kcal()}") },
                        )
                    }
                }
            }

            if (draft.thinking) {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Working out calories… you can save now; anything unfinished is classified in the background.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            draft.message?.let {
                Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            draft.photo?.let { path ->
                val gemini = draft.gemini
                if (gemini != null && gemini.error == null) {
                    GeminiBlock(path, gemini)
                } else {
                    gemini?.error?.let {
                        Text(
                            "$it Using on-device recognition instead.", Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                    PhotoBlock(path, draft.labels, draft.dishModelMissing, vm)
                    draft.plate?.let { PlateBlock(it, vm) }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (draft.items.isEmpty()) {
                Text(
                    "Type a food or describe your meal, or snap a photo. Items appear here for you to adjust before saving.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.items.forEach { item -> key(item.key) { PlateRow(item, vm) } }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.saveDraft(); onDismiss() },
                enabled = draft.canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    when {
                        draft.items.isEmpty() && draft.photo != null -> "Save · classify in background"
                        draft.pendingCount > 0 -> "Save · ${draft.total.kcal()} kcal + ${draft.pendingCount} to classify"
                        draft.items.isEmpty() -> "Save"
                        else -> "Save · ${draft.total.kcal()} kcal"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoBlock(path: String, labels: List<String>?, dishModelMissing: Boolean, vm: MainViewModel) {
    var requested by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
        PhotoThumb(path, 72.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            when {
                labels == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Looking at your photo…", style = MaterialTheme.typography.bodyMedium)
                }
                labels.isEmpty() -> Text(
                    if (dishModelMissing) "Couldn't recognise the dish. Get the dish recognizer below, or type what it is above."
                    else "Couldn't tell what this is — type it above and the photo will be attached.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text("Looks like — tap to add:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.take(6).forEach { label ->
                            val food = remember(label) { vm.foodDb.match(label) }
                            SuggestionChip(
                                onClick = { if (food != null) vm.addFood(food, "photo") else vm.addCustom(label, "photo") },
                                label = { Text(if (food != null) "$label · ${food.kcalPerServing.kcal()}" else label) },
                            )
                        }
                    }
                }
            }
            if (dishModelMissing && labels != null) {
                if (requested) {
                    Text(
                        "Downloading the dish recognizer — add your next photo once it's done (see Settings).",
                        Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton({ vm.downloadModel(FoodVisionModel); requested = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Recognise dishes like biryani or tacos (${FoodVisionModel.sizeMb} MB)", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiBlock(path: String, state: GeminiState) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoThumb(path, 72.dp)
        Spacer(Modifier.width(12.dp))
        if (state.running) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Gemini is analyzing your photo…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                when (state.found) {
                    0 -> "Gemini didn't find any food. Type what you ate above."
                    1 -> "Gemini found 1 item — check the calories below."
                    else -> "Gemini found ${state.found} items — check the calories below."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** SmolVLM's take on the whole plate: a sentence plus tap-to-add chips for foods it named. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlateBlock(plate: PlateAnalysis, vm: MainViewModel) {
    var requested by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
        when {
            plate.modelMissing -> if (requested) {
                Text(
                    "Downloading the plate analyzer — it will run on your next photo (see Settings).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton({ vm.downloadModel(PlateVisionModel); requested = true }, contentPadding = PaddingValues(0.dp)) {
                    Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Analyze whole plates with on-device AI (${PlateVisionModel.sizeMb} MB)", style = MaterialTheme.typography.labelMedium)
                }
            }
            plate.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("AI is looking at your plate…", style = MaterialTheme.typography.bodyMedium)
            }
            plate.description.isNotBlank() -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_sparkle), null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "AI sees: ${plate.description.trimEnd('.')}",
                        style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    )
                }
                if (plate.foods.isNotEmpty()) {
                    FlowRow(Modifier.padding(start = 24.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        plate.foods.forEach { f ->
                            SuggestionChip(
                                onClick = { vm.addFood(f, "ai") },
                                label = { Text("${f.name.replaceFirstChar { it.uppercase() }} · ${f.kcalPerServing.kcal()}") },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlateRow(item: PlateItem, vm: MainViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = if (item.canCount) 2.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        if (item.source == "ai" || item.source == "gemini") {
                            Spacer(Modifier.width(6.dp))
                            Icon(painterResource(R.drawable.ic_sparkle), "Identified by AI", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    val food = item.food
                    Text(
                        when {
                            food != null && item.qtyUnit != QtyUnit.SERVING -> "= ${Portions.label(item.quantity, food.servingLabel)} · ${food.kcalPerServing.kcal()} kcal per ${food.servingLabel}"
                            food != null -> "~${item.grams.roundToInt()} g · ${food.kcalPerServing.kcal()} kcal per ${food.servingLabel}"
                            item.canCount -> listOfNotNull("Gemini estimate", item.grams.takeIf { it > 0 }?.let { "~${it.roundToInt()} g" }).joinToString(" · ")
                            else -> "Enter calories, or save and it'll be classified"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (food == null && !item.canCount) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.food != null) {
                    Text("${item.kcal.kcal()} kcal", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                } else {
                    NumberField(item.customKcal?.roundToInt(), "kcal") { k -> vm.updateItem(item.key) { it.withKcal(k.toDouble()) } }
                }
                IconButton({ vm.removeItem(item.key) }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.canCount) QuantityControls(item, vm)
        }
    }
}

/** − amount + with a unit picker: the food's own unit ("1.5 tacos"), weight (g, oz…) or volume (ml, cup…). */
@Composable
private fun QuantityControls(item: PlateItem, vm: MainViewModel) {
    val u = item.qtyUnit
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            { vm.updateItem(item.key) { it.withAmount((it.amount - u.step).coerceAtLeast(u.step)) } },
            Modifier.size(36.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        DecimalField(item.amount, u) { a -> vm.updateItem(item.key) { it.withAmount(a) } }
        IconButton({ vm.updateItem(item.key) { it.withAmount(it.amount + u.step) } }, Modifier.size(36.dp)) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
        UnitPicker(u, item.units, item.amount, item.unit) { nu -> vm.updateItem(item.key) { it.copy(qtyUnit = nu) } }
    }
}

/** Unit button with a dropdown; switching unit keeps the same amount of food, just shown differently. */
@Composable
fun UnitPicker(current: QtyUnit, units: List<QtyUnit>, amount: Double, servingLabel: String?, onPick: (QtyUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ if (units.size > 1) open = true }, enabled = units.isNotEmpty()) {
            Text(current.name(amount, servingLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (units.size > 1) Icon(Icons.Filled.ArrowDropDown, "Change unit", Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            units.forEach { unit ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (unit == QtyUnit.SERVING) "${servingLabel ?: "serving"} (each)" else unit.label,
                            fontWeight = if (unit == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onPick(unit); open = false },
                )
            }
        }
    }
}

/** Compact decimal input for counts like 1, 1.5, 2. */
@Composable
fun DecimalField(value: Double, unit: QtyUnit = QtyUnit.SERVING, onValue: (Double) -> Unit) {
    val shown = QtyUnit.format(value, unit)
    var text by remember(shown) { mutableStateOf(shown) }
    Box(
        Modifier.width(56.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { s ->
                // Digits and at most one decimal point.
                val t = s.filter { it.isDigit() || it == '.' }
                val dot = t.indexOf('.')
                val cleaned = (if (dot < 0) t else t.substring(0, dot + 1) + t.substring(dot + 1).replace(".", "")).take(5)
                text = cleaned
                cleaned.toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue)
            },
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Compact integer input with a unit suffix. */
@Composable
fun NumberField(value: Int?, unit: String, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Box(
        Modifier.width(76.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { s ->
                val digits = s.filter { it.isDigit() }.take(5)
                text = digits
                digits.toIntOrNull()?.takeIf { it > 0 }?.let(onValue)
            },
            singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (text.isEmpty()) Text("0", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    }
                    Text(" $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}
