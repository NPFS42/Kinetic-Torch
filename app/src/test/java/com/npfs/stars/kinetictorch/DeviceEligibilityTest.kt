package com.npfs.stars.kinetictorch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEligibilityTest {
    @Test fun manufacturerAndRetailBrandDetection() {
        assertTrue(DeviceEligibility.isMotorola("motorola", "motorola"))
        assertTrue(DeviceEligibility.isMotorola("MOTOROLA", "unknown"))
        assertTrue(DeviceEligibility.isMotorola("unknown", "moto"))
        assertTrue(DeviceEligibility.isMotorola(" unknown ", " MOTOROLA "))
        assertFalse(DeviceEligibility.isMotorola("Google", "google"))
        assertFalse(DeviceEligibility.isMotorola("Samsung", "samsung"))
        assertFalse(DeviceEligibility.isMotorola("LENOVO", "lenovo"))
    }

    @Test fun defaultsFitSliderRanges() {
        assertTrue(TorchDefaults.FORCE_THRESHOLD in 10.0f..35.0f)
        assertTrue(TorchDefaults.CHOP_WINDOW_MS in 300..1000)
        assertTrue(TorchDefaults.AUTO_OFF_MINUTES in 0..30)
        assertTrue(TorchDefaults.WAKE_MODE in 0..5)
        assertEquals(800, TorchDefaults.CHOP_WINDOW_MS)
    }
}
