package com.npfs.stars.kinetictorch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChopDetectorTest {

    private lateinit var detector: ChopDetector

    @Before
    fun setUp() {
        detector = ChopDetector(forceThreshold = 18.5f, chopWindowMs = 650L, cooldownMs = 1000L)
    }

    /** Feeds an in-plane sample; returns whether a double-chop was just recognised. */
    private fun planar(t: Long, force: Float) = detector.onLinearAcceleration(force, 0f, 0f, t)

    /** Quiet samples every 20 ms (50 Hz) over [from, to]; asserts none of them trigger. */
    private fun quiet(from: Long, to: Long) {
        var t = from
        while (t <= to) {
            assertFalse(planar(t, 0.5f))
            t += 20
        }
    }

    @Test
    fun doubleChopTriggers() {
        assertFalse(planar(0, 25f))
        quiet(20, 200)
        assertTrue(planar(300, 25f))
    }

    @Test
    fun singleChopDoesNotTrigger() {
        assertFalse(planar(0, 25f))
        quiet(20, 1500)
    }

    @Test
    fun secondChopAfterWindowDoesNotTrigger() {
        assertFalse(planar(0, 25f))
        quiet(20, 780)
        // 800 ms > 650 ms window: the gesture restarts, so this is only a new first chop
        assertFalse(planar(800, 25f))
    }

    @Test
    fun secondSpikeTooSoonIsTreatedAsSameChop() {
        assertFalse(planar(0, 25f))
        quiet(20, 80)
        assertFalse(planar(100, 25f)) // 100 ms < MIN_SECOND_CHOP_GAP_MS
    }

    @Test
    fun zAxisPokesAreIgnored() {
        assertFalse(detector.onLinearAcceleration(0f, 0f, 30f, 0))
        quiet(20, 280)
        assertFalse(detector.onLinearAcceleration(0f, 0f, 30f, 300))
    }

    @Test
    fun sustainedShakingWithoutValleyDoesNotTrigger() {
        var t = 0L
        while (t <= 2000) {
            assertFalse(planar(t, 25f))
            t += 20
        }
    }

    @Test
    fun cooldownSuppressesImmediateRetrigger() {
        assertFalse(planar(0, 25f))
        quiet(20, 200)
        assertTrue(planar(300, 25f))

        // Complete second double-chop entirely inside the 1000 ms cooldown
        assertFalse(planar(400, 25f))
        assertFalse(planar(500, 0.5f))
        assertFalse(planar(700, 25f))

        // After the cooldown a fresh double-chop works again
        assertFalse(planar(1400, 25f))
        assertFalse(planar(1500, 0.5f))
        assertTrue(planar(1800, 25f))
    }

    @Test
    fun thresholdChangeAppliesLive() {
        detector.forceThreshold = 30f
        assertFalse(planar(0, 25f))
        quiet(20, 200)
        assertFalse(planar(300, 25f))
    }

    @Test
    fun resetClearsInProgressGesture() {
        assertFalse(planar(0, 25f))
        quiet(20, 200)
        detector.reset()
        assertFalse(planar(300, 25f)) // treated as a new first chop, not the second
    }

    @Test
    fun chopPlaneHelper() {
        assertTrue(ChopDetector.isChopPlane(10f, 0f, 5f))
        assertFalse(ChopDetector.isChopPlane(1f, 1f, 30f))
        assertEquals(5f, ChopDetector.magnitude(3f, 4f, 0f), 1e-4f)
    }
}

class GravityHighPassTest {

    @Test
    fun staticGravityIsRemovedAndWarmupIsHonoured() {
        val filter = GravityHighPass()
        val out = FloatArray(3)
        var t = 0L
        var settled = false
        while (t <= 1000) {
            settled = filter.filter(0f, 0f, 9.81f, t, out)
            assertEquals(0f, out[2], 0.01f)
            if (t < 400) assertFalse(settled)
            t += 20
        }
        assertTrue(settled)
    }

    @Test
    fun suddenImpulsePassesThrough() {
        val filter = GravityHighPass()
        val out = FloatArray(3)
        var t = 0L
        while (t < 600) { filter.filter(0f, 0f, 9.81f, t, out); t += 20 }
        filter.filter(25f, 0f, 9.81f, t, out)
        assertTrue(out[0] > 20f)
    }

    @Test
    fun resetRestartsWarmup() {
        val filter = GravityHighPass()
        val out = FloatArray(3)
        var t = 0L
        while (t < 600) { filter.filter(0f, 0f, 9.81f, t, out); t += 20 }
        filter.reset()
        assertFalse(filter.filter(0f, 0f, 9.81f, t, out))
    }
}

