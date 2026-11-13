package com.npfs.stars.kinetictorch

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
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
        if (DeviceEligibility.isMotorolaDevice()) {
            getSharedPreferences(ChopDetectionService.PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)
            }
            stopService(Intent(this, ChopDetectionService::class.java))
            Toast.makeText(this, R.string.motorola_excluded_message, Toast.LENGTH_LONG).show()
            syncTileState()
            return
        }
        if (!DeviceEligibility.hasHardwareFlash(this)) {
            getSharedPreferences(ChopDetectionService.PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)
            }
            stopService(Intent(this, ChopDetectionService::class.java))
            Toast.makeText(this, R.string.led_required_message, Toast.LENGTH_LONG).show()
            syncTileState()
            return
        }
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
        if (DeviceEligibility.isMotorolaDevice()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.motorola_tile_label)
            tile.updateTile()
            return
        }
        if (!DeviceEligibility.hasHardwareFlash(this)) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.led_required_tile_label)
            tile.updateTile()
            return
        }
        val prefs = getSharedPreferences(ChopDetectionService.PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)

        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (enabled) "Chop Torch: ON" else "Chop Torch: OFF"
        tile.updateTile()
    }
}

