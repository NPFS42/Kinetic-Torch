package com.npfs.stars.kinetictorch

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.math.roundToInt

/** Only user-adjustable gesture settings; never includes arm state or device permissions. */
internal data class GestureTuning(
    val force: Float,
    val windowMs: Int,
    val autoOffMinutes: Int,
    val wakeMode: Int
) {
    fun normalized() = copy(
        force = (force.coerceIn(TorchDefaults.FORCE_MIN, TorchDefaults.FORCE_MAX) * 10f).roundToInt() / 10f,
        windowMs = windowMs.coerceIn(300, 1000),
        autoOffMinutes = autoOffMinutes.coerceIn(0, 30),
        wakeMode = wakeMode.coerceIn(0, 5)
    )

    companion object {
        fun defaults() = GestureTuning(
            TorchDefaults.FORCE_THRESHOLD,
            TorchDefaults.CHOP_WINDOW_MS,
            TorchDefaults.AUTO_OFF_MINUTES,
            TorchDefaults.WAKE_MODE
        )
    }
}

/** Saves one named custom profile and an independent automatic recovery snapshot on lock. */
internal class TorchProfileStore(private val prefs: SharedPreferences) {
    private companion object {
        const val LOCKED = "ui_settings_locked"
        const val RECOVERY = "ui_lock_snapshot"
        const val CUSTOM = "ui_custom_profile"
        const val CUSTOM_NAME = "ui_custom_profile_name"
    }

    fun isLocked() = prefs.getBoolean(LOCKED, false)

    fun current() = GestureTuning(
        prefs.getFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, TorchDefaults.FORCE_THRESHOLD),
        prefs.getInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, TorchDefaults.CHOP_WINDOW_MS),
        prefs.getInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, TorchDefaults.AUTO_OFF_MINUTES),
        prefs.getInt(ChopDetectionService.KEY_WAKE_MODE, TorchDefaults.WAKE_MODE)
    ).normalized()

    fun lock() {
        val tuning = current()
        prefs.edit {
            putSnapshot(RECOVERY, tuning)
            putBoolean(LOCKED, true)
        }
    }

    fun unlock() = prefs.edit { putBoolean(LOCKED, false) }

    fun saveCustom(name: String) {
        require(!isLocked()) { "Unlock settings before saving" }
        val safeName = name.trim().take(40).ifEmpty { "My profile" }
        prefs.edit {
            putSnapshot(CUSTOM, current())
            putString(CUSTOM_NAME, safeName)
        }
    }

    fun customName() = prefs.getString(CUSTOM_NAME, "My profile") ?: "My profile"

    fun custom() = readSnapshot(CUSTOM)
    fun recovery() = readSnapshot(RECOVERY)

    fun restore(tuning: GestureTuning) {
        require(!isLocked()) { "Unlock settings before restoring" }
        val value = tuning.normalized()
        prefs.edit {
            putFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, value.force)
            putInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, value.windowMs)
            putInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, value.autoOffMinutes)
            putInt(ChopDetectionService.KEY_WAKE_MODE, value.wakeMode)
        }
    }

    private fun SharedPreferences.Editor.putSnapshot(prefix: String, value: GestureTuning) {
        putFloat("${prefix}_force", value.force)
        putInt("${prefix}_window", value.windowMs)
        putInt("${prefix}_auto_off", value.autoOffMinutes)
        putInt("${prefix}_wake", value.wakeMode)
        putBoolean("${prefix}_present", true)
    }

    private fun readSnapshot(prefix: String): GestureTuning? {
        if (!prefs.getBoolean("${prefix}_present", false)) return null
        return GestureTuning(
            prefs.getFloat("${prefix}_force", TorchDefaults.FORCE_THRESHOLD),
            prefs.getInt("${prefix}_window", TorchDefaults.CHOP_WINDOW_MS),
            prefs.getInt("${prefix}_auto_off", TorchDefaults.AUTO_OFF_MINUTES),
            prefs.getInt("${prefix}_wake", TorchDefaults.WAKE_MODE)
        ).normalized()
    }
}
