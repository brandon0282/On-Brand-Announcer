package dev.bws.announcer.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Text-to-speech front end.
 *
 * Threading model (the fix for the 1.3.0 "no voices / nothing speaks" bug): every TextToSpeech call
 * and every piece of Speaker state lives on ONE dedicated thread. Public methods and engine callbacks
 * only post work to that thread, and nothing holds a lock while calling into the engine. Previously a
 * Speaker monitor was held while calling TextToSpeech, while TextToSpeech held its own lock while
 * calling back into Speaker (onInit / progress), which could deadlock when the engine (re)connected.
 *
 * The engine (Google TTS, a separate app) can die or update at any time. A failed request triggers a
 * reconnect with backoff, and the utterance is re-sent once the engine is back.
 */
class Speaker(context: Context, private val settings: Settings) {
    private val appContext = context.applicationContext
    private val pkg = appContext.packageName
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val thread = HandlerThread("announcer-tts").apply { start() }
    private val h = Handler(thread.looper)

    // ---- state below is touched only on [h]'s thread ----
    private var tts: TextToSpeech? = null
    private var generation = 0
    private var ready = false
    private var connecting = false
    private var retryDelayMs = 1_000L
    private val backlog = ArrayDeque<Utterance>()
    private val speaking = HashSet<String>() // speech utterance ids in flight (tones/silence aren't tracked)
    private var focus: AudioFocusRequest? = null
    private var defaultVoice: Voice? = null
    private var voiceByName: Map<String, Voice> = emptyMap()
    private val registeredChimes = HashSet<Chime>()
    private var nextId = 0

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    /** Installed voices with friendly names. Empty until the engine is ready. */
    val voices: StateFlow<List<VoiceInfo>> = _voices

    private val _engineReady = MutableStateFlow(false)
    /** False while connecting to (or after losing) the speech engine. */
    val engineReady: StateFlow<Boolean> = _engineReady

    private data class Utterance(
        val text: String, val voice: String?, val rate: Float?, val pitch: Float?, val flush: Boolean,
        /** null = use the setting; otherwise force this tone (previews). */
        val chime: Chime? = null,
        val attempts: Int = 0,
    )

    private fun log(msg: String) { if (dev.bws.announcer.BuildConfig.DEBUG) android.util.Log.d("AnnouncerSmoke", msg) }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { log("start $id") }
        override fun onDone(id: String?) { log("done $id"); h.post { finished(id) } }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { log("error $id"); h.post { finished(id) } }
        override fun onError(id: String?, errorCode: Int) { log("error $id code=$errorCode"); h.post { finished(id) } }
        override fun onStop(id: String?, interrupted: Boolean) { log("stop $id"); h.post { finished(id) } }
    }

    init { h.post { connect() } }

    // ------------------------------------------------------------------ public API (any thread)

    /** Speak with the given voice name (null = global setting). */
    fun speak(text: String, voice: String? = null) =
        h.post { submit(Utterance(text, voice ?: settings.current.voiceName, null, null, false)) }

    /** Interrupts whatever is playing to audition a voice/style. */
    fun preview(text: String, voice: String?, rate: Float? = null, pitch: Float? = null) =
        h.post { submit(Utterance(text, voice, rate, pitch, true)) }

    /** Audition an attention tone followed by a short sample. */
    fun previewChime(chime: Chime) =
        h.post { submit(Utterance("New message", settings.current.voiceName, null, null, true, chime = chime)) }

    fun stop() = h.post {
        runCatching { tts?.stop() }
        backlog.clear()
        speaking.clear()
        releaseFocus()
    }

    fun refreshVoices() = h.post {
        if (ready) loadVoices() else if (!connecting) connect()
    }

    /** Manual "Retry" from the UI. */
    fun reconnect() = h.post { retryDelayMs = 1_000L; connect() }

    fun shutdown() = h.post { runCatching { tts?.shutdown() }; tts = null; releaseFocus(); thread.quitSafely() }

    // ------------------------------------------------------------------ engine lifecycle (h thread)

    private fun connect() {
        ready = false
        connecting = true
        _engineReady.value = false
        registeredChimes.clear()
        runCatching { tts?.shutdown() }
        val gen = ++generation
        log("connect gen=$gen")
        tts = TextToSpeech(appContext) { status -> h.post { onInit(gen, status) } }.apply {
            setAudioAttributes(attrs)
            setOnUtteranceProgressListener(listener)
        }
    }

    private fun onInit(gen: Int, status: Int) {
        if (gen != generation) return // a newer connection superseded this one
        connecting = false
        log("init gen=$gen status=$status engine=${runCatching { tts?.defaultEngine }.getOrNull()}")
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            retryDelayMs = 1_000L
            _engineReady.value = true
            loadVoices()
            while (ready && backlog.isNotEmpty()) enqueue(backlog.removeFirst())
        } else {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (connecting) return
        connecting = true
        _engineReady.value = false
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        log("reconnect in ${delay}ms")
        h.postDelayed({ connecting = false; connect() }, delay)
    }

    private fun loadVoices() {
        val t = tts ?: return
        if (defaultVoice == null) defaultVoice = runCatching { t.defaultVoice ?: t.voice }.getOrNull()
        val all = runCatching { t.voices }.getOrNull().orEmpty()
        voiceByName = all.associateBy { it.name }
        _voices.value = VoiceCatalog.describe(all)
        log("voices=${all.size}")
    }

    // ------------------------------------------------------------------ speaking (h thread)

    private fun submit(u: Utterance) {
        if (u.flush) {
            runCatching { tts?.stop() }
            speaking.clear()
            backlog.clear()
        }
        if (!ready) {
            backlog.addLast(u)
            while (backlog.size > 20) backlog.removeFirst() // don't replay a long stale queue after an outage
            if (!connecting) connect()
            return
        }
        enqueue(u)
    }

    private fun enqueue(u: Utterance) {
        val t = tts ?: return submitLater(u)
        val c = settings.current
        t.setSpeechRate(u.rate ?: c.speechRate)
        t.setPitch(u.pitch ?: c.pitch)
        val wanted = u.voice?.let { voiceByName[it] } ?: defaultVoice
        if (wanted != null && wanted != t.voice) runCatching { t.voice = wanted }

        val idle = speaking.isEmpty()
        log("enqueue idle=$idle voice=${t.voice?.name} chime=${u.chime ?: c.chime}")
        if (idle) requestFocus(c.duckOthers)
        // One tone per burst: only when starting from silence.
        val chime = u.chime ?: c.chime
        if (idle && chime != Chime.OFF && registerChime(t, chime)) {
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, c.chimeVolume.coerceIn(0.05f, 1f)) }
            t.playEarcon(earcon(chime), TextToSpeech.QUEUE_ADD, params, "c${++nextId}")
            t.playSilentUtterance(150, TextToSpeech.QUEUE_ADD, "s${++nextId}")
        }
        val id = "u${++nextId}"
        speaking += id
        val result = t.speak(u.text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        log("speak $id -> $result")
        if (result != TextToSpeech.SUCCESS) {
            // Engine connection is gone (e.g. Google TTS updated or was killed): reconnect and retry once.
            speaking -= id
            if (speaking.isEmpty()) releaseFocus()
            if (u.attempts < 1) submitLater(u.copy(attempts = u.attempts + 1))
            return
        }
        // Safety net: if the engine never reports back, don't leave other audio ducked forever.
        val timeoutMs = 15_000L + u.text.length * 120L
        h.postDelayed({ if (speaking.remove(id) && speaking.isEmpty()) releaseFocus() }, timeoutMs)
    }

    private fun submitLater(u: Utterance) {
        ready = false
        backlog.addFirst(u)
        scheduleReconnect()
    }

    private fun finished(id: String?) {
        if (id == null || !id.startsWith("u")) return // tones and pauses don't report reliably
        if (speaking.remove(id) && speaking.isEmpty()) releaseFocus()
    }

    private fun earcon(c: Chime) = "[announcer_${c.name.lowercase()}]"

    private fun registerChime(t: TextToSpeech, c: Chime): Boolean {
        if (c in registeredChimes) return true
        val res = Chimes.resource(c) ?: return false
        // Registered by resource id: the TTS engine is another app and reads our APK resources directly.
        val ok = runCatching { t.addEarcon(earcon(c), pkg, res) }.getOrNull() == TextToSpeech.SUCCESS
        if (ok) registeredChimes += c
        return ok
    }

    private fun requestFocus(duck: Boolean) {
        if (focus != null) return
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
