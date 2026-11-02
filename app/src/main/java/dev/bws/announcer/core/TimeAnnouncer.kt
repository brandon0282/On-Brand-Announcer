package dev.bws.announcer.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import dev.bws.announcer.AnnouncerApp
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * Speaks the time on clock boundaries (every 15/30/60/120/180 min, aligned to midnight).
 * Uses exact alarms when "Alarms & reminders" is allowed; otherwise falls back to an inexact
 * alarm that Android may shift by a few minutes.
 */
object TimeAnnouncer {
    val intervals = listOf(0, 15, 30, 60, 120, 180)

    fun label(min: Int) = when (min) {
        0 -> "Off"; 15 -> "Every 15 minutes"; 30 -> "Every 30 minutes"; 60 -> "Every hour"
        else -> "Every ${min / 60} hours"
    }

    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, TimeReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        val interval = AnnouncerApp.instance.settings.current.timeInterval
        if (interval <= 0) return
        val at = nextBoundary(LocalDateTime.now(), interval).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (canBeExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Next time strictly after [now] that is a multiple of [interval] minutes since midnight. */
    fun nextBoundary(now: LocalDateTime, interval: Int): LocalDateTime {
        val midnight = now.toLocalDate().atStartOfDay()
        val minutes = now.hour * 60 + now.minute
        val next = (minutes / interval + 1) * interval
        return midnight.plusMinutes(next.toLong())
    }

    fun spokenTime(context: Context): String = "It's " + DateFormat.getTimeFormat(context).format(Date())
}

class TimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = AnnouncerApp.instance
        val c = app.settings.current
        val s = ListenerService.deviceState(context)
        val ok = c.enabled && c.timeInterval > 0 && !s.inCall &&
            !c.quiet.isActive(s.minuteOfDay, s.dayOfWeek) &&
            (c.outputMode == OutputMode.ALWAYS || s.headphones) &&
            (!s.headphones || c.deviceRule(s.deviceName)?.enabled != false)
        TimeAnnouncer.schedule(context)
        if (!ok) return
        // Keep the process alive briefly so the TTS engine can finish speaking.
        val done = goAsync()
        app.speaker.speak(TimeAnnouncer.spokenTime(context), c.deviceRule(s.deviceName)?.voiceName ?: c.voiceName)
        Handler(Looper.getMainLooper()).postDelayed({ done.finish() }, 8_000)
    }
}
