package com.npfs.stars.kinetictorch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TorchHapticPatternsTest {
    @Test fun activationIsTwoPulsesWithPauseAndNoLeadingDelay() {
        assertArrayEquals(longArrayOf(0L, 240L, 90L, 240L), TorchHapticPatterns.activationTimingsMs())
    }

    @Test fun deactivationIsExactlyOne220msWaveformPulse() {
        assertEquals(220L, TorchHapticPatterns.DEACTIVATION_PULSE_MS)
        assertArrayEquals(longArrayOf(0L, 220L), TorchHapticPatterns.deactivationTimingsMs())
    }

    @Test fun onPatternIsStillTheOriginalWorkingTwoPulses() {
        assertArrayEquals(longArrayOf(0L, 240L, 90L, 240L), TorchHapticPatterns.activationTimingsMs())
        assertTrue(TorchHapticPatterns.ACTIVATION_GAP_MS > 0L)
    }
}
