package com.npfs.stars.kinetictorch

/**
 * Distinguishable, brief haptic confirmation patterns without a repeating vibration.
 * VibrationEffect.createWaveform interprets even positions as off-times and odd as on-times.
 */
internal object TorchHapticPatterns {
    const val ACTIVATION_PULSE_MS = 60L
    const val ACTIVATION_GAP_MS = 90L
    const val DEACTIVATION_PULSE_MS = 55L

    // Start immediately, vibrate, pause, then vibrate again; -1 means no repeats.
    fun activationTimingsMs(): LongArray = longArrayOf(
        0L, ACTIVATION_PULSE_MS, ACTIVATION_GAP_MS, ACTIVATION_PULSE_MS
    )
}
