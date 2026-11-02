package com.example.aarogya.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.text.format.DateUtils
import com.example.aarogya.BuildConfig
import com.example.aarogya.MainViewModel
import com.example.aarogya.whoop.Whoop
import com.example.aarogya.data.ActivityLevel
import com.example.aarogya.data.Maintenance
import com.example.aarogya.llm.Downloadable
import com.example.aarogya.llm.FoodVisionModel
import com.example.aarogya.llm.ModelOption
import com.example.aarogya.llm.PlateVisionModel
import com.example.aarogya.llm.ModelStatus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val statuses by vm.modelStatus.collectAsStateWithLifecycle()
    var goal by rememberSaveable { mutableStateOf(settings.goal.toString()) }
    var burn by rememberSaveable { mutableStateOf(settings.restingBurn.takeIf { it > 0 }?.toString() ?: "") }
    var showCalc by rememberSaveable { mutableStateOf(false) }
    var showMaintenance by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        vm.watchModels(true)
        onDispose { vm.watchModels(false) }
    }

    fun save() = vm.updateSettings(goal.toIntOrNull()?.coerceIn(800, 6000) ?: settings.goal, burn.toIntOrNull()?.coerceIn(0, 6000) ?: 0)

    ModalBottomSheet(
        onDismissRequest = { save(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            SectionTitle("Daily targets")
            OutlinedTextField(
                goal, { s -> goal = s.filter { it.isDigit() }.take(4) }, Modifier.fillMaxWidth(),
                label = { Text("Calorie intake goal") }, suffix = { Text("kcal") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton({ showMaintenance = !showMaintenance }) { Text(if (showMaintenance) "Hide calculator" else "Calculate from body weight") }
            AnimatedVisibility(showMaintenance) { MaintenanceCalculator { goal = it.toString(); showMaintenance = false } }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                burn, { s -> burn = s.filter { it.isDigit() }.take(4) }, Modifier.fillMaxWidth(),
                label = { Text("Base burn (optional)") }, suffix = { Text("kcal/day") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("What your body burns on a normal day without workouts. Used for “Out” on days without WHOOP data.") },
            )
            TextButton({ showCalc = !showCalc }) { Text(if (showCalc) "Hide calculator" else "Estimate it for me") }
            AnimatedVisibility(showCalc) { BurnCalculator { burn = it.toString(); showCalc = false } }

            SectionTitle("Meal reminders")
            RemindersSection(settings.reminders, onChange = { meal, r -> vm.setReminder(meal, r) })

            SectionTitle("Time zone")
            TimeZoneSection(settings.timeZone, onSelect = { vm.setTimeZone(it) })

            SectionTitle("WHOOP")
            WhoopSection(vm)

            SectionTitle("Gemini (cloud)")
            GeminiSection(vm)

            SectionTitle("On-device AI")
            Text(
                "Optional. SmolLM2 runs entirely on your phone and helps understand meal descriptions the built-in food list doesn't recognise. Calories always come from the food list or your own input.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelOption.entries.forEach { option ->
                    ModelRow(
                        option = option,
                        blurb = option.blurb,
                        status = statuses[option],
                        selected = settings.model == option.id,
                        onSelect = { vm.selectModel(option) },
                        onDownload = { vm.downloadModel(option) },
                        onDelete = { vm.deleteModel(option) },
                    )
                }
            }

            SectionTitle("Photo recognition")
            Text(
                "Optional, both run on-device. The dish recognizer names around 2,000 dishes — including Indian (biryani, chole bhature, samosa, rajma…) and Mexican (tacos, enchiladas, tamales, pozole…). The plate analyzer describes mixed plates (e.g. rice, dal and roti) so you can add each item.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ModelRow(
                option = FoodVisionModel,
                blurb = "Google AIY food classifier",
                status = statuses[FoodVisionModel],
                selected = null,
                onSelect = {},
                onDownload = { vm.downloadModel(FoodVisionModel) },
                onDelete = { vm.deleteModel(FoodVisionModel) },
            )
            Spacer(Modifier.height(8.dp))
            ModelRow(
                option = PlateVisionModel,
                blurb = "SmolVLM 256M · describes everything on the plate",
                status = statuses[PlateVisionModel],
                selected = null,
                onSelect = {},
                onDownload = { vm.downloadModel(PlateVisionModel) },
                onDelete = { vm.deleteModel(PlateVisionModel) },
            )
        }
    }
}

/** Gemini photo analysis: identifies every food in a photo with per-item calories. Sends photos to Google. */
@Composable
private fun GeminiSection(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!vm.geminiConfigured) {
                Text("Not set up in this build", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "Create a key at aistudio.google.com, add GEMINI_API_KEY to local.properties and rebuild.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Analyze meal photos with Gemini", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "Finds every food in the photo with calories for each. ${BuildConfig.GEMINI_MODEL}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.useGemini, onCheckedChange = vm::setUseGemini)
                }
                Text(
                    "Photos are sent to Google for analysis. When off or offline, on-device recognition is used.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Connect/sync WHOOP. Its measured daily burn replaces the base-burn estimate. */
@Composable
private fun WhoopSection(vm: MainViewModel) {
    val s by vm.whoop.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                !s.configured -> {
                    Text("Not set up in this build", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Create an app at developer-dashboard.whoop.com with redirect URI ${Whoop.REDIRECT_URI} and scopes " +
                            "read:cycles, read:workout and offline. Then add WHOOP_CLIENT_ID and WHOOP_CLIENT_SECRET to local.properties and rebuild.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                !s.connected -> {
                    Text(
                        "Use WHOOP's measured calories as your daily burn instead of an estimate.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, vm.whoopAuthUrl())) },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Connect WHOOP") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connected", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                if (s.syncing) "Syncing…" else if (s.lastSync == 0L) "Not synced yet"
                                else "Synced " + DateUtils.getRelativeTimeSpanString(s.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else TextButton({ vm.syncWhoop() }) { Text("Sync now") }
                        TextButton({ vm.disconnectWhoop() }) { Text("Disconnect") }
                    }
                }
            }
            s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ModelRow(
    option: Downloadable,
    blurb: String,
    status: ModelStatus?,
    selected: Boolean?, // null = no radio button (not a choice between alternatives)
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (selected == null) 16.dp else 0.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) RadioButton(selected = selected, onClick = onSelect, enabled = status == ModelStatus.Ready)
            Column(Modifier.weight(1f)) {
                Text(option.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val sub = when (status) {
                    is ModelStatus.Downloading -> "Downloading… ${(status.progress * 100).roundToInt()}%"
                    is ModelStatus.Failed -> status.reason
                    else -> "$blurb · ${option.sizeMb} MB"
                }
                Text(
                    sub, style = MaterialTheme.typography.bodySmall,
                    color = if (status is ModelStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (status) {
                null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                ModelStatus.Ready -> TextButton(onDelete) { Text("Delete") }
                is ModelStatus.Downloading -> {
                    CircularProgressIndicator({ status.progress }, Modifier.size(20.dp), strokeWidth = 2.dp)
                    TextButton(onDelete) { Text("Cancel") }
                }
                is ModelStatus.Failed -> TextButton(onDownload) { Text("Retry") }
                ModelStatus.NotInstalled -> TextButton(onDownload) { Text("Download") }
            }
        }
    }
}

/** Maintenance intake from body weight: pounds × 14–18 kcal by activity level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaintenanceCalculator(onResult: (Int) -> Unit) {
    var weight by rememberSaveable { mutableStateOf("") }
    var inKg by rememberSaveable { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf(ActivityLevel.MODERATE) }
    val w = weight.toDoubleOrNull()?.takeIf { if (inKg) it in 25.0..300.0 else it in 55.0..660.0 }
    val result = w?.let { Maintenance.kcal(it, inKg, level) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    weight, { s -> weight = s.filter { it.isDigit() || it == '.' }.take(5) }, Modifier.weight(1f),
                    label = { Text("Body weight") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                SingleChoiceSegmentedButtonRow(Modifier.width(120.dp)) {
                    SegmentedButton(!inKg, { inKg = false }, SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("lb") }
                    SegmentedButton(inKg, { inKg = true }, SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("kg") }
                }
            }
            Text("How active are you?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityLevel.entries.forEach { l ->
                    FilterChip(selected = level == l, onClick = { level = l }, label = { Text("${l.label} ×${l.kcalPerLb}") })
                }
            }
            Text(
                if (result != null) "Maintenance ≈ ${result.kcal()} kcal/day (${level.kcalPerLb} kcal per lb). Eat below this to lose weight, above it to gain."
                else "Multiplies your weight in pounds by 14–18 kcal depending on activity (15 if moderately active).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton({ result?.let(onResult) }, enabled = result != null, modifier = Modifier.align(Alignment.End)) {
                Text(if (result != null) "Use ${result.kcal()} kcal as goal" else "Enter your weight")
            }
        }
    }
}

/** Mifflin–St Jeor BMR × 1.2 (sedentary) — a base burn that excludes logged workouts. */
@Composable
private fun BurnCalculator(onResult: (Int) -> Unit) {
    var male by rememberSaveable { mutableStateOf(true) }
    var age by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    val a = age.toIntOrNull()
    val h = height.toIntOrNull()
    val w = weight.toIntOrNull()
    val result = if (a != null && h != null && w != null && a in 10..100 && h in 100..250 && w in 25..300) {
        ((10 * w + 6.25 * h - 5 * a + if (male) 5 else -161) * 1.2).roundToInt()
    } else null

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(male, { male = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Male") }
                SegmentedButton(!male, { male = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Female") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallNumber(age, { age = it }, "Age", Modifier.weight(1f))
                SmallNumber(height, { height = it }, "Height cm", Modifier.weight(1f))
                SmallNumber(weight, { weight = it }, "Weight kg", Modifier.weight(1f))
            }
            TextButton({ result?.let(onResult) }, enabled = result != null, modifier = Modifier.align(Alignment.End)) {
                Text(if (result != null) "Use $result kcal/day" else "Fill in all three")
            }
        }
    }
}

@Composable
private fun SmallNumber(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value, { s -> onChange(s.filter { it.isDigit() }.take(3)) }, modifier,
        label = { Text(label, maxLines = 1) }, singleLine = true, shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
