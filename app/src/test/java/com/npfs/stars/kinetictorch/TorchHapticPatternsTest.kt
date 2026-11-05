package com.npfs.stars.kinetictorch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchHapticPatternsTest {
    @Test fun activationIsTwoPulsesWithPauseAndNoLeadingDelay() {
        assertArrayEquals(longArrayOf(0L, 60L, 90L, 60L), TorchHapticPatterns.activationTimingsMs())
    }

    @Test fun deactivationIsOneShortPulse() {
        assertTrue(TorchHapticPatterns.DEACTIVATION_PULSE_MS > 0L)
        assertTrue(TorchHapticPatterns.DEACTIVATION_PULSE_MS < TorchHapticPatterns.ACTIVATION_PULSE_MS +
            TorchHapticPatterns.ACTIVATION_GAP_MS + TorchHapticPatterns.ACTIVATION_PULSE_MS)
    }
}
