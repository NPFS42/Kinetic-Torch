package com.npfs.stars.kinetictorch

/**
 * Distinguishable haptic confirmation patterns (4x longer buzzes than RC1) without a repeating vibration.
 * VibrationEffect.createWaveform interprets even positions as off-times and odd as on-times.
 */
internal object TorchHapticPatterns {
    const val ACTIVATION_PULSE_MS = 240L
    const val ACTIVATION_GAP_MS = 90L
    const val DEACTIVATION_PULSE_MS = 220L

    // Start immediately, vibrate, pause, then vibrate again; -1 means no repeats.
    fun activationTimingsMs(): LongArray = longArrayOf(
        0L, ACTIVATION_PULSE_MS, ACTIVATION_GAP_MS, ACTIVATION_PULSE_MS
    )

    // A single pulse using the same createWaveform API already verified for ON on-device.
    fun deactivationTimingsMs(): LongArray = longArrayOf(0L, DEACTIVATION_PULSE_MS)
}
