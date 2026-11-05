package com.npfs.stars.kinetictorch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TorchHapticPatternsTest {
    @Test fun activationIsTwoPulsesWithPauseAndNoLeadingDelay() {
        assertArrayEquals(longArrayOf(0L, 240L, 90L, 240L), TorchHapticPatterns.activationTimingsMs())
    }

    @Test fun deactivationIsExactlyOneStrong220msWaveformPulse() {
        assertEquals(220L, TorchHapticPatterns.DEACTIVATION_PULSE_MS)
        assertArrayEquals(longArrayOf(0L, 220L), TorchHapticPatterns.deactivationTimingsMs())
        assertArrayEquals(intArrayOf(0, 255), TorchHapticPatterns.deactivationAmplitudes())
        assertEquals(140L, TorchHapticPatterns.CONFIRMED_OFF_HAPTIC_DELAY_MS)
    }

    @Test fun confirmedOffHapticRejectsStaleOrReactivatedLight() {
        assertTrue(TorchHapticPatterns.shouldDeliverConfirmedOff(false, false, 4L, 4L))
        assertTrue(!TorchHapticPatterns.shouldDeliverConfirmedOff(true, false, 4L, 4L))
        assertTrue(!TorchHapticPatterns.shouldDeliverConfirmedOff(null, false, 4L, 4L))
        assertTrue(!TorchHapticPatterns.shouldDeliverConfirmedOff(false, true, 4L, 4L))
        assertTrue(!TorchHapticPatterns.shouldDeliverConfirmedOff(false, false, 4L, 5L))
    }

    @Test fun onPatternIsStillTheOriginalWorkingTwoPulses() {
        assertArrayEquals(longArrayOf(0L, 240L, 90L, 240L), TorchHapticPatterns.activationTimingsMs())
        assertTrue(TorchHapticPatterns.ACTIVATION_GAP_MS > 0L)
    }
}
