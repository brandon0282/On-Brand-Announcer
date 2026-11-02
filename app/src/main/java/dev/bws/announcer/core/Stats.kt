package dev.bws.announcer.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant

/** One day's counters. Kept for [Stats.DAYS] days, on-device only. */
data class DayStats(
    val date: String, // ISO yyyy-MM-dd
    val read: Int = 0,
    val skipped: Int = 0,
    val words: Int = 0,
    val apps: Map<String, Int> = emptyMap(),      // pkg -> read count
    val labels: Map<String, String> = emptyMap(), // pkg -> label
    val reasons: Map<String, Int> = emptyMap(),   // skip reason -> count
    val hours: List<Int> = List(24) { 0 },        // read count per hour
)

class Stats(context: Context) {
    private val file = File(context.filesDir, "stats.json")
    private val _days = MutableStateFlow(load())
    /** Newest last. */
    val days: StateFlow<List<DayStats>> = _days

    @Synchronized
    fun record(time: Long, pkg: String, label: String, spoken: String?, reason: String?) {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault())
        val key = dt.toLocalDate().toString()
        val list = _days.value.toMutableList()
        val idx = list.indexOfFirst { it.date == key }
        val d = if (idx >= 0) list[idx] else DayStats(key)
        // Notifications folded into a combined announcement count as read, but add no words.
        val combined = spoken == null && reason == Rules.COMBINED
        val next = if (spoken != null || combined) d.copy(
            read = d.read + 1,
            words = d.words + (spoken?.split(' ')?.count { it.isNotBlank() } ?: 0),
            apps = d.apps + (pkg to (d.apps[pkg] ?: 0) + 1),
            labels = d.labels + (pkg to label),
            hours = d.hours.toMutableList().also { it[dt.hour]++ },
        ) else d.copy(
            skipped = d.skipped + 1,
            reasons = d.reasons + (bucket(reason) to (d.reasons[bucket(reason)] ?: 0) + 1),
        )
        if (idx >= 0) list[idx] = next else list += next
        val cutoff = LocalDate.now().minusDays(DAYS.toLong()).toString()
        val trimmed = list.filter { it.date > cutoff }.sortedBy { it.date }
        _days.value = trimmed
        runCatching { file.writeText(JSONArray(trimmed.map(::toJson)).toString()) }
    }

    @Synchronized
    fun clear() { _days.value = emptyList(); file.delete() }

    /** Collapse per-word reasons ("Contains blocked word \"x\"") into one bucket. */
    private fun bucket(r: String?) = when {
        r == null -> "Other"
        r.startsWith("Contains blocked word") -> "Blocked word"
        else -> r
    }

    private fun load(): List<DayStats> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun toJson(d: DayStats) = JSONObject().apply {
        put("date", d.date); put("read", d.read); put("skipped", d.skipped); put("words", d.words)
        put("apps", JSONObject(d.apps)); put("labels", JSONObject(d.labels))
        put("reasons", JSONObject(d.reasons)); put("hours", JSONArray(d.hours))
    }

    private fun fromJson(o: JSONObject): DayStats {
        fun ints(k: String) = o.optJSONObject(k)?.let { j -> j.keys().asSequence().associateWith { j.getInt(it) } } ?: emptyMap()
        val h = o.optJSONArray("hours")
        return DayStats(
            date = o.getString("date"), read = o.optInt("read"), skipped = o.optInt("skipped"), words = o.optInt("words"),
            apps = ints("apps"), reasons = ints("reasons"),
            labels = o.optJSONObject("labels")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } ?: emptyMap(),
            hours = if (h != null && h.length() == 24) List(24) { h.getInt(it) } else List(24) { 0 },
        )
    }

    companion object { const val DAYS = 30 }
}
