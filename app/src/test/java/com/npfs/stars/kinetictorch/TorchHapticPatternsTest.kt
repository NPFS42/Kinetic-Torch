package com.npfs.stars.kinetictorch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TorchHapticPatternsTest {
    @Test fun activationIsTwoPulsesWithPauseAndNoLeadingDelay() {
        assertArrayEquals(longArrayOf(0L, 240L, 90L, 240L), TorchHapticPatterns.activationTimingsMs())
    }

    @Test fun deactivationIsOneShortPulse() {
        assertEquals(220L, TorchHapticPatterns.DEACTIVATION_PULSE_MS)
        assertTrue(TorchHapticPatterns.DEACTIVATION_PULSE_MS > 0L)
        assertTrue(TorchHapticPatterns.DEACTIVATION_PULSE_MS < TorchHapticPatterns.ACTIVATION_PULSE_MS +
            TorchHapticPatterns.ACTIVATION_GAP_MS + TorchHapticPatterns.ACTIVATION_PULSE_MS)
    }
}
