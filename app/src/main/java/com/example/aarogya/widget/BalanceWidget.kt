package com.example.aarogya.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.aarogya.AarogyaApp
import com.example.aarogya.MainActivity
import com.example.aarogya.R
import com.example.aarogya.data.Analyzer
import com.example.aarogya.data.AppTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen widget with today's calorie balance (in minus out). The app refreshes it whenever
 * food, activities, WHOOP data or settings change; an alarm at midnight in the tracking time zone
 * rolls it over to the new day.
 */
class BalanceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIDNIGHT) refreshAsync(context) else super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(midnightIntent(context))
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { refresh(context) } finally { pending.finish() }
        }
    }

    companion object {
        private const val ACTION_MIDNIGHT = "com.example.aarogya.widget.MIDNIGHT"

        /** Redraws every placed widget. Call from a background thread (it reads the database). */
        fun refresh(context: Context) {
            val app = context.applicationContext as AarogyaApp
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, BalanceWidget::class.java))
            if (ids.isEmpty()) return
            val today = AppTime.today()
            val analysis = Analyzer.analyze(app.store.foods(today), app.store.burns(today), app.prefs.state.value, emptyList(), true, app.store.whoopDay(today))
            val views = render(app, WidgetBalance.from(analysis))
            manager.updateAppWidget(ids, views)
            scheduleMidnight(app)
        }

        private fun render(context: Context, b: WidgetBalance) = RemoteViews(context.packageName, R.layout.widget_balance).apply {
            setTextViewText(R.id.widget_label, b.label)
            setTextViewText(R.id.widget_value, b.value)
            setTextViewText(R.id.widget_detail, b.detail)
            val color = context.getColor(
                when (b.tone) {
                    WidgetBalance.Tone.DEFICIT -> R.color.widget_deficit
                    WidgetBalance.Tone.SURPLUS -> R.color.widget_surplus
                    WidgetBalance.Tone.NEUTRAL -> R.color.widget_text
                }
            )
            setTextColor(R.id.widget_value, color)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setOnClickPendingIntent(R.id.widget_root, open)
        }

        private fun midnightIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, BalanceWidget::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Inexact, battery-friendly: the widget may roll over a few minutes after midnight. */
        private fun scheduleMidnight(context: Context) {
            val at = AppTime.today().plusDays(1).atStartOfDay(AppTime.zone).toInstant().toEpochMilli() + 60_000
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, at, midnightIntent(context))
        }
    }
}
