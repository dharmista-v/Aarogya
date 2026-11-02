package com.example.aarogya.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.aarogya.AarogyaApp
import com.example.aarogya.data.AppTime
import com.example.aarogya.data.Meal
import java.time.ZonedDateTime

/**
 * Schedules one alarm per enabled meal at its time in the tracking time zone. Uses
 * setAndAllowWhileIdle (battery-friendly, may be a few minutes late in deep sleep; no exact-alarm
 * permission needed). Each alarm reschedules the next day when it fires.
 */
object MealReminders {
    const val CHANNEL = "meal_reminders"
    const val EXTRA_MEAL = "meal"

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Meal reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders to log breakfast, lunch, snacks and dinner"
            }
        )
    }

    /** (Re)schedules every meal: enabled ones get an alarm, disabled ones are cancelled. */
    fun scheduleAll(context: Context) {
        val app = context.applicationContext as AarogyaApp
        val reminders = app.prefs.reminders()
        for (meal in Meal.entries) schedule(context, meal, reminders.getValue(meal))
    }

    fun schedule(context: Context, meal: Meal, reminder: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, meal)
        am.cancel(pi)
        if (!reminder.enabled) return
        val at = ReminderTimes.next(ZonedDateTime.now(AppTime.zone), reminder.minutes)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }

    private fun pendingIntent(context: Context, meal: Meal): PendingIntent = PendingIntent.getBroadcast(
        context, meal.ordinal,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_MEAL, meal.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
