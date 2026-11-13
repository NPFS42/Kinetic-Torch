package com.npfs.stars.kinetictorch

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build

/** Runtime safety net; store distribution exclusions are configured separately in Play Console. */
internal object DeviceEligibility {
    fun isMotorola(manufacturer: String, brand: String): Boolean =
        manufacturer.trim().equals("motorola", ignoreCase = true) ||
            brand.trim().equals("motorola", ignoreCase = true) ||
            brand.trim().equals("moto", ignoreCase = true)

    fun isMotorolaDevice(): Boolean = isMotorola(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())

    /** White-screen torch is retired: only real camera flash LEDs can be armed. */
    fun hasHardwareFlash(context: Context): Boolean = runCatching {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        ChopDetectionService.findFlashCameraId(cameraManager) != null
    }.getOrDefault(false)
}
