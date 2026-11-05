package com.npfs.stars.kinetictorch

/**
 * Distinguishable haptic confirmation patterns (4x longer buzzes than RC1) without a repeating vibration.
 * VibrationEffect.createWaveform interprets even positions as off-times and odd as on-times.
 */
internal object TorchHapticPatterns {
    const val ACTIVATION_PULSE_MS = 240L
    const val ACTIVATION_GAP_MS = 90L
    const val DEACTIVATION_PULSE_MS = 220L
    const val DEACTIVATION_AMPLITUDE = 255  // Android's maximum explicit amplitude.
    const val CONFIRMED_OFF_HAPTIC_DELAY_MS = 140L // Avoid the immediate LED-off transition.

    // Start immediately, vibrate, pause, then vibrate again; -1 means no repeats.
    fun activationTimingsMs(): LongArray = longArrayOf(
        0L, ACTIVATION_PULSE_MS, ACTIVATION_GAP_MS, ACTIVATION_PULSE_MS
    )

    // A single pulse using the same createWaveform API already verified for ON on-device.
    fun deactivationTimingsMs(): LongArray = longArrayOf(0L, DEACTIVATION_PULSE_MS)

    // Only the OFF effect is made explicit; preserve the previously working double-ON pattern.
    fun deactivationAmplitudes(): IntArray = intArrayOf(0, DEACTIVATION_AMPLITUDE)

    /**
     * TEST ONLY: reproduce the proven ON waveform exactly on an OFF transition.
     * If this stays silent, the difference is not one-shot vs waveform shape.
     * Never use this temporary two-pulse OFF probe in production.
     */
    fun diagnosticOffProbeTimingsMs(): LongArray = activationTimingsMs()

    fun shouldDeliverConfirmedOff(
        confirmedLedState: Boolean?, torchActive: Boolean,
        requestId: Long, latestRequestId: Long
    ): Boolean = confirmedLedState == false && !torchActive && requestId == latestRequestId
}
