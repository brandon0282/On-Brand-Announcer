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
        if (c.privacyMode) {
            return Decision.Speak(if (name.isBlank()) "New notification" else "Notification from $name",
                voice, group, name, who, "", priority)
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
