package com.example.aarogya.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val QUICK = listOf("Walking", "Running", "Cycling", "Gym", "Yoga", "Swimming", "Sports", "Housework")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddActivitySheet(whoopConnected: Boolean, onSave: (String, Double) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    val kcalValue = kcal.toDoubleOrNull()?.takeIf { it > 0 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Add activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK.forEach { q -> FilterChip(selected = name == q, onClick = { name = q }, label = { Text(q) }) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(),
                label = { Text("Activity") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                kcal, { s -> kcal = s.filter { it.isDigit() }.take(5) }, Modifier.fillMaxWidth(),
                label = { Text("Calories burned") }, suffix = { Text("kcal") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        if (whoopConnected) "WHOOP already counts workouts it tracked — only add activities it missed."
                        else "Use the estimate from your watch, phone or gym machine.",
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(name.trim().ifEmpty { "Activity" }, kcalValue!!); onDismiss() },
                enabled = kcalValue != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (kcalValue != null) "Save · ${kcalValue.kcal()} kcal" else "Save") }
        }
    }
}
