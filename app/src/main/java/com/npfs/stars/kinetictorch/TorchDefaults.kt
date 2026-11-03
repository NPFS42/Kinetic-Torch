package com.npfs.stars.kinetictorch

/**
 * Single source of truth for settings on fresh installs and Reset Sliders.
 * Existing SharedPreferences values take precedence; upgrading never silently overwrites them.
 * Gesture values are provisional until validated against several hardware models.
 */
internal object TorchDefaults {
    const val FORCE_THRESHOLD = 16.0f      // m/s² (slider range 10.0–35.0)
    const val CHOP_WINDOW_MS = 800         // ms (slider range 300–1000)
    const val AUTO_OFF_MINUTES = 10
    const val WAKE_MODE = 2                // screen-wake + 30s
    const val COOLDOWN_MS = 1000           // not an exposed slider
}
