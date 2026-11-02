package dev.bws.announcer.core

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.bws.announcer.AnnouncerApp

/** Quick Settings tile: one tap to pause/resume announcements (and silence anything mid-sentence). */
class ToggleTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val app = AnnouncerApp.instance
        app.settings.update { it.copy(enabled = !it.enabled) }
        if (!app.settings.current.enabled) app.speaker.stop()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = AnnouncerApp.instance.settings.current.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }
}
