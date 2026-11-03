package com.npfs.stars.kinetictorch

/**
 * Single source of truth for settings on fresh installs and Reset Sliders.
 * Existing SharedPreferences values take precedence; upgrading never silently overwrites them.
 * The gesture baseline reflects an owner-tested device; wider device validation is still required.
 */
internal object TorchDefaults {
    const val FORCE_THRESHOLD = 12.0f      // m/s² (slider range 10.0–35.0)
    const val CHOP_WINDOW_MS = 750         // ms (slider range 300–1000)
    const val AUTO_OFF_MINUTES = 10
    const val WAKE_MODE = 2                // screen-wake + 30s
    const val COOLDOWN_MS = 1000           // not an exposed slider
}
