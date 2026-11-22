package com.npfs.stars.kinetictorch

import kotlin.math.abs

/**
 * Suppresses chop gestures after sustained, rapidly alternating motion, such as washboard-road
 * shaking or a bass-heavy speaker vibrating the phone/mount. This measures the accelerometer,
 * not audio playback or microphone input: bass can only be detected when it physically shakes
 * the handset enough to cross [MIN_COMPONENT_ACCELERATION].
 *
 * Inputs must be gravity-free linear acceleration and monotonic elapsed-realtime milliseconds.
 * Repeated opposite-direction excursions on any axis count as vibration. A normal double-chop
 * contains too few reversals to arm the guard. Once armed, further vibration continually extends
 * the hold to [SUPPRESSION_MS] after the *latest* qualifying reversal pattern.
 *
 * No Android dependencies: the same logic runs in JVM regression tests.
 */
internal class VibrationGuard {
    companion object {
        const val SUPPRESSION_MS = 30_000L

        /** Deliberately below chop sensitivity; strong bass need not reach chop force. */
        const val MIN_COMPONENT_ACCELERATION = 0.55f // m/s²
        const val PATTERN_WINDOW_MS = 1_200L
        const val REQUIRED_REVERSALS = 7
        private const val MIN_PATTERN_SPAN_MS = 100L
        private const val MAX_AXIS_REVERSAL_GAP_MS = 180L
        private const val MIN_REVERSAL_GAP_MS = 8L
        private const val MAX_SAMPLE_GAP_MS = 250L
        private const val FILTER_TIME_CONSTANT_MS = 90f
    }

    private val slowComponent = FloatArray(3)
    private val lastExcursionSign = IntArray(3)
    private val lastExcursionAtMs = LongArray(3) { -1L }
    private val reversals = ArrayDeque<Long>()
    private var lastSampleAtMs = -1L
    private var lastReversalAtMs = -1L
    private var suppressionUntilMs = -1L
    private var ignoreOwnHapticUntilMs = -1L

    var detectionEnabled = true
        private set
    var blockingEnabled = true
        private set

    /**
     * User-facing switches are independent. Detection may remain active for diagnostics
     * while gesture blocking is off. Changing either clears an old rolling suppression
     * window, so restoring protection requires fresh sustained vibration.
     */
    fun configure(detectShaking: Boolean, blockGestures: Boolean) {
        if (detectionEnabled == detectShaking && blockingEnabled == blockGestures) return
        detectionEnabled = detectShaking
        blockingEnabled = blockGestures
        suppressionUntilMs = -1L
        resetPattern()
    }

    fun isBlocking(nowMs: Long): Boolean =
        detectionEnabled && blockingEnabled && remainingMs(nowMs) > 0L

    /** Clear the pattern on sensor re-registration, but retain a still-running safety hold. */
    fun resetPattern() {
        slowComponent.fill(0f)
        lastExcursionSign.fill(0)
        lastExcursionAtMs.fill(-1L)
        reversals.clear()
        lastSampleAtMs = -1L
        lastReversalAtMs = -1L
    }

    fun remainingMs(nowMs: Long): Long = (suppressionUntilMs - nowMs).coerceAtLeast(0L)

    /** Our own confirmation buzz must not masquerade as external road or bass vibration. */
    fun ignoreOwnHaptic(nowMs: Long, durationMs: Long) {
        resetPattern()
        ignoreOwnHapticUntilMs = maxOf(ignoreOwnHapticUntilMs, nowMs + durationMs)
    }

    fun isIgnoringOwnHaptic(nowMs: Long): Boolean = nowMs < ignoreOwnHapticUntilMs

    /** Returns true while a gesture is blocked, including a hold extended by this sample. */
    fun observe(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (!detectionEnabled) return false
        if (isIgnoringOwnHaptic(nowMs)) return remainingMs(nowMs) > 0L
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || nowMs < 0L) {
            return remainingMs(nowMs) > 0L
        }

        if (lastSampleAtMs >= 0L && (nowMs < lastSampleAtMs ||
                    nowMs - lastSampleAtMs > MAX_SAMPLE_GAP_MS)) {
            resetPattern() // stale or out-of-order samples must not fabricate a pattern
        }

        val deltaMs = if (lastSampleAtMs < 0L) 0L else nowMs - lastSampleAtMs
        val blend = if (deltaMs == 0L) 1f else
            deltaMs.toFloat() / (FILTER_TIME_CONSTANT_MS + deltaMs)
        var reversed = false
        for (axis in 0..2) {
            val highFrequency = (when (axis) { 0 -> x; 1 -> y; else -> z }) - slowComponent[axis]
            slowComponent[axis] += blend * highFrequency

            if (abs(highFrequency) < MIN_COMPONENT_ACCELERATION) continue
            val sign = if (highFrequency > 0f) 1 else -1
            val previousAt = lastExcursionAtMs[axis]
            if (lastExcursionSign[axis] == -sign && previousAt >= 0L &&
                nowMs - previousAt <= MAX_AXIS_REVERSAL_GAP_MS) {
                reversed = true
            }
            lastExcursionSign[axis] = sign
            lastExcursionAtMs[axis] = nowMs
        }
        lastSampleAtMs = nowMs

        while (reversals.isNotEmpty() && nowMs - reversals.first() > PATTERN_WINDOW_MS) {
            reversals.removeFirst()
        }

        if (reversed && (lastReversalAtMs < 0L ||
                    nowMs - lastReversalAtMs >= MIN_REVERSAL_GAP_MS)) {
            reversals.addLast(nowMs)
            lastReversalAtMs = nowMs
            if (reversals.size >= REQUIRED_REVERSALS &&
                nowMs - reversals.first() >= MIN_PATTERN_SPAN_MS) {
                suppressionUntilMs = nowMs + SUPPRESSION_MS // new reversal renews the rolling hold
            }
        }
        return remainingMs(nowMs) > 0L
    }
}
