package com.npfs.stars.kinetictorch

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit

class ChopTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        DebugLog.start(this)
        DebugLog.event("tile_clicked", "reason" to "USER_TILE_ARM_TOGGLE")
        val prefs = getSharedPreferences(ChopDetectionService.PREFS_NAME, MODE_PRIVATE)
        val currentlyEnabled = prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)
        val newState = !currentlyEnabled

        prefs.edit { putBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, newState) }

        val serviceIntent = Intent(this, ChopDetectionService::class.java)
        if (newState) {
            DebugLog.action("tile_start_service") { startForegroundService(serviceIntent) }
        } else {
            // Centralized stopService triggers onDestroy() -> shutdownTorchAndCleanup()
            DebugLog.action("tile_stop_service") { stopService(serviceIntent) }
        }
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val prefs = getSharedPreferences(ChopDetectionService.PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (enabled) "Chop Torch: ON" else "Chop Torch: OFF"
        tile.updateTile()
    }
}

