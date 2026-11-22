package com.npfs.stars.kinetictorch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VibrationGuardTest {
    @Test
    fun turningOffGestureBlockingClearsHoldAndAllowsDetectionOnlyMode() {
        val guard = VibrationGuard()
        for (t in 0L..600L step 20L) {
            guard.observe(0f, 0f, if ((t / 20L) % 2L == 0L) 2.5f else -2.5f, t)
        }
        assertTrue(guard.isBlocking(600L))
        guard.configure(detectShaking = true, blockGestures = false)
        assertFalse(guard.isBlocking(601L))
        assertFalse(guard.observe(0f, 0f, 0f, 601L)) // stale hold was cleared

        var detected = false
        for (t in 700L..1_300L step 20L) {
            detected = guard.observe(0f, 0f, if ((t / 20L) % 2L == 0L) 2.5f else -2.5f, t)
        }
        assertTrue(detected) // detector keeps reporting for diagnostics
        assertFalse(guard.isBlocking(1_300L))
        guard.configure(detectShaking = true, blockGestures = true)
        assertFalse(guard.isBlocking(1_301L)) // re-enabling starts a fresh pattern
    }

    @Test
    fun disablingDetectionClearsGuardAndLeavesChopDetectorAvailable() {
        val guard = VibrationGuard()
        for (t in 0L..600L step 20L) {
            guard.observe(0f, 0f, if ((t / 20L) % 2L == 0L) 2.5f else -2.5f, t)
        }
        assertTrue(guard.isBlocking(600L))
        guard.configure(detectShaking = false, blockGestures = true)
        assertFalse(guard.isBlocking(601L))
        for (t in 700L..1_300L step 20L) {
            assertFalse(guard.observe(0f, 0f, if ((t / 20L) % 2L == 0L) 2.5f else -2.5f, t))
        }
        guard.configure(detectShaking = true, blockGestures = true)
        assertFalse(guard.isBlocking(1_301L))
    }

    @Test
    fun roadShakingArmsThirtySecondSuppression() {
        val guard = VibrationGuard()
        var suppressed = false
        for (t in 0L..600L step 20L) {
            suppressed = guard.observe(0f, 0f, if ((t / 20L) % 2L == 0L) 2.5f else -2.5f, t)
        }
        assertTrue(suppressed)
        assertTrue(guard.remainingMs(600L) >= 29_000L)
    }

    @Test
    fun bassOscillationsBelowChopThresholdStillActivateGuard() {
        val guard = VibrationGuard()
        var suppressed = false
        // A vibrating phone mount sampling a 35 Hz bass vibration at 100 Hz.
        for (t in 0L..1_000L step 10L) {
            val wave = (1.8 * sin(2 * PI * 35 * t / 1_000)).toFloat()
            suppressed = guard.observe(0f, wave, 0f, t)
        }
        assertTrue(suppressed)
    }

    @Test
    fun deliberateDoubleChopNeverArmsVibrationGuard() {
        val guard = VibrationGuard()
        val detector = ChopDetector(forceThreshold = 17f, chopWindowMs = 579L)
        val samples = listOf(
            0L to 19f, 40L to 0f, 80L to -7f, 120L to 0f,
            200L to 0f, 300L to 19f
        )
        var accepted = false
        samples.forEach { (time, acceleration) ->
            assertFalse(guard.observe(acceleration, 0f, 0f, time))
            accepted = detector.onLinearAcceleration(acceleration, 0f, 0f, time)
        }
        assertTrue(accepted)
    }

    @Test
    fun ownConfirmationHapticsDoNotCreateFalseBassLockout() {
        val guard = VibrationGuard()
        guard.ignoreOwnHaptic(nowMs = 0L, durationMs = 770L)
        for (t in 0L..760L step 10L) {
            assertTrue(guard.isIgnoringOwnHaptic(t))
            assertFalse(guard.observe(if ((t / 10L) % 2L == 0L) 3f else -3f, 0f, 0f, t))
        }
        assertFalse(guard.isIgnoringOwnHaptic(770L))
        assertFalse(guard.observe(0f, 0f, 0f, 770L))

        // Genuine external vibration afterward can still arm the guard normally.
        var suppressed = false
        for (t in 800L..1_100L step 20L) {
            suppressed = guard.observe(if ((t / 20L) % 2L == 0L) 2f else -2f, 0f, 0f, t)
        }
        assertTrue(suppressed)
    }

    @Test
    fun eachNewVibrationEpisodeRestartsThirtySecondCountdown() {
        val guard = VibrationGuard()
        for (t in 0L..300L step 20L) {
            guard.observe(if ((t / 20L) % 2L == 0L) 3f else -3f, 0f, 0f, t)
        }
        assertTrue(guard.remainingMs(30_000L) > 0L)

        // New sustained vibration during the hold renews it; no 30s timer from the first event.
        for (t in 29_000L..29_300L step 20L) {
            guard.observe(if ((t / 20L) % 2L == 0L) 3f else -3f, 0f, 0f, t)
        }
        assertTrue(guard.remainingMs(30_500L) > 28_000L)
        assertTrue(guard.observe(0f, 0f, 0f, 59_100L))
        assertFalse(guard.observe(0f, 0f, 0f, 59_301L))
    }

    @Test
    fun resetsGesturePatternButPreservesSuppressionWhileSensorsSleep() {
        val guard = VibrationGuard()
        for (t in 0L..260L step 20L) {
            guard.observe(0f, if ((t / 20L) % 2L == 0L) 2f else -2f, 0f, t)
        }
        guard.resetPattern()
        assertTrue(guard.observe(0f, 0f, 0f, 10_000L))
        assertFalse(guard.observe(0f, 0f, 0f, 30_261L))
    }

    @Test
    fun normalChopWorksAgainAfterThirtyQuietSeconds() {
        val guard = VibrationGuard()
        val detector = ChopDetector(forceThreshold = 17f, chopWindowMs = 579L)
        for (t in 0L..300L step 20L) {
            val x = if ((t / 20L) % 2L == 0L) 3f else -3f
            if (guard.observe(x, 0f, 0f, t)) detector.reset()
        }
        assertTrue(guard.observe(0f, 0f, 0f, 15_000L))
        detector.reset()
        assertFalse(guard.observe(0f, 0f, 0f, 30_301L))

        assertFalse(guard.observe(20f, 0f, 0f, 30_400L))
        assertFalse(detector.onLinearAcceleration(20f, 0f, 0f, 30_400L))
        assertFalse(guard.observe(0f, 0f, 0f, 30_500L))
        assertFalse(detector.onLinearAcceleration(0f, 0f, 0f, 30_500L))
        assertFalse(guard.observe(20f, 0f, 0f, 30_700L))
        assertTrue(detector.onLinearAcceleration(20f, 0f, 0f, 30_700L))
    }

    @Test
    fun isolatedImpulsesAndSteadyMotionDoNotTriggerGuard() {
        val guard = VibrationGuard()
        for (t in 0L..4_000L step 20L) {
            val force = when (t) { 0L, 1_500L, 3_000L -> 25f; else -> 2f }
            assertFalse(guard.observe(force, 0f, 0f, t))
        }
    }
}
