package com.npfs.stars.kinetictorch

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureTuningTest {
    @Test fun lucasFastShakeIsRepresentableWithoutRoundingAwaySettings() {
        val lucas = GestureTuning(5.0f, 451, 10, 1)
        assertEquals(lucas, lucas.normalized())
    }

    @Test fun restoredSnapshotsAreClampedToActualUiRanges() {
        val tuning = GestureTuning(2.4f, 1200, -3, 8).normalized()
        assertEquals(5.0f, tuning.force, 0.0001f)
        assertEquals(1000, tuning.windowMs)
        assertEquals(0, tuning.autoOffMinutes)
        assertEquals(5, tuning.wakeMode)
    }

    @Test fun defaultTuningRemainsAtOwnerTestedValues() {
        assertEquals(GestureTuning(12.0f, 750, 10, 2), GestureTuning.defaults())
    }
}
