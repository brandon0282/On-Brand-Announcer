package dev.bws.announcer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS wrapper: queues utterances, holds transient audio focus (ducking other apps) only while
 * something is actually being spoken, and releases it when the queue drains.
 * Each utterance can use its own voice (global or per-app), resolved by name at speak time.
 */
class Speaker(context: Context, private val settings: Settings) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val pending = AtomicInteger(0)
    private var focus: AudioFocusRequest? = null
    private var ready = false
    private val backlog = ArrayList<Utterance>()
    private val ids = AtomicInteger(0)
    private var defaultVoice: Voice? = null

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    /** Installed voices with friendly names, best first. Empty until the engine is ready. */
    val voices: StateFlow<List<VoiceInfo>> = _voices

    private data class Utterance(val text: String, val voice: String?, val rate: Float?, val pitch: Float?, val flush: Boolean)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        synchronized(this) {
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                refreshVoices()
                backlog.forEach(::enqueue); backlog.clear()
            }
        }
    }.apply {
        setAudioAttributes(attrs)
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished()
            override fun onStop(id: String?, interrupted: Boolean) = finished()
        })
    }

    /** Speak with the given voice name (null = global setting). */
    fun speak(text: String, voice: String? = null) = submit(Utterance(text, voice ?: settings.current.voiceName, null, null, false))

    /** Interrupts whatever is playing to audition a voice/style. */
    fun preview(text: String, voice: String?, rate: Float? = null, pitch: Float? = null) =
        submit(Utterance(text, voice, rate, pitch, true))

    @Synchronized
    private fun submit(u: Utterance) {
        if (!ready) { backlog += u; return }
        if (u.flush) { tts.stop(); pending.set(0) }
        enqueue(u)
    }

    @Synchronized
    fun stop() {
        tts.stop()
        pending.set(0)
        releaseFocus()
    }

    fun shutdown() { stop(); tts.shutdown() }

    @Synchronized
    fun refreshVoices() {
        if (!ready) return
        if (defaultVoice == null) defaultVoice = runCatching { tts.defaultVoice ?: tts.voice }.getOrNull()
        _voices.value = VoiceCatalog.describe(runCatching { tts.voices }.getOrNull().orEmpty())
    }

    private fun enqueue(u: Utterance) {
        val c = settings.current
        tts.setSpeechRate(u.rate ?: c.speechRate)
        tts.setPitch(u.pitch ?: c.pitch)
        val v = u.voice?.let { name -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == name } } ?: defaultVoice
        if (v != null && v != tts.voice) runCatching { tts.voice = v }
        if (pending.getAndIncrement() == 0) requestFocus(c.duckOthers)
        tts.speak(u.text, TextToSpeech.QUEUE_ADD, Bundle(), "u${ids.incrementAndGet()}")
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            synchronized(this) { releaseFocus() }
        }
    }

    private fun requestFocus(duck: Boolean) {
        val req = AudioFocusRequest.Builder(
            if (duck) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(req)
        focus = req
    }

    private fun releaseFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}
