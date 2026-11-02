package com.example.aarogya.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.aarogya.data.AppTime
import java.time.ZoneId

/** Settings card: the tracking time zone, with change / use-phone's-zone actions. */
@Composable
fun TimeZoneSection(zoneId: String, onSelect: (String) -> Unit) {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(AppTime.zone)
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) ZonePickerDialog(current = zone, onPick = { onSelect(it.id); picking = false }) { picking = false }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(AppTime.label(zone), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(zone.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ picking = true }) { Text("Change") }
            }
            Text(
                "Days, meal times, WHOOP and weight are all tracked in this time zone, even when you travel. Changing it keeps logged meals on their day and re-syncs WHOOP and weight in the new zone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val phone = AppTime.phoneZone()
            if (phone.id != zone.id) {
                TextButton({ onSelect(phone.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Use phone's time zone (${AppTime.label(phone)})")
                }
            }
        }
    }
}

/** Searchable list of region time zones, sorted by UTC offset. */
@Composable
private fun ZonePickerDialog(current: ZoneId, onPick: (ZoneId) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { AppTime.allZones() }
    val shown = remember(query) {
        val q = query.trim().replace(' ', '_')
        if (q.isEmpty()) all else all.filter { it.id.contains(q, ignoreCase = true) || AppTime.offsetLabel(it).contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tracking time zone") },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("Search city or GMT+5:30") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().height(360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { z ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(z) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(AppTime.shortName(z), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (z.id == current.id) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (z.id == current.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(z.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(AppTime.offsetLabel(z), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Day-screen note shown while the phone is in another zone than the tracking zone (travel). */
@Composable
fun TravelNotice(onUsePhoneZone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tracking in ${AppTime.shortName(AppTime.zone)} time (${AppTime.offsetLabel(AppTime.zone)}) · phone is on ${AppTime.shortName(AppTime.phoneZone())} time",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onUsePhoneZone) { Text("Switch") }
        }
    }
}
