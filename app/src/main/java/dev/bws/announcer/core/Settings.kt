package dev.bws.announcer.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class OutputMode { HEADPHONES_ONLY, ALWAYS }

/** Per-app override. Empty [spokenName] = don't say the app name; null = use the real label. */
data class AppRule(
    val enabled: Boolean = true,
    val spokenName: String? = null,
    val maxWords: Int = 0, // 0 = use global
    val voiceName: String? = null, // null = use device/global voice
)

/** Per audio-output device (headphones, earbuds, car), keyed by its product name. */
data class DeviceRule(
    val enabled: Boolean = true,
    val voiceName: String? = null,
    val lastSeen: Long = 0,
)

/**
 * Quiet window in minutes since midnight. [days] are ISO day-of-week numbers (1 = Monday … 7 = Sunday)
 * on which the window *starts*, so 22:00–07:00 on Friday covers Friday night into Saturday morning.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val days: Set<Int> = (1..7).toSet(),
) {
    fun isActive(minuteOfDay: Int, dayOfWeek: Int): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val yesterday = if (dayOfWeek == 1) 7 else dayOfWeek - 1
        return if (startMinute < endMinute) {
            dayOfWeek in days && minuteOfDay >= startMinute && minuteOfDay < endMinute
        } else {
            (dayOfWeek in days && minuteOfDay >= startMinute) || (yesterday in days && minuteOfDay < endMinute)
        }
    }
}

data class Config(
    val enabled: Boolean = true,
    val outputMode: OutputMode = OutputMode.HEADPHONES_ONLY,
    val speakWhenScreenOn: Boolean = true,
    val speakDuringCall: Boolean = false,
    val respectDnd: Boolean = true,
    val skipSilent: Boolean = true,
    val newAppsEnabled: Boolean = true,
    val sayAppName: Boolean = true,
    val privacyMode: Boolean = false,
    val skipEmojis: Boolean = true,
    val shortenLinks: Boolean = true,
    val maxWords: Int = 40,
    val delaySeconds: Int = 0,
    val combineSeconds: Int = 3,
    val duplicateWindowSeconds: Int = 60,
    val duckOthers: Boolean = true,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceName: String? = null, // null = engine default
    val blockWords: List<String> = emptyList(),
    val allowWords: List<String> = emptyList(),
    val apps: Map<String, AppRule> = emptyMap(),
    // schedules
    val quiet: QuietHours = QuietHours(),
    // devices
    val devices: Map<String, DeviceRule> = emptyMap(),
    val newDevicesEnabled: Boolean = true,
    // priority people
    val priorityPeople: List<String> = emptyList(),
    val priorityBypass: Boolean = true,
    val prioritySpeaker: Boolean = false,
    // time announcements (minutes between, 0 = off)
    val timeInterval: Int = 0,
    // caller ID
    val callerId: Boolean = false,
    val callerRepeat: Boolean = true,
    val callerSpeaker: Boolean = true,
) {
    fun ruleFor(pkg: String): AppRule = apps[pkg] ?: AppRule(enabled = newAppsEnabled)
    fun deviceRule(name: String?): DeviceRule? =
        name?.let { devices[it] ?: DeviceRule(enabled = newDevicesEnabled) }
}

/** Whole config is one JSON blob in SharedPreferences: trivial to export/import and observe. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("announcer", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<Config> = _config
    val current: Config get() = _config.value

    @Synchronized
    fun update(transform: (Config) -> Config) {
        val next = transform(_config.value)
        if (next == _config.value) return
        _config.value = next
        prefs.edit().putString(KEY, toJson(next).toString()).apply()
    }

    fun setAppRule(pkg: String, rule: AppRule) = update { it.copy(apps = it.apps + (pkg to rule)) }
    fun setDeviceRule(name: String, rule: DeviceRule) = update { it.copy(devices = it.devices + (name to rule)) }

    /** Remember a newly connected output device so it shows up in the Devices screen. */
    fun noteDevice(name: String, now: Long = System.currentTimeMillis()) = update { c ->
        val r = c.devices[name] ?: DeviceRule(enabled = c.newDevicesEnabled)
        if (now - r.lastSeen < 60_000) c else c.copy(devices = c.devices + (name to r.copy(lastSeen = now)))
    }

    fun exportJson(): String = toJson(current).toString(2)
    fun importJson(json: String) = update { fromJson(JSONObject(json)) }

    private fun load(): Config =
        prefs.getString(KEY, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: Config()

    companion object {
        private const val KEY = "config_v1"

        fun toJson(c: Config) = JSONObject().apply {
            put("enabled", c.enabled)
            put("outputMode", c.outputMode.name)
            put("speakWhenScreenOn", c.speakWhenScreenOn)
            put("speakDuringCall", c.speakDuringCall)
            put("respectDnd", c.respectDnd)
            put("skipSilent", c.skipSilent)
            put("newAppsEnabled", c.newAppsEnabled)
            put("sayAppName", c.sayAppName)
            put("privacyMode", c.privacyMode)
            put("skipEmojis", c.skipEmojis)
            put("shortenLinks", c.shortenLinks)
            put("maxWords", c.maxWords)
            put("delaySeconds", c.delaySeconds)
            put("combineSeconds", c.combineSeconds)
            put("duplicateWindowSeconds", c.duplicateWindowSeconds)
            put("duckOthers", c.duckOthers)
            put("speechRate", c.speechRate.toDouble())
            put("pitch", c.pitch.toDouble())
            c.voiceName?.let { put("voiceName", it) }
            put("blockWords", JSONArray(c.blockWords))
            put("allowWords", JSONArray(c.allowWords))
            put("apps", JSONObject().apply {
                c.apps.forEach { (pkg, r) ->
                    put(pkg, JSONObject().apply {
                        put("enabled", r.enabled)
                        r.spokenName?.let { put("spokenName", it) }
                        put("maxWords", r.maxWords)
                        r.voiceName?.let { put("voiceName", it) }
                    })
                }
            })
            put("quiet", JSONObject().apply {
                put("enabled", c.quiet.enabled); put("start", c.quiet.startMinute); put("end", c.quiet.endMinute)
                put("days", JSONArray(c.quiet.days.sorted()))
            })
            put("devices", JSONObject().apply {
                c.devices.forEach { (name, r) ->
                    put(name, JSONObject().apply {
                        put("enabled", r.enabled); put("lastSeen", r.lastSeen)
                        r.voiceName?.let { put("voiceName", it) }
                    })
                }
            })
            put("newDevicesEnabled", c.newDevicesEnabled)
            put("priorityPeople", JSONArray(c.priorityPeople))
            put("priorityBypass", c.priorityBypass)
            put("prioritySpeaker", c.prioritySpeaker)
            put("timeInterval", c.timeInterval)
            put("callerId", c.callerId)
            put("callerRepeat", c.callerRepeat)
            put("callerSpeaker", c.callerSpeaker)
        }

        fun fromJson(j: JSONObject): Config {
            val d = Config()
            fun list(key: String) = j.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val apps = buildMap {
                j.optJSONObject("apps")?.let { o ->
                    o.keys().forEach { pkg ->
                        val r = o.getJSONObject(pkg)
                        put(pkg, AppRule(
                            enabled = r.optBoolean("enabled", true),
                            spokenName = if (r.has("spokenName")) r.getString("spokenName") else null,
                            maxWords = r.optInt("maxWords", 0),
                            voiceName = r.optString("voiceName").ifEmpty { null },
                        ))
                    }
                }
            }
            val devices = buildMap {
                j.optJSONObject("devices")?.let { o ->
                    o.keys().forEach { name ->
                        val r = o.getJSONObject(name)
                        put(name, DeviceRule(r.optBoolean("enabled", true), r.optString("voiceName").ifEmpty { null }, r.optLong("lastSeen")))
                    }
                }
            }
            val quiet = j.optJSONObject("quiet")?.let { q ->
                QuietHours(
                    enabled = q.optBoolean("enabled"),
                    startMinute = q.optInt("start", d.quiet.startMinute),
                    endMinute = q.optInt("end", d.quiet.endMinute),
                    days = q.optJSONArray("days")?.let { a -> List(a.length()) { a.getInt(it) }.toSet() } ?: d.quiet.days,
                )
            } ?: d.quiet
            return Config(
                enabled = j.optBoolean("enabled", d.enabled),
                outputMode = runCatching { OutputMode.valueOf(j.getString("outputMode")) }.getOrDefault(d.outputMode),
                speakWhenScreenOn = j.optBoolean("speakWhenScreenOn", d.speakWhenScreenOn),
                speakDuringCall = j.optBoolean("speakDuringCall", d.speakDuringCall),
                respectDnd = j.optBoolean("respectDnd", d.respectDnd),
                skipSilent = j.optBoolean("skipSilent", d.skipSilent),
                newAppsEnabled = j.optBoolean("newAppsEnabled", d.newAppsEnabled),
                sayAppName = j.optBoolean("sayAppName", d.sayAppName),
                privacyMode = j.optBoolean("privacyMode", d.privacyMode),
                skipEmojis = j.optBoolean("skipEmojis", d.skipEmojis),
                shortenLinks = j.optBoolean("shortenLinks", d.shortenLinks),
                maxWords = j.optInt("maxWords", d.maxWords),
                delaySeconds = j.optInt("delaySeconds", d.delaySeconds),
                combineSeconds = j.optInt("combineSeconds", d.combineSeconds),
                duplicateWindowSeconds = j.optInt("duplicateWindowSeconds", d.duplicateWindowSeconds),
                duckOthers = j.optBoolean("duckOthers", d.duckOthers),
                speechRate = j.optDouble("speechRate", d.speechRate.toDouble()).toFloat(),
                pitch = j.optDouble("pitch", d.pitch.toDouble()).toFloat(),
                voiceName = j.optString("voiceName").ifEmpty { null },
                blockWords = list("blockWords"),
                allowWords = list("allowWords"),
                apps = apps,
                quiet = quiet,
                devices = devices,
                newDevicesEnabled = j.optBoolean("newDevicesEnabled", d.newDevicesEnabled),
                priorityPeople = list("priorityPeople"),
                priorityBypass = j.optBoolean("priorityBypass", d.priorityBypass),
                prioritySpeaker = j.optBoolean("prioritySpeaker", d.prioritySpeaker),
                timeInterval = j.optInt("timeInterval", d.timeInterval),
                callerId = j.optBoolean("callerId", d.callerId),
                callerRepeat = j.optBoolean("callerRepeat", d.callerRepeat),
                callerSpeaker = j.optBoolean("callerSpeaker", d.callerSpeaker),
            )
        }
    }
}
