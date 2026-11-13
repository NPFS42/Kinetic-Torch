package com.npfs.stars.kinetictorch

/** Active torch routing is intentionally LED-only. White-screen mode is deprecated. */
internal object TorchRouting {
    enum class Route { HARDWARE_LED, UNAVAILABLE }

    fun select(hasHardwareFlash: Boolean): Route =
        if (hasHardwareFlash) Route.HARDWARE_LED else Route.UNAVAILABLE
}
