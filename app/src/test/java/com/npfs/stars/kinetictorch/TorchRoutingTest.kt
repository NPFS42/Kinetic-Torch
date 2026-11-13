package com.npfs.stars.kinetictorch

import org.junit.Assert.assertEquals
import org.junit.Test

class TorchRoutingTest {
    @Test fun hardwareFlashUsesOnlyTheLed() {
        assertEquals(TorchRouting.Route.HARDWARE_LED, TorchRouting.select(hasHardwareFlash = true))
    }

    @Test fun noFlashCannotOpenTheRetiredScreenTorch() {
        assertEquals(TorchRouting.Route.UNAVAILABLE, TorchRouting.select(hasHardwareFlash = false))
    }
}
