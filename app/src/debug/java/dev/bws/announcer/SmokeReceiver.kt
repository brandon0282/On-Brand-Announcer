package dev.bws.announcer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bws.announcer.core.Chime

/** adb shell am broadcast -n dev.bws.announcer.smoke/dev.bws.announcer.SmokeReceiver --es cmd voice */
class SmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = AnnouncerApp.instance
        when (val cmd = intent.getStringExtra("cmd")) {
            "voice" -> app.speaker.speak("Smoke test ${intent.getStringExtra("n") ?: ""}.")
            "stop" -> app.speaker.stop()
            "retry" -> app.speaker.reconnect()
            "chime" -> app.speaker.previewChime(Chime.valueOf(intent.getStringExtra("name") ?: "SOFT_BELL"))
            else -> android.util.Log.w("AnnouncerSmoke", "unknown cmd $cmd")
        }
    }
}
