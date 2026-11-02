package dev.bws.announcer

import android.app.Application
import android.content.ComponentName
import android.service.quicksettings.TileService
import dev.bws.announcer.core.ActivityLog
import dev.bws.announcer.core.AnnouncerWidget
import dev.bws.announcer.core.Deduper
import dev.bws.announcer.core.LogEntry
import dev.bws.announcer.core.Settings
import dev.bws.announcer.core.Speaker
import dev.bws.announcer.core.Stats
import dev.bws.announcer.core.TimeAnnouncer
import dev.bws.announcer.core.ToggleTileService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AnnouncerApp : Application() {
    lateinit var settings: Settings private set
    lateinit var log: ActivityLog private set
    lateinit var deduper: Deduper private set
    lateinit var stats: Stats private set
    val speaker: Speaker by lazy { Speaker(this, settings) }
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        log = ActivityLog(this)
        stats = Stats(this)
        // First run after upgrading: backfill the dashboard from the existing activity log.
        if (stats.days.value.isEmpty()) {
            log.entries.value.asReversed().forEach { stats.record(it.time, it.pkg, it.app, it.spoken, it.reason) }
        }
        deduper = Deduper({ settings.current.duplicateWindowSeconds * 1000L })

        // Keep the widget and Quick Settings tile in sync with on/off state and today's counts.
        scope.launch {
            combine(settings.config.map { it.enabled }.distinctUntilChanged(), stats.days) { on, _ -> on }
                .collect {
                    runCatching { AnnouncerWidget.refresh(this@AnnouncerApp) }
                    runCatching { TileService.requestListeningState(this@AnnouncerApp, ComponentName(this@AnnouncerApp, ToggleTileService::class.java)) }
                }
        }
        // Re-arm the time-announcement alarm whenever its interval changes.
        scope.launch {
            settings.config.map { it.timeInterval }.distinctUntilChanged().drop(1)
                .collect { TimeAnnouncer.schedule(this@AnnouncerApp) }
        }
    }

    /** Single entry point for every decision: feeds both the activity log and the dashboard stats. */
    fun record(e: LogEntry) {
        log.add(e)
        stats.record(e.time, e.pkg, e.app, e.spoken, e.reason)
    }

    companion object {
        lateinit var instance: AnnouncerApp private set
    }
}
