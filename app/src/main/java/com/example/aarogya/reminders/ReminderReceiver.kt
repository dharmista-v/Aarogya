package com.example.aarogya.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.aarogya.AarogyaApp
import com.example.aarogya.MainActivity
import com.example.aarogya.R
import com.example.aarogya.data.AppTime
import com.example.aarogya.data.Meal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires at a meal's reminder time: notifies unless that meal is already logged today, then reschedules. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val meal = intent.getStringExtra(MealReminders.EXTRA_MEAL)?.let { runCatching { Meal.valueOf(it) }.getOrNull() } ?: return
        val app = context.applicationContext as AarogyaApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alreadyLogged = app.store.foods(AppTime.today()).any { it.meal == meal }
                if (!alreadyLogged) notify(context, meal)
                MealReminders.schedule(context, meal, app.prefs.reminders().getValue(meal)) // tomorrow's
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, meal: Meal) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 100 + meal.ordinal,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ADD_FOOD_MEAL, meal.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MealReminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Time to log ${meal.label.lowercase()}")
            .setContentText("What did you have? Snap a photo or type it in.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Add food", open)
            .build()
        NotificationManagerCompat.from(context).notify(meal.ordinal + 1, notification)
    }
}

/** Alarms are cleared on reboot and can drift on clock/time zone changes: schedule them again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = MealReminders.scheduleAll(context)
}
