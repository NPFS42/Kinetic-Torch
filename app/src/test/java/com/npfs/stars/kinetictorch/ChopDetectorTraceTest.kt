package com.npfs.stars.kinetictorch

import org.junit.Assert.*
import org.junit.Test

class ChopDetectorTraceTest {
    @Test fun explainsAcceptanceAndRecoilAndCooldown() {
        val traces = mutableListOf<ChopDetector.Trace>()
        val detector = ChopDetector().apply { traceListener = { traces.add(it) } }
        fun sample(t: Long, force: Float) = detector.onLinearAcceleration(force, 0f, 0f, t)
        assertFalse(sample(0, 25f))
        assertEquals("FIRST_CHOP_RECORDED", traces.last().reason)
        assertFalse(sample(20, .5f))
        assertEquals("VALLEY_RECORDED", traces.last().reason)
        assertFalse(sample(100, 25f))
        assertEquals("SECOND_SPIKE_TOO_SOON_RECOIL", traces.last().reason)
        assertFalse(sample(140, .5f))
        assertTrue(sample(300, 25f))
        assertEquals("DOUBLE_CHOP_ACCEPTED", traces.last().reason)
        assertEquals(300L, traces.last().gestureAgeMs ?: -1L)
        assertFalse(sample(400, 25f))
        assertEquals("COOLDOWN_ACTIVE", traces.last().reason)
        assertEquals(900L, traces.last().cooldownRemainingMs)
    }

    @Test fun explainsThresholdPlaneValleyAndExpiredWindow() {
        val traces = mutableListOf<ChopDetector.Trace>()
        val detector = ChopDetector().apply { traceListener = { traces.add(it) } }
        detector.onLinearAcceleration(1f, 0f, 0f, 0)
        assertEquals("BELOW_FORCE_THRESHOLD", traces.last().reason)
        detector.onLinearAcceleration(0f, 0f, 30f, 20)
        assertEquals("WRONG_MOTION_PLANE", traces.last().reason)
        detector.onLinearAcceleration(25f, 0f, 0f, 40)
        detector.onLinearAcceleration(25f, 0f, 0f, 60)
        assertEquals("WAITING_FOR_QUIET_VALLEY", traces.last().reason)
        assertFalse(detector.onLinearAcceleration(.5f, 0f, 0f, 800))
        assertTrue(traces.last().windowExpired)
        assertEquals("BELOW_FORCE_THRESHOLD", traces.last().reason)
        assertEquals("WAITING_FIRST_CHOP", traces.last().after)
    }

    @Test fun observerFailureCannotBlockAcceptedGesture() {
        val detector = ChopDetector().apply { traceListener = { error("storage failed") } }
        assertFalse(detector.onLinearAcceleration(25f, 0f, 0f, 0))
        assertFalse(detector.onLinearAcceleration(.5f, 0f, 0f, 100))
        assertTrue(detector.onLinearAcceleration(25f, 0f, 0f, 300))
    }

    @Test fun loggingDoesNotChangeExistingDetectorResults() {
        val plain = ChopDetector()
        val traced = ChopDetector().apply { traceListener = {} }
        val random = java.util.Random(42)
        for (t in 0L..20_000L step 20L) {
            val x = random.nextFloat() * 60f - 30f
            val y = random.nextFloat() * 60f - 30f
            val z = random.nextFloat() * 60f - 30f
            assertEquals(plain.onLinearAcceleration(x, y, z, t),
                traced.onLinearAcceleration(x, y, z, t))
        }
    }
}
