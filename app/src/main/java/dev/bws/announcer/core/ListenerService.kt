package dev.bws.announcer.core

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.bws.announcer.AnnouncerApp
import java.time.LocalDateTime

class ListenerService : NotificationListenerService() {
    private val app get() = AnnouncerApp.instance
    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** A burst of announcements waiting to be spoken together (delay and/or combine window). */
    private class Pending(val items: MutableList<Pair<Parsed, Decision.Speak>>, val keys: MutableSet<String>, val runnable: Runnable)
    private val pending = HashMap<String, Pending>() // by group key; main thread only

    /** Incoming-call keys currently ringing, for repeat announcements. */
    private val ringing = HashSet<String>()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            added.filter { it.isSink && it.type in HEADPHONE_TYPES }.forEach { d ->
                d.productName?.toString()?.takeIf { it.isNotBlank() }?.let { app.settings.noteDevice(it) }
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        audio.registerAudioDeviceCallback(deviceCallback, main)
        TimeAnnouncer.schedule(this)
    }

    override fun onListenerDisconnected() {
        connected = false
        runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null || sbn.packageName == packageName) return
        val parsed = runCatching { NotificationParser.parse(this, sbn, rankingMap) }.getOrNull() ?: return
        val config = app.settings.current
        val state = deviceState(this)

        if (parsed.isIncomingCall) {
            if (config.callerId) main.post { announceCall(parsed, first = true) }
            return
        }

        val decision = Rules.decide(parsed, state, config) { content -> app.deduper.checkAndRecord(parsed.pkg, content) }
        when (decision) {
            is Decision.Mute -> record(parsed, null, decision.reason)
            is Decision.Speak -> main.post { queue(parsed, decision, config) }
        }
    }

    private fun queue(parsed: Parsed, d: Decision.Speak, c: Config) {
        val waitSec = maxOf(c.delaySeconds, c.combineSeconds)
        val existing = pending[d.group]
        if (existing != null && c.combineSeconds > 0) {
            existing.items += parsed to d
            existing.keys += parsed.key
            return
        }
        if (waitSec <= 0) {
            app.speaker.speak(d.text, d.voice)
            record(parsed, d.text, null)
            return
        }
        lateinit var p: Pending
        val r = Runnable { flush(d.group, p) }
        p = Pending(mutableListOf(parsed to d), mutableSetOf(parsed.key), r)
        pending[d.group] = p
        main.postDelayed(r, waitSec * 1000L)
    }

    private fun flush(group: String, p: Pending) {
        if (pending[group] === p) pending.remove(group)
        if (p.items.isEmpty()) return
        val (lastParsed, last) = p.items.last()
        val text = Rules.combined(last, p.items.size, app.settings.current.privacyMode)
        app.speaker.speak(text, last.voice)
        p.items.dropLast(1).forEach { (parsed, _) -> record(parsed, null, Rules.COMBINED) }
        record(lastParsed, text, null)
    }

    private fun announceCall(p: Parsed, first: Boolean) {
        val c = app.settings.current
        if (first) { if (!ringing.add(p.key)) return } else if (p.key !in ringing) return
        val state = deviceState(this)
        val stillRinging = first || audio.mode == AudioManager.MODE_RINGTONE
        if (!c.enabled || !c.callerId || !stillRinging) { ringing -= p.key; return }
        if (!state.headphones && !c.callerSpeaker && c.outputMode == OutputMode.HEADPHONES_ONLY) {
            if (first) record(p, null, "Caller ID: no headphones connected")
            ringing -= p.key; return
        }
        val caller = p.title.ifBlank { p.body }
        if (caller.isBlank()) { ringing -= p.key; return }
        val text = Rules.callerText(caller)
        app.speaker.speak(text, c.voiceName)
        if (first) record(p, text, null)
        if (c.callerRepeat) main.postDelayed({ announceCall(p, first = false) }, 7_000)
        else ringing -= p.key
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val key = sbn?.key ?: return
        main.post {
            ringing -= key
            val entry = pending.entries.firstOrNull { key in it.value.keys } ?: return@post
            val p = entry.value
            p.keys -= key
            val gone = p.items.filter { it.first.key == key }
            p.items.removeAll { it.first.key == key }
            gone.forEach { (parsed, _) -> record(parsed, null, "Dismissed before it was read") }
            if (p.items.isEmpty()) {
                main.removeCallbacks(p.runnable)
                pending.remove(entry.key)
            }
        }
    }

    override fun onDestroy() {
        pending.values.forEach { main.removeCallbacks(it.runnable) }
        pending.clear()
        ringing.clear()
        super.onDestroy()
    }

    private fun record(p: Parsed, spoken: String?, reason: String?) =
        app.record(LogEntry(System.currentTimeMillis(), p.pkg, p.appLabel, spoken, reason))

    companion object {
        @Volatile var connected = false
            private set

        val HEADPHONE_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= 28) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
        }

        fun deviceState(context: Context): DeviceState {
            val audio = context.getSystemService(AudioManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            val outs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in HEADPHONE_TYPES }
            // Prefer a media (A2DP/BLE) device's name over its SCO twin.
            val name = (outs.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: outs.firstOrNull())
                ?.productName?.toString()?.takeIf { it.isNotBlank() }
            val now = LocalDateTime.now()
            return DeviceState(
                screenOn = power.isInteractive,
                inCall = audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE),
                headphones = outs.isNotEmpty(),
                deviceName = name,
                minuteOfDay = now.hour * 60 + now.minute,
                dayOfWeek = now.dayOfWeek.value,
            )
        }

        fun hasAccess(context: Context): Boolean {
            val flat = SysSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, ListenerService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
