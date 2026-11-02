package com.example.aarogya.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.aarogya.data.AppTime
import com.example.aarogya.data.Meal
import com.example.aarogya.reminders.Reminder
import com.example.aarogya.reminders.ReminderTimes

/** One row per meal: on/off and its time (in the tracking time zone). */
@Composable
fun RemindersSection(reminders: Map<Meal, Reminder>, onChange: (Meal, Reminder) -> Unit) {
    val context = LocalContext.current
    fun hasPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var denied by remember { mutableStateOf(false) }
    var pendingEnable by remember { mutableStateOf<Meal?>(null) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        pendingEnable?.let { m -> if (granted) onChange(m, reminders.getValue(m).copy(enabled = true)) }
        pendingEnable = null
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Meal.entries.forEach { meal ->
                val r = reminders[meal] ?: Reminder(false, ReminderTimes.DEFAULTS.getValue(meal))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meal.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    TextButton({
                        TimePickerDialog(context, { _, h, m -> onChange(meal, r.copy(minutes = h * 60 + m)) }, r.hour, r.minute, false).show()
                    }) { Text(ReminderTimes.label(r.minutes)) }
                    Switch(
                        checked = r.enabled,
                        onCheckedChange = { on ->
                            if (on && !hasPermission()) { pendingEnable = meal; askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            else onChange(meal, r.copy(enabled = on))
                        },
                    )
                }
            }
            Text(
                if (denied) "Notifications are blocked — allow them for Aarogya in Android Settings to get reminders."
                else "Reminds you only if that meal isn't logged yet. Times are in ${AppTime.shortName(AppTime.zone)} time (your tracking time zone).",
                Modifier.padding(top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
