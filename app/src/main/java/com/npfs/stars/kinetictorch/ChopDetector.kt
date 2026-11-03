package com.npfs.stars.kinetictorch

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Double-chop gesture state machine.
 *
 * Input must be GRAVITY-FREE (linear) acceleration in m/s² — either from
 * Sensor.TYPE_LINEAR_ACCELERATION or from [GravityHighPass] applied to the raw accelerometer.
 * Deliberately has no Android imports so it can be unit-tested on the plain JVM.
 *
 * Gesture: spike >= threshold -> quiet valley (< threshold * 0.45) -> second spike >= threshold,
 * with the second spike at least [MIN_SECOND_CHOP_GAP_MS] and at most [chopWindowMs] after the first.
 */
class ChopDetector(
    var forceThreshold: Float = 18.5f,
    var chopWindowMs: Long = 650L,
    var cooldownMs: Long = 1000L
) {
    companion object {
        /** Valley must drop below this fraction of the threshold between the two chops. */
        const val VALLEY_RATIO = 0.45f

        /**
         * A single physical chop produces an accelerate spike and a decelerate spike ~100-150 ms
         * apart. Requiring >= 200 ms between the two counted spikes stops one chop being read as two.
         */
        const val MIN_SECOND_CHOP_GAP_MS = 200L

        /** In-plane (x/y) motion must be at least this fraction of z motion to count as a chop. */
        const val PLANE_RATIO = 0.7f

        private const val NONE = -1L

        fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

        /** Chop motion is mostly in the screen plane; pokes/taps along z are ignored. */
        fun isChopPlane(x: Float, y: Float, z: Float): Boolean =
            (abs(x) + abs(y)) >= (abs(z) * PLANE_RATIO)
    }

    private var firstChopMs = NONE
    private var lastToggleMs = NONE
    private var awaitingValley = false

    /** Clears an in-progress gesture. The post-toggle cooldown is intentionally kept. */
    fun reset() {
        firstChopMs = NONE
        awaitingValley = false
    }

    /**
     * Feed one gravity-free sample. [nowMs] must be monotonic (SystemClock.elapsedRealtime()).
     * Returns true exactly once when a complete double-chop has just been recognised.
     */
    data class Trace(
        val reason: String, val before: String, val after: String,
        val nowMs: Long, val force: Float, val inPlane: Boolean,
        val firstChopMs: Long, val lastToggleMs: Long,
        val threshold: Float, val windowMs: Long, val cooldownRemainingMs: Long,
        val windowExpired: Boolean, val gestureAgeMs: Long?
    )

    /** Optional observer; logging exceptions must not alter gesture recognition. */
    var traceListener: ((Trace) -> Unit)? = null
    private fun phase() = when {
        firstChopMs == NONE -> "WAITING_FIRST_CHOP"
        awaitingValley -> "WAITING_VALLEY"
        else -> "WAITING_SECOND_CHOP"
    }

    fun onLinearAcceleration(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        val before = phase()
        val force = magnitude(x, y, z)
        val inPlane = isChopPlane(x, y, z)
        val remaining = if (lastToggleMs == NONE) 0L else
            (cooldownMs - (nowMs - lastToggleMs)).coerceAtLeast(0L)
        val gestureAgeMs = if (firstChopMs == NONE) null else nowMs - firstChopMs
        var expired = false
        fun result(reason: String, accepted: Boolean = false): Boolean {
            traceListener?.let { listener ->
                try {
                    listener(Trace(reason, before, phase(), nowMs, force, inPlane,
                        firstChopMs, lastToggleMs, forceThreshold, chopWindowMs, remaining, expired, gestureAgeMs))
                } catch (_: Exception) { /* An observer must never change torch behavior. */ }
            }
            return accepted
        }
        if (lastToggleMs != NONE && nowMs - lastToggleMs < cooldownMs)
            return result("COOLDOWN_ACTIVE")
        if (firstChopMs != NONE && nowMs - firstChopMs > chopWindowMs) {
            expired = true
            reset()
        }
        when {
            firstChopMs == NONE -> {
                if (!inPlane) return result("WRONG_MOTION_PLANE")
                if (force < forceThreshold) return result("BELOW_FORCE_THRESHOLD")
                firstChopMs = nowMs
                awaitingValley = true
                return result("FIRST_CHOP_RECORDED")
            }
            awaitingValley -> {
                if (force < forceThreshold * VALLEY_RATIO) {
                    awaitingValley = false
                    return result("VALLEY_RECORDED")
                }
                return result("WAITING_FOR_QUIET_VALLEY")
            }
            else -> {
                if (!inPlane) return result("WRONG_MOTION_PLANE")
                if (force < forceThreshold) return result("WAITING_SECOND_CHOP_BELOW_THRESHOLD")
                if (nowMs - firstChopMs >= MIN_SECOND_CHOP_GAP_MS) {
                    lastToggleMs = nowMs
                    reset()
                    return result("DOUBLE_CHOP_ACCEPTED", true)
                }
                awaitingValley = true
                return result("SECOND_SPIKE_TOO_SOON_RECOIL")
            }
        }
    }
}

/**
 * First-order high-pass filter that strips gravity from raw accelerometer samples. Used only when
 * the device has no TYPE_LINEAR_ACCELERATION sensor. Ignores the first [warmupMs] after a reset so
 * the gravity estimate can settle instead of producing a bogus spike.
 */
class GravityHighPass(
    private val alpha: Float = 0.9f,
    private val warmupMs: Long = 400L
) {
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var startMs = -1L

    fun reset() {
        startMs = -1L
    }

    /** Writes gravity-free x/y/z into [out]; returns false while the filter is still settling. */
    fun filter(x: Float, y: Float, z: Float, nowMs: Long, out: FloatArray): Boolean {
        if (startMs < 0L) {
            startMs = nowMs
            gx = x; gy = y; gz = z
        }
        gx = alpha * gx + (1f - alpha) * x
        gy = alpha * gy + (1f - alpha) * y
        gz = alpha * gz + (1f - alpha) * z
        out[0] = x - gx
        out[1] = y - gy
        out[2] = z - gz
        return nowMs - startMs >= warmupMs
    }
}

