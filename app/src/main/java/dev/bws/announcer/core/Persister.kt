package dev.bws.announcer.core

import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * Debounced, off-main-thread file writer. Busy notification streams used to rewrite the log and stats
 * JSON on every single event; now the latest snapshot is written at most once per [delayMs].
 */
class Persister(private val file: File, private val delayMs: Long = 3_000L) {
    @Volatile private var pending: (() -> String)? = null
    private val write = Runnable {
        val snapshot = pending ?: return@Runnable
        pending = null
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(snapshot())
            tmp.renameTo(file) // atomic replace: a crash mid-write can't corrupt the file
        }
    }

    fun save(snapshot: () -> String) {
        val first = pending == null
        pending = snapshot
        if (first) handler.postDelayed(write, delayMs)
    }

    fun delete() {
        pending = null
        handler.removeCallbacks(write)
        handler.post { file.delete() }
    }

    companion object {
        private val handler by lazy { Handler(HandlerThread("announcer-io").apply { start() }.looper) }
    }
}
