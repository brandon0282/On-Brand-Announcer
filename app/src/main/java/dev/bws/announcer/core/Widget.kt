package dev.bws.announcer.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import dev.bws.announcer.AnnouncerApp
import dev.bws.announcer.R
import dev.bws.announcer.ui.MainActivity
import java.time.LocalDate

/** Home-screen widget: today's count + on/off pill. Pushed updates only (no polling). */
class AnnouncerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) =
        refresh(context)

    companion object {

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AnnouncerWidget::class.java))
            if (ids.isEmpty()) return
            val app = AnnouncerApp.instance
            val on = app.settings.current.enabled
            val today = app.stats.days.value.firstOrNull { it.date == LocalDate.now().toString() }
            fun build(layout: Int, compact: Boolean) = RemoteViews(context.packageName, layout).apply {
                val read = today?.read ?: 0
                val skipped = today?.skipped ?: 0
                setTextViewText(R.id.w_count, "$read")
                setTextViewText(R.id.w_caption, if (compact) "read · $skipped skipped" else "read today · $skipped skipped")
                setTextViewText(R.id.w_toggle, if (on) "On" else "Paused")
                setInt(R.id.w_toggle, "setBackgroundResource", if (on) R.drawable.widget_pill_on else R.drawable.widget_pill_off)
                setInt(R.id.w_dot, "setBackgroundResource", if (on) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
                setOnClickPendingIntent(R.id.w_toggle, PendingIntent.getBroadcast(context, 1,
                    Intent(context, WidgetToggleReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 2,
                    Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            // Android 12+: the launcher picks the largest layout that fits each size (incl. both foldable screens).
            // Older versions: choose by the reported minimum height.
            val roomyMinHeight = 160f
            if (Build.VERSION.SDK_INT >= 31) {
                val v = RemoteViews(mapOf(
                    SizeF(0f, 0f) to build(R.layout.widget_compact, true),
                    SizeF(0f, roomyMinHeight) to build(R.layout.widget, false),
                ))
                manager.updateAppWidget(ids, v)
            } else {
                ids.forEach { id ->
                    val h = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    manager.updateAppWidget(id, if (h >= roomyMinHeight) build(R.layout.widget, false) else build(R.layout.widget_compact, true))
                }
            }
        }
    }
}

/**
 * Handles the widget's On/Paused button. Deliberately NOT exported: the widget provider must be
 * exported for the launcher, so toggling lives here where only our own PendingIntent can reach it.
 */
class WidgetToggleReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = AnnouncerApp.instance
        app.settings.update { it.copy(enabled = !it.enabled) }
        if (!app.settings.current.enabled) app.speaker.stop()
        AnnouncerWidget.refresh(context)
    }
}
