package dev.bws.announcer.core

/**
 * Pure (Android-free) decision logic, so it's unit-testable.
 * The service gathers the parsed notification + [DeviceState], then asks [Rules.decide].
 */
data class Parsed(
    val pkg: String,
    val key: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val fromSelf: Boolean,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isSilent: Boolean,
    val passesDnd: Boolean,
    val isMediaOrProgress: Boolean,
    /** Person behind the notification (chat sender / email from), when known. */
    val sender: String? = null,
    val isIncomingCall: Boolean = false,
)

data class DeviceState(
    val screenOn: Boolean,
    val inCall: Boolean,
    val headphones: Boolean,
    /** Product name of the connected headphone/car output, if any. */
    val deviceName: String? = null,
    val minuteOfDay: Int = 12 * 60,
    val dayOfWeek: Int = 1,
)

sealed interface Decision {
    data class Speak(
        val text: String,
        val voice: String? = null,
        /** Notifications with the same group key arriving close together are combined. */
        val group: String = "",
        val name: String = "",
        val sender: String? = null,
        val content: String = "",
        val priority: Boolean = false,
        /** True when content was withheld (privacy or sensitive), so combined summaries stay vague too. */
        val redacted: Boolean = false,
    ) : Decision
    data class Mute(val reason: String) : Decision
}

object Rules {
    const val COMBINED = "Combined into one announcement"

    fun isPriority(n: Parsed, c: Config): Boolean {
        if (c.priorityPeople.isEmpty()) return false
        val who = listOfNotNull(n.sender, n.title).joinToString(" ").lowercase()
        if (who.isBlank()) return false
        return c.priorityPeople.any { p ->
            val name = p.trim().lowercase()
            name.isNotEmpty() && Regex("""(^|\W)${Regex.escape(name)}(\W|$)""").containsMatchIn(who)
        }
    }

    fun decide(n: Parsed, d: DeviceState, c: Config, isDuplicate: (String) -> Boolean): Decision {
        val rule = c.ruleFor(n.pkg)
        val priority = isPriority(n, c)
        val bypass = priority && c.priorityBypass
        val device = if (d.headphones) c.deviceRule(d.deviceName) else null
        when {
            !c.enabled -> return Decision.Mute("Announcer is turned off")
            n.isGroupSummary -> return Decision.Mute("Group summary (individual messages are read instead)")
            n.fromSelf -> return Decision.Mute("Your own message")
            n.isOngoing -> return Decision.Mute("Ongoing/persistent notification")
            n.isMediaOrProgress -> return Decision.Mute("Media or progress notification")
            !bypass && !rule.enabled -> return Decision.Mute("App is muted")
            !bypass && c.skipSilent && n.isSilent -> return Decision.Mute("Silent notification")
            !bypass && c.respectDnd && !n.passesDnd -> return Decision.Mute("Blocked by Do Not Disturb")
            !bypass && c.quiet.isActive(d.minuteOfDay, d.dayOfWeek) -> return Decision.Mute("Quiet hours")
            c.outputMode == OutputMode.HEADPHONES_ONLY && !d.headphones && !(priority && c.prioritySpeaker) ->
                return Decision.Mute("No headphones connected")
            !bypass && device?.enabled == false -> return Decision.Mute("Turned off for ${d.deviceName}")
            !bypass && !c.speakWhenScreenOn && d.screenOn -> return Decision.Mute("Screen was on")
            !c.speakDuringCall && d.inCall -> return Decision.Mute("Call in progress")
        }

        val content = listOf(n.title, n.body).filter { it.isNotBlank() }.joinToString(". ")
        if (content.isBlank()) return Decision.Mute("No text content")

        if (!bypass) {
            val haystack = content.lowercase()
            c.blockWords.firstOrNull { it.isNotBlank() && haystack.contains(it.lowercase()) }
                ?.let { return Decision.Mute("Contains blocked word \"$it\"") }
            if (c.allowWords.any { it.isNotBlank() } &&
                c.allowWords.none { it.isNotBlank() && haystack.contains(it.lowercase()) }
            ) return Decision.Mute("No allow-list word present")
        }

        if (isDuplicate(content)) return Decision.Mute("Duplicate of a recent announcement")

        val name = when {
            !c.sayAppName -> ""
            rule.spokenName != null -> rule.spokenName
            else -> n.appLabel
        }
        val voice = rule.voiceName ?: device?.voiceName ?: c.voiceName
        val who = n.sender ?: n.title.takeIf { it.isNotBlank() }
        val group = n.pkg + "|" + (who?.lowercase() ?: "")
        val private = Privacy.isPrivate(d, device)
        val hideAll = when (c.privacy) {
            PrivacyMode.ALWAYS -> true
            PrivacyMode.SHARED_ONLY -> !private
            PrivacyMode.OFF -> false
        }
        val from = if (name.isBlank()) "" else " from $name"
        if (hideAll) {
            return Decision.Speak(if (name.isBlank()) "New notification" else "Notification$from",
                voice, group, name, who, "", priority, redacted = true)
        }
        // Codes, banking and security alerts: full detail only where nobody else can hear.
        val kind = Privacy.sensitiveKind(content)
        if (kind != null && (Privacy.isSystemRedacted(content) || (c.protectSensitive && !private))) {
            return Decision.Speak("$kind$from", voice, group, name, who, "", priority, redacted = true)
        }
        val limit = if (rule.maxWords > 0) rule.maxWords else c.maxWords
        val spoken = Text.shape(content, limit, c.skipEmojis, c.shortenLinks)
        if (spoken.isBlank()) return Decision.Mute("Nothing speakable after cleanup")
        val bodyOnly = Text.shape(n.body.ifBlank { content }, limit, c.skipEmojis, c.shortenLinks)
        return Decision.Speak(if (name.isBlank()) spoken else "$name. $spoken", voice, group, name, who, bodyOnly, priority)
    }

    /** "WhatsApp. 4 new messages from Mom. Latest: see you soon" */
    fun combined(latest: Decision.Speak, count: Int, privacy: Boolean): String {
        if (count <= 1) return latest.text
        val prefix = if (latest.name.isBlank()) "" else "${latest.name}. "
        if (privacy) return "$prefix$count new notifications"
        val head = if (latest.sender != null) "$count new messages from ${latest.sender}" else "$count new notifications"
        return if (latest.content.isBlank()) "$prefix$head" else "$prefix$head. Latest: ${latest.content}"
    }

    /** Spoken caller ID. Phone numbers are read digit by digit. */
    fun callerText(caller: String): String {
        val digits = caller.filter { it.isDigit() }
        val looksLikeNumber = digits.length >= 5 && caller.all { it.isDigit() || it in "+-() ." }
        return if (looksLikeNumber) "Call from " + digits.toList().joinToString(" ") else "Call from $caller"
    }
}

/** Who can hear the announcement, and which messages are too sensitive for shared audio. */
object Privacy {
    // Checked first: these are personal listening devices even when the brand also makes speakers
    // (e.g. "Soundcore Liberty 5 Pro" earbuds vs "Soundcore Motion" speaker).
    private val personal = Regex(
        """\b(liberty|life|space|aerofit|buds?\d*|pods|airpods|earbuds?|earphones?|headphones?|headset|beats|powerbeats|""" +
            """jabra|elite|freebuds|momentum|wf-\S+|wh-\S+|qc|quietcomfort|hearing|ear(fun)?|shokz|openrun|sport|""" +
            // Audio glasses: speakers sit at your ears, so treat them as personal (e.g. "AIWA GLASSES", "Echo Frames").
            """glasses|frames|aiwa|vivitar|lucyd|anzu|ray-?ban|meta|solos|vue|tempo)\b""",
        RegexOption.IGNORE_CASE,
    )
    // Audio others can hear: cars and loudspeakers.
    private val shared = Regex(
        """\b(car|auto|sync|uconnect|carplay|mylink|entune|mbux|idrive|uvo|bluelink|hands-?free|stereo|speaker|soundlink|""" +
            """motion|boom|flip|charge|pulse|xtreme|soundbar|sonos|echo|nest|tv|receiver|toyota|honda|ford|chevrolet|chevy|""" +
            """gmc|jeep|dodge|ram|kia|hyundai|subaru|nissan|tesla|bmw|audi|vw|volkswagen|lexus|acura|volvo|mazda|buick|cadillac|lincoln)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Best guess from a device's name; the user can override per device. */
    fun guessPrivate(name: String): Boolean = when {
        personal.containsMatchIn(name) -> true
        shared.containsMatchIn(name) -> false
        else -> true // unknown Bluetooth audio is most often headphones
    }

    fun isPrivate(d: DeviceState, rule: DeviceRule?): Boolean {
        if (!d.headphones) return false // phone speaker
        val name = d.deviceName ?: return true // wired / unnamed headphones
        return rule?.private ?: guessPrivate(name)
    }

    private val code = Regex("""\b(verification|verify|security|login|log-?in|sign-?in|one[- ]time|otp|2fa|passcode|confirmation|access)\s*(code|pin|number)?\b|\bcode\b|\bpin\b""", RegexOption.IGNORE_CASE)
    private val digits = Regex("""\b[A-Z]?-?\d{4,8}\b""")
    private val money = Regex("""[$€£]\s?\d|\b\d+[.,]\d{2}\s?(usd|eur|gbp)\b""", RegexOption.IGNORE_CASE)
    private val bankWords = Regex("""\b(balance|deposit|deposited|withdrawal|withdrawn|payment|paid|charged?|purchase|transaction|transfer|debit|credit|card ending|account ending|zelle|venmo|cash app)\b""", RegexOption.IGNORE_CASE)
    private val security = Regex("""\b(password|reset your|new sign-?in|login attempt|sign-?in attempt|unusual activity|suspicious activity|2-step|two-factor)\b""", RegexOption.IGNORE_CASE)
    private val systemHidden = Regex("""sensitive notification content hidden|content hidden""", RegexOption.IGNORE_CASE)

    /** Android 15+ blanks one-time codes for apps like this one; there's nothing to read anyway. */
    fun isSystemRedacted(text: String) = systemHidden.containsMatchIn(text)

    /** "Verification code" / "Banking alert" / "Security alert", or null if nothing sensitive. */
    fun sensitiveKind(text: String): String? = when {
        isSystemRedacted(text) -> "Verification code"
        code.containsMatchIn(text) && digits.containsMatchIn(text) -> "Verification code"
        money.containsMatchIn(text) && bankWords.containsMatchIn(text) -> "Banking alert"
        security.containsMatchIn(text) -> "Security alert"
        else -> null
    }
}

object Text {
    private val url = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    // Emoji & pictographs: So (other symbol) codepoints, variation selectors, ZWJ, skin-tone modifiers.
    private val emoji = Regex("""[\p{So}\x{FE0F}\x{FE0E}\x{200D}\x{1F3FB}-\x{1F3FF}]""")
    private val ws = Regex("""\s+""")

    fun shape(input: String, maxWords: Int, skipEmojis: Boolean, shortenLinks: Boolean): String {
        var s = input
        if (shortenLinks) s = url.replace(s) { m -> linkLabel(m.value) }
        if (skipEmojis) s = emoji.replace(s, " ")
        s = s.replace('_', ' ')
        s = ws.replace(s, " ").trim()
        if (maxWords > 0) {
            val words = s.split(' ')
            if (words.size > maxWords) s = words.take(maxWords).joinToString(" ") + "… and more"
        }
        return s
    }

    /** "https://www.youtube.com/watch?v=x" → "link to youtube.com" */
    private fun linkLabel(u: String): String {
        val host = u.substringAfter("://").removePrefix("www.").substringBefore('/').substringBefore('?')
        return if (host.isBlank()) "link" else "link to $host"
    }
}

/** Remembers recently spoken content (normalized) for [windowMs]; bounded LRU. */
class Deduper(private val windowMs: () -> Long, private val now: () -> Long = System::currentTimeMillis) {
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 300
    }

    @Synchronized
    fun checkAndRecord(pkg: String, content: String): Boolean {
        val k = pkg + "|" + content.lowercase().filter { it.isLetterOrDigit() }
        val t = now()
        val prev = seen[k]
        seen[k] = t
        return prev != null && t - prev < windowMs()
    }
}
