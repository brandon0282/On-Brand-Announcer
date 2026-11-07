package dev.bws.announcer.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LogEntry(
    val time: Long,
    val pkg: String,
    val app: String,
    val spoken: String?, // null when muted
    val reason: String?, // why it was muted
)

/** Rolling on-device log of the last [MAX] decisions; answers "why didn't it read that?". */
class ActivityLog(context: Context) {
    private val file = File(context.filesDir, "log.json")
    private val persist = Persister(file)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<LogEntry>> = _entries

    @Synchronized
    fun add(e: LogEntry) {
        val next = (listOf(e) + _entries.value).take(MAX)
        _entries.value = next
        persist.save { JSONArray(next.map(::toJson)).toString() }
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        persist.delete()
    }

    private fun load(): List<LogEntry> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) {
            val o = a.getJSONObject(it)
            LogEntry(
                o.getLong("t"), o.getString("p"), o.getString("a"),
                o.optString("s").ifEmpty { null }, o.optString("r").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    private fun toJson(e: LogEntry) = JSONObject().apply {
        put("t", e.time); put("p", e.pkg); put("a", e.app)
        e.spoken?.let { put("s", it) }; e.reason?.let { put("r", it) }
    }

    companion object { const val MAX = 200 }
}
