package com.npfs.stars.kinetictorch

import android.os.Build

/** Runtime safety net; store distribution exclusions are configured separately in Play Console. */
internal object DeviceEligibility {
    fun isMotorola(manufacturer: String, brand: String): Boolean =
        manufacturer.trim().equals("motorola", ignoreCase = true) ||
            brand.trim().equals("motorola", ignoreCase = true) ||
            brand.trim().equals("moto", ignoreCase = true)

    fun isMotorolaDevice(): Boolean = isMotorola(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())
}
