package com.npfs.stars.kinetictorch

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class ChopDetectionService : Service(), SensorEventListener {

    /**
     * Explicitly tracks which light source is currently active so fallback from a busy/missing
     * hardware LED to the 100% White Screen Torch always routes subsequent OFF commands to the
     * matching implementation.
     */
    enum class ActiveLightSource {
        NONE,
        HARDWARE_LED,
        SCREEN_WHITE
    }

    companion object {
        const val PREFS_NAME = "KineticTorchPrefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_USE_SCREEN_TORCH = "use_screen_torch"     // Full-screen 100% white mode
        const val KEY_FORCE_THRESHOLD = "force_threshold"       // Default from TorchDefaults (10.0..35.0)
        const val KEY_CHOP_WINDOW_MS = "chop_window_ms"         // Default from TorchDefaults (300..1000)
        const val KEY_AUTO_OFF_MINUTES = "auto_off_minutes"     // Default: 10 min (0 = Disabled, 1..30)
        const val KEY_WAKE_MODE = "wake_mode"                   // 0=ScreenOn, 1=15s, 2=30s(Def), 3=2m, 4=10m, 5=AlwaysOn 24/7
        const val KEY_COOLDOWN_MS = "cooldown_ms"               // Default: 1000 ms
        const val KEY_FSI_PROMPTED = "fsi_prompted"             // Full-screen-intent settings page already shown once
        const val ACTION_STOP_AND_OFF = "com.npfs.stars.kinetictorch.ACTION_STOP_AND_OFF"

        const val CHANNEL_ID = "stars_kinetic_torch_channel"
        const val SCREEN_TORCH_ALERT_CHANNEL_ID = "stars_screen_torch_fullscreen_channel"
        const val NOTIFICATION_ID = 1001
        const val SCREEN_TORCH_NOTIFICATION_ID = 1002

        // How long to wait before concluding Android blocked the screen-torch activity launch
        private const val SCREEN_TORCH_LAUNCH_CHECK_MS = 1_500L

        // Proximity readings below this (and below the sensor's max range) count as "covered"
        private const val PROXIMITY_NEAR_CM = 5.0f

        // Post-screen-off listening durations in milliseconds for modes 0..4 (mode 5 = infinite/24-7)
        val WAKE_WINDOW_MS_MAP = longArrayOf(
            0L,           // 0: Screen-On Only (0s after lock)
            15_000L,      // 1: Screen-Wake + 15s Window
            30_000L,      // 2: Screen-Wake + 30s Window (Recommended Default)
            120_000L,     // 3: Screen-Wake + 2 min Window
            600_000L,     // 4: Screen-Wake + 10 min Window
            -1L           // 5: Always-On 24/7 (Continuous Wake Lock)
        )

        /**
         * Strictly finds a camera with FLASH_INFO_AVAILABLE == true (preferring rear-facing).
         * Never falls back to a flashless camera ID. Returns null if the device has no LED flash.
         */
        fun findFlashCameraId(cameraManager: CameraManager): String? {
            var fallbackFlashId: String? = null
            try {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    if (!hasFlash) continue

                    val isRearFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                    if (isRearFacing) {
                        return id
                    }
                    if (fallbackFlashId == null) {
                        fallbackFlashId = id
                    }
                }
            } catch (_: Exception) {}
            return fallbackFlashId
        }
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var prefs: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var flashCameraId: String? = null
    private var hasProximityHardware = false
    @Volatile
    private var activeLightSource: ActiveLightSource = ActiveLightSource.NONE

    // True only when WE switched the LED on. A torch turned on elsewhere (system tile, camera app)
    // can still be switched off by a double-chop, but we never auto-shut-off or force-kill it.
    private var hardwareLedOwnedByUs = false
    private var isPocketBlocked = false
    private var sensorsActive = false

    // Dynamic sensitivity, power & safety parameters updated live via SharedPreferences listener
    private var useScreenTorch = false
    private var autoOffMinutes = 10
    private var wakeMode = 2

    // Gesture recognition (monotonic SystemClock.elapsedRealtime timestamps) lives in ChopDetector;
    // threshold / window / cooldown are pushed into it from prefs.
    private var lastMotionElapsedMs: Long? = null
    private val diagnosticHeartbeat = object : Runnable {
        override fun run() {
            trace("service_heartbeat", "SERVICE_ALIVE", "last_motion_elapsed_ms" to lastMotionElapsedMs,
                "motion_age_ms" to lastMotionElapsedMs?.let { SystemClock.elapsedRealtime() - it },
                "pending_led_request_id" to pendingLedRequest,
                "log_storage_error" to DebugLog.storageError)
            mainHandler.postDelayed(this, 2000L)
        }
    }
    private var gestureId = 0L
    private var lastDetectorReason = ""
    private var ledRequestId = 0L
    private var pendingLedRequest: Long? = null
    private var pendingLedTarget: Boolean? = null
    private var confirmedLedState: Boolean? = null
    private fun trace(name: String, reason: String, vararg fields: Pair<String, Any?>) {
        DebugLog.event(name, "reason" to reason, "gesture_id" to gestureId,
            "active_light" to activeLightSource, "led_owned" to hardwareLedOwnedByUs,
            "screen_visible" to ScreenTorchActivity.isScreenTorchVisible,
            "pocket_blocked" to isPocketBlocked, "sensors_active" to sensorsActive,
            "interactive" to if (::powerManager.isInitialized) powerManager.isInteractive else null,
            "locked" to if (::keyguardManager.isInitialized) keyguardManager.isKeyguardLocked else null,
            "wake_held" to wakeLock?.isHeld, "wake_mode" to wakeMode,
            "confirmed_led" to confirmedLedState, *fields)
    }
    private val detector = ChopDetector()
    private val gravityFilter = GravityHighPass()
    private val linearBuffer = FloatArray(3)

    /** Any active light, including an LED someone else switched on (used for toggle/off logic). */
    private fun isAnyTorchActive(): Boolean {
        return activeLightSource != ActiveLightSource.NONE || ScreenTorchActivity.isScreenTorchVisible
    }

    /** Light that WE are responsible for: drives auto-shutoff and keeps sensors/wake lock alive. */
    private fun isOwnedTorchActive(): Boolean {
        return activeLightSource == ActiveLightSource.SCREEN_WHITE ||
                ScreenTorchActivity.isScreenTorchVisible ||
                (activeLightSource == ActiveLightSource.HARDWARE_LED && hardwareLedOwnedByUs)
    }

    // Safety Auto-Shutoff Runnable
    private val autoShutoffRunnable = Runnable {
        trace("auto_off_timer", if (isOwnedTorchActive()) "OWNED_LIGHT_TIMEOUT" else "NO_OWNED_LIGHT_SKIP")
        if (isOwnedTorchActive()) {
            deactivateActiveTorch()
        }
    }

    // Screen-Off Sleep Window Runnable (for Wake Modes 0..4)
    private val sleepSensorsRunnable = Runnable {
        trace("sensor_sleep_timer", if (!isOwnedTorchActive() && wakeMode < 5 && !powerManager.isInteractive)
            "LISTENING_WINDOW_ENDED" else "KEEP_LISTENING")
        if (!isOwnedTorchActive() && wakeMode < 5 && !powerManager.isInteractive) {
            deactivateSensorsAndWakeLock()
        }
    }

    // Android 10+ can silently block a background startActivity(); the full-screen-intent alert is
    // then the only way in. If the torch screen never appeared, say so in the status notification.
    private val screenTorchLaunchCheck = Runnable {
        trace("screen_launch_check", if (ScreenTorchActivity.isScreenTorchVisible)
            "ACTIVITY_VISIBLE" else "ACTIVITY_NOT_VISIBLE_NO_OS_REASON_AVAILABLE")
        if (activeLightSource == ActiveLightSource.SCREEN_WHITE && !ScreenTorchActivity.isScreenTorchVisible) {
            updateNotificationStatus("Screen Torch blocked by Android • tap the alert to open it")
        }
    }

    private var lastStatusText = "Armed • Torch: OFF"

    // Screen State & ScreenTorch Dismissal Receiver
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            trace("system_broadcast", intent?.action ?: "NULL_ACTION")
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    mainHandler.removeCallbacks(sleepSensorsRunnable)
                    activateSensorsAndWakeLock()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    scheduleScreenOffWindow()
                }
                ScreenTorchActivity.ACTION_USER_DISMISSED_SCREEN_TORCH -> {
                    if (activeLightSource == ActiveLightSource.SCREEN_WHITE) {
                        activeLightSource = ActiveLightSource.NONE
                    }
                    mainHandler.removeCallbacks(autoShutoffRunnable)
                    mainHandler.removeCallbacks(screenTorchLaunchCheck)
                    updateNotificationStatus("Armed • Torch: OFF")
                    if (!powerManager.isInteractive) {
                        scheduleScreenOffWindow()
                    }
                }
            }
        }
    }

    /**
     * Authoritative hardware torch callback: reconciles activeLightSource whenever the OS,
     * another app, or our own request changes or revokes the physical camera LED state.
     */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            trace("led_callback", if (cameraId == flashCameraId) "OS_REPORTED_LED_STATE" else "OTHER_CAMERA_IGNORED",
                "camera_id" to cameraId, "enabled" to enabled,
                "pending_request_id" to pendingLedRequest, "requested_target" to pendingLedTarget)
            if (cameraId != flashCameraId) return
            confirmedLedState = enabled
            if (pendingLedRequest != null && pendingLedTarget == enabled) {
                trace("led_request_confirmed", "CALLBACK_MATCHED_REQUEST", "request_id" to pendingLedRequest,
                    "enabled" to enabled)
                pendingLedRequest = null
                pendingLedTarget = null
            }
            if (enabled) {
                if (activeLightSource == ActiveLightSource.NONE) {
                    // Switched on by the system flashlight / another app. Track it so a double-chop
                    // can turn it off, but we don't own it: no auto-shutoff, no forced kill on stop.
                    activeLightSource = ActiveLightSource.HARDWARE_LED
                    hardwareLedOwnedByUs = false
                    updateNotificationStatus("LED Torch: ON (turned on elsewhere)")
                }
            } else if (activeLightSource == ActiveLightSource.HARDWARE_LED) {
                activeLightSource = ActiveLightSource.NONE
                hardwareLedOwnedByUs = false
                mainHandler.removeCallbacks(autoShutoffRunnable)
                updateNotificationStatus("Armed • LED Torch: OFF")
                if (!powerManager.isInteractive) {
                    scheduleScreenOffWindow()
                }
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            trace("led_unavailable", "OS_CAMERA_RESOURCE_UNAVAILABLE", "camera_id" to cameraId,
                "pending_request_id" to pendingLedRequest)
            if (cameraId == flashCameraId) {
                confirmedLedState = null
                pendingLedRequest = null
                pendingLedTarget = null
            }
            if (cameraId == flashCameraId && activeLightSource == ActiveLightSource.HARDWARE_LED) {
                activeLightSource = ActiveLightSource.NONE
                hardwareLedOwnedByUs = false
                mainHandler.removeCallbacks(autoShutoffRunnable)
                updateNotificationStatus("Armed (LED released to Camera app)")
                if (!powerManager.isInteractive) {
                    scheduleScreenOffWindow()
                }
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
        trace("preference_changed", "SETTINGS_RELOADED", "key" to key)
        loadSensitivityPrefs(sharedPrefs)
        if (key == KEY_AUTO_OFF_MINUTES && isOwnedTorchActive()) {
            scheduleAutoShutoff()
        }
        if (key == KEY_WAKE_MODE) {
            applyWakeModePolicy()
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLog.start(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        flashCameraId = findFlashCameraId(cameraManager)
        hasProximityHardware = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (flashCameraId == null && !prefs.getBoolean(KEY_USE_SCREEN_TORCH, false)) {
            prefs.edit { putBoolean(KEY_USE_SCREEN_TORCH, true) }
        }
        trace("service_created", "STARTING", "camera_id" to flashCameraId,
            "has_proximity" to hasProximityHardware,
            "battery_exempt" to powerManager.isIgnoringBatteryOptimizations(packageName),
            "notifications_enabled" to getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        if (BuildConfig.DIAGNOSTICS_ENABLED) detector.traceListener = { sample ->
            if (sample.reason == "FIRST_CHOP_RECORDED") gestureId++
            DebugLog.event("motion_sample", "reason" to sample.reason, "gesture_id" to gestureId,
                "force" to sample.force, "in_plane" to sample.inPlane,
                "phase_before" to sample.before, "phase_after" to sample.after,
                "threshold" to sample.threshold, "window_ms" to sample.windowMs,
                "torch_on_off_mode" to sample.torchIsOn,
                "cooldown_remaining_ms" to sample.cooldownRemainingMs,
                "first_chop_ms" to sample.firstChopMs, "last_toggle_ms" to sample.lastToggleMs,
                "gesture_age_ms" to sample.gestureAgeMs, "window_expired" to sample.windowExpired, "sample_now_ms" to sample.nowMs,
                "linear_x" to linearBuffer[0], "linear_y" to linearBuffer[1], "linear_z" to linearBuffer[2])
            if (sample.windowExpired) trace("gesture_window_expired", "NO_SECOND_CHOP_IN_WINDOW",
                "window_ms" to sample.windowMs, "off_mode" to sample.torchIsOn)
            if (sample.reason != lastDetectorReason || sample.reason == "DOUBLE_CHOP_ACCEPTED") {
                trace("gesture_decision", sample.reason, "force" to sample.force,
                    "phase_before" to sample.before, "phase_after" to sample.after,
                    "cooldown_remaining_ms" to sample.cooldownRemainingMs)
                lastDetectorReason = sample.reason
            }
        }
        loadSensitivityPrefs(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        cameraManager.registerTorchCallback(torchCallback, mainHandler)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ScreenTorchActivity.ACTION_USER_DISMISSED_SCREEN_TORCH)
        }
        ContextCompat.registerReceiver(this, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        createNotificationChannels()
        startForegroundWithSpecialUse()
        applyWakeModePolicy()
        if (BuildConfig.DIAGNOSTICS_ENABLED) mainHandler.post(diagnosticHeartbeat)
    }

    private fun loadSensitivityPrefs(sharedPrefs: SharedPreferences) {
        useScreenTorch = (flashCameraId == null) || sharedPrefs.getBoolean(KEY_USE_SCREEN_TORCH, false)
        detector.forceThreshold = sharedPrefs.getFloat(KEY_FORCE_THRESHOLD, TorchDefaults.FORCE_THRESHOLD)
        detector.chopWindowMs = sharedPrefs.getInt(KEY_CHOP_WINDOW_MS, TorchDefaults.CHOP_WINDOW_MS).toLong()
        detector.cooldownMs = sharedPrefs.getInt(KEY_COOLDOWN_MS, TorchDefaults.COOLDOWN_MS).toLong()
        autoOffMinutes = sharedPrefs.getInt(KEY_AUTO_OFF_MINUTES, TorchDefaults.AUTO_OFF_MINUTES)
        wakeMode = sharedPrefs.getInt(KEY_WAKE_MODE, TorchDefaults.WAKE_MODE).coerceIn(0, 5)
        trace("settings_snapshot", "CURRENT_DETECTOR_AND_POWER_POLICY",
            "force_threshold" to detector.forceThreshold, "chop_window_ms" to detector.chopWindowMs,
            "valley_ratio" to ChopDetector.VALLEY_RATIO,
            "off_force_ratio" to ChopDetector.OFF_FORCE_MULTIPLIER,
            "off_window_grace_ms" to ChopDetector.OFF_WINDOW_EXTENSION_MS,
            "cooldown_ms" to detector.cooldownMs, "auto_off_minutes" to autoOffMinutes,
            "use_screen_torch" to useScreenTorch,
            "armed_pref" to sharedPrefs.getBoolean(KEY_SERVICE_ENABLED, false))
    }

    private fun resetGestureState() {
        trace("gesture_reset", "GESTURE_AND_GRAVITY_FILTER_RESET_COOLDOWN_RETAINED")
        detector.reset()
        gravityFilter.reset()
    }

    private fun applyWakeModePolicy() {
        trace("wake_policy", "APPLY_CURRENT_POLICY")
        mainHandler.removeCallbacks(sleepSensorsRunnable)
        if (wakeMode == 5 || powerManager.isInteractive || isOwnedTorchActive()) {
            activateSensorsAndWakeLock()
        } else {
            scheduleScreenOffWindow()
        }
    }

    private fun scheduleScreenOffWindow() {
        trace("screen_off_policy", if (wakeMode == 5 || isOwnedTorchActive())
            "KEEP_AWAKE_MODE_OR_OWNED_LIGHT" else "LIMITED_LISTENING_WINDOW",
            "window_ms" to WAKE_WINDOW_MS_MAP[wakeMode])
        mainHandler.removeCallbacks(sleepSensorsRunnable)
        if (wakeMode == 5 || isOwnedTorchActive()) {
            activateSensorsAndWakeLock()
            return
        }
        val windowMs = WAKE_WINDOW_MS_MAP[wakeMode]
        if (windowMs <= 0L) {
            deactivateSensorsAndWakeLock()
        } else {
            activateSensorsAndWakeLock()
            mainHandler.postDelayed(sleepSensorsRunnable, windowMs)
        }
    }

    private fun activateSensorsAndWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KineticTorch::ChopAccelerometerWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
            trace("wake_lock_acquired", "TWO_HOUR_SAFETY_TIMEOUT", "timeout_ms" to 7_200_000L)
        }
        if (!sensorsActive) {
            resetGestureState()
            isPocketBlocked = hasProximityHardware && !powerManager.isInteractive

            // Prefer the OS's gravity-free linear-acceleration sensor; fall back to the raw
            // accelerometer + our own high-pass filter on devices without it.
            val motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (motionSensor == null) trace("motion_sensor_missing", "NO_LINEAR_OR_RAW_ACCELEROMETER")
            motionSensor?.let { sensor ->
                val registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
                trace("motion_sensor_registration", if (registered) "REGISTERED" else "REGISTRATION_FAILED",
                    "sensor_type" to sensor.type, "sensor_name" to sensor.name,
                    "sensor_wakeup" to sensor.isWakeUpSensor)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { prox ->
                val registered = sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL, 0)
                trace("proximity_sensor_registration", if (registered) "REGISTERED" else "REGISTRATION_FAILED",
                    "max_range" to prox.maximumRange)
            }
            sensorsActive = true
        }
    }

    private fun deactivateSensorsAndWakeLock() {
        trace("sensors_deactivating", "UNREGISTER_LISTENERS_RELEASE_WAKE_LOCK")
        resetGestureState()
        if (sensorsActive) {
            sensorManager.unregisterListener(this)
            sensorsActive = false
        }
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                trace("wake_lock_released", "SENSORS_DEACTIVATED")
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> handleProximity(event)
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> handleMotion(event)
        }
    }

    private fun handleProximity(event: SensorEvent) {
        val maxRange = event.sensor.maximumRange
        val distance = event.values[0]
        // Binary sensors report 0 (or ~3) for "near" and maxRange for "far"; continuous ones report cm.
        val blockedNow = distance < minOf(maxRange, PROXIMITY_NEAR_CM)
        // A covered sensor cannot arm the torch, but must not trap a lit torch ON.
        // Do not reset a pending OFF gesture when the proximity sensor reports "near".
        val torchOn = isAnyTorchActive()
        if (blockedNow && !isPocketBlocked && ChopDetector.shouldBlockByProximity(blockedNow, torchOn)) {
            resetGestureState()
        }
        isPocketBlocked = blockedNow
        trace("proximity_sample", when {
            !blockedNow -> "GUARD_CLEAR"
            torchOn -> "ALLOW_OFF_WHILE_COVERED"
            else -> "POCKET_GUARD_BLOCKS_ACTIVATION"
        },
            "distance_cm" to distance, "max_range_cm" to maxRange,
            "near_cutoff_cm" to minOf(maxRange, PROXIMITY_NEAR_CM))
    }

    private fun handleMotion(event: SensorEvent) {
        lastMotionElapsedMs = SystemClock.elapsedRealtime()
        DebugLog.event("motion_raw", "sensor_type" to event.sensor.type,
            "sensor_timestamp_ns" to event.timestamp,
            "x" to event.values[0], "y" to event.values[1], "z" to event.values[2],
            "pocket_blocked" to isPocketBlocked, "wake_held" to wakeLock?.isHeld)
        val torchOn = isAnyTorchActive()
        if (ChopDetector.shouldBlockByProximity(isPocketBlocked, torchOn)) {
            DebugLog.event("motion_sample", "reason" to "POCKET_GUARD_BLOCKED",
                "gesture_id" to gestureId, "torch_on_off_mode" to false)
            resetGestureState()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val v = event.values
        val lin = linearBuffer
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            lin[0] = v[0]; lin[1] = v[1]; lin[2] = v[2]
        } else if (!gravityFilter.filter(v[0], v[1], v[2], now, lin)) {
            DebugLog.event("motion_sample", "reason" to "GRAVITY_FILTER_WARMUP",
                "gesture_id" to gestureId, "linear_x" to lin[0], "linear_y" to lin[1], "linear_z" to lin[2])
            return // raw accelerometer fallback still settling
        }
        if (detector.onLinearAcceleration(lin[0], lin[1], lin[2], now, torchIsOn = torchOn)) {
            toggleActiveTorch()
        }
    }

    private fun toggleActiveTorch() {
        trace("activation_decision", if (isAnyTorchActive()) "ACCEPTED_CHOP_TURNS_LIGHT_OFF" else "ACCEPTED_CHOP_TURNS_LIGHT_ON")
        if (isAnyTorchActive()) {
            deactivateActiveTorch()
        } else {
            activatePreferredTorch()
        }
    }

    private fun activatePreferredTorch() {
        trace("activation_route", if (useScreenTorch || flashCameraId == null)
            "SCREEN_SELECTED_OR_NO_FLASH" else "HARDWARE_LED_SELECTED")
        if (useScreenTorch || flashCameraId == null) {
            setScreenTorchState(true)
        } else {
            setHardwareLedState(true)
        }
    }

    private fun deactivateActiveTorch() {
        trace("deactivation_route", when {
            activeLightSource == ActiveLightSource.SCREEN_WHITE || ScreenTorchActivity.isScreenTorchVisible -> "CLOSE_SCREEN_TORCH"
            activeLightSource == ActiveLightSource.HARDWARE_LED -> "SWITCH_LED_OFF"
            else -> "NO_TRACKED_ACTIVE_LIGHT"
        })
        when {
            activeLightSource == ActiveLightSource.SCREEN_WHITE || ScreenTorchActivity.isScreenTorchVisible -> {
                setScreenTorchState(false)
            }
            activeLightSource == ActiveLightSource.HARDWARE_LED -> {
                setHardwareLedState(false)
            }
        }
    }

    private fun setHardwareLedState(targetState: Boolean) {
        val requestId = ++ledRequestId
        trace("led_request", "REQUESTING_OS_TORCH", "request_id" to requestId, "target" to targetState)
        val camId = flashCameraId
        if (camId == null) {
            trace("led_request_skipped", "NO_FLASH_CAMERA_ID", "request_id" to requestId)
            if (targetState) setScreenTorchState(true)
            return
        }
        try {
            pendingLedRequest = requestId
            pendingLedTarget = targetState
            cameraManager.setTorchMode(camId, targetState)
            trace("led_api_returned", "REQUEST_RETURNED_WITHOUT_EXCEPTION_NOT_YET_CONFIRMED",
                "request_id" to requestId, "target" to targetState)
            mainHandler.postDelayed({
                if (pendingLedRequest == requestId) trace("led_confirmation_timeout",
                    "NO_MATCHING_OS_CALLBACK_OBSERVED", "request_id" to requestId, "target" to targetState)
            }, 1500L)
            // Note: torchCallback.onTorchModeChanged will authoritatively confirm activeLightSource,
            // but we also set optimistic state here and trigger haptics immediately.
            activeLightSource = if (targetState) ActiveLightSource.HARDWARE_LED else ActiveLightSource.NONE
            hardwareLedOwnedByUs = targetState
            triggerHapticConfirmation(targetState)
            if (targetState) {
                mainHandler.removeCallbacks(sleepSensorsRunnable)
                scheduleAutoShutoff()
                updateNotificationStatus("LED Torch: ON (tap Disarm/Off to shut down)")
            } else {
                mainHandler.removeCallbacks(autoShutoffRunnable)
                updateNotificationStatus("Armed • LED Torch: OFF")
                if (!powerManager.isInteractive) {
                    scheduleScreenOffWindow()
                }
            }
        } catch (e: CameraAccessException) {
            pendingLedRequest = null
            pendingLedTarget = null
            trace("led_request_failed", "CAMERA_ACCESS_EXCEPTION", "request_id" to requestId,
                "camera_reason_code" to e.reason, "message" to e.message,
                "stack" to android.util.Log.getStackTraceString(e))
            activeLightSource = ActiveLightSource.NONE
            hardwareLedOwnedByUs = false
            if (targetState) {
                // Camera LED is busy or unavailable; fall back to 100% White Screen Torch and track it as SCREEN_WHITE
                updateNotificationStatus("LED busy — switched to 100% White Screen Torch")
                setScreenTorchState(true)
            } else {
                updateNotificationStatus("LED unavailable (${e.reason})")
            }
        } catch (e: Exception) {
            pendingLedRequest = null
            pendingLedTarget = null
            trace("led_request_failed", e.javaClass.simpleName, "request_id" to requestId,
                "message" to e.message, "stack" to android.util.Log.getStackTraceString(e))
            activeLightSource = ActiveLightSource.NONE
            hardwareLedOwnedByUs = false
            updateNotificationStatus("Torch error: ${e.javaClass.simpleName}")
        }
    }

    private fun setScreenTorchState(targetState: Boolean) {
        trace("screen_torch_request", if (targetState) "OPEN_REQUESTED_NOT_YET_VISIBLE" else "CLOSE_REQUESTED",
            "notifications_enabled" to getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            "full_screen_intent_allowed" to if (Build.VERSION.SDK_INT >= 34)
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent() else true)
        val nm = getSystemService(NotificationManager::class.java)
        if (targetState) {
            activeLightSource = ActiveLightSource.SCREEN_WHITE
            triggerHapticConfirmation(true)
            mainHandler.removeCallbacks(sleepSensorsRunnable)
            scheduleAutoShutoff()

            val fullScreenIntent = Intent(this, ScreenTorchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this, 2, fullScreenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // Locked / screen-off: the full-screen intent wakes the device and shows the torch.
            // Unlocked and in use: Android shows this as a heads-up instead, so the content intent
            // (tap) must open the torch screen too.
            val alertNotification = Notification.Builder(this, SCREEN_TORCH_ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chop_torch)
                .setContentTitle("100% White Screen Torch Active")
                .setContentText("Tap to open • double-chop to turn off")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .build()

            nm.notify(SCREEN_TORCH_NOTIFICATION_ID, alertNotification)

            // Best effort only: a service is not exempt from Android 10+ background-activity-launch
            // limits, so this is frequently blocked. screenTorchLaunchCheck reports when it was.
            if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked) {
                try {
                    startActivity(fullScreenIntent)
                    trace("screen_activity_start", "START_ACTIVITY_RETURNED_VISIBILITY_PENDING")
                } catch (error: Exception) {
                    trace("screen_activity_start_failed", error.javaClass.simpleName,
                        "message" to error.message, "stack" to android.util.Log.getStackTraceString(error))
                }
            }
            mainHandler.removeCallbacks(screenTorchLaunchCheck)
            mainHandler.postDelayed(screenTorchLaunchCheck, SCREEN_TORCH_LAUNCH_CHECK_MS)
            updateNotificationStatus("100% White Screen Torch: ON")
        } else {
            activeLightSource = ActiveLightSource.NONE
            triggerHapticConfirmation(false)
            mainHandler.removeCallbacks(autoShutoffRunnable)
            mainHandler.removeCallbacks(screenTorchLaunchCheck)
            nm.cancel(SCREEN_TORCH_NOTIFICATION_ID)
            sendBroadcast(Intent(ScreenTorchActivity.ACTION_CLOSE_SCREEN_TORCH).setPackage(packageName))
            updateNotificationStatus("Armed • Screen Torch: OFF")
            if (!powerManager.isInteractive) {
                scheduleScreenOffWindow()
            }
        }
    }

    private fun scheduleAutoShutoff() {
        trace("auto_off_schedule", if (autoOffMinutes > 0) "TIMER_SCHEDULED" else "DISABLED_BY_SETTING",
            "minutes" to autoOffMinutes)
        mainHandler.removeCallbacks(autoShutoffRunnable)
        if (autoOffMinutes > 0) {
            mainHandler.postDelayed(autoShutoffRunnable, autoOffMinutes * 60_000L)
        }
    }

    /**
     * A double pulse confirms ON and a single short pulse confirms OFF. This runs only from the
     * torch-toggle paths, not the asynchronous camera callback, avoiding duplicate feedback.
     * If the vibrator is missing or blocked, the torch operation must still succeed.
     */
    private fun triggerHapticConfirmation(turnedOn: Boolean) {
        trace("haptic_request", if (turnedOn) "DOUBLE_PULSE_ON" else "SINGLE_PULSE_OFF",
            "target" to turnedOn)
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) {
                trace("haptic_skipped", "NO_VIBRATOR_AVAILABLE")
                return
            }
            val effect = if (turnedOn) {
                VibrationEffect.createWaveform(TorchHapticPatterns.activationTimingsMs(), -1)
            } else {
                VibrationEffect.createOneShot(
                    TorchHapticPatterns.DEACTIVATION_PULSE_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            }
            vibrator.vibrate(effect)
        } catch (error: Exception) {
            trace("haptic_failed", error.javaClass.simpleName, "message" to error.message)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "S.T.A.R.S. Torch Background Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps chop-flash gesture detection active"
            setShowBadge(false)
        }
        val screenTorchChannel = NotificationChannel(
            SCREEN_TORCH_ALERT_CHANNEL_ID,
            "Screen Torch Lock-Screen Wake",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Wakes screen at 100% white brightness when Screen Torch mode is triggered"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(screenTorchChannel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ChopDetectionService::class.java).apply {
            action = ACTION_STOP_AND_OFF
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disarmAction = Notification.Action.Builder(
            null,
            "DISARM / TORCH OFF",
            stopPendingIntent
        ).build()

        val proxTag = if (hasProximityHardware) "Prox Guard: ON" else "No Prox Sensor"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("S.T.A.R.S. Kinetic Torch Active")
            .setContentText("$statusText • $proxTag")
            .setSmallIcon(R.drawable.ic_chop_torch)
            .setContentIntent(openAppIntent)
            .addAction(disarmAction)
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationStatus(statusText: String) {
        trace("notification_status", "STATUS_UPDATED", "text" to statusText)
        lastStatusText = statusText
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun startForegroundWithSpecialUse() {
        trace("foreground_start", "REQUEST_SPECIAL_USE_FOREGROUND_SERVICE")
        val notification = buildNotification(lastStatusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Centralized shutdown path: guarantees both the hardware LED torch and the 100% White
     * Screen Torch are switched OFF, timers are cancelled, listeners are unregistered, and
     * wake locks are released whenever the service stops via UI switch, QS tile, notification, or OS.
     */
    private fun shutdownTorchAndCleanup() {
        trace("service_cleanup", "STOP_OWNED_LIGHT_CANCEL_TIMERS_AND_SENSORS")
        mainHandler.removeCallbacks(autoShutoffRunnable)
        mainHandler.removeCallbacks(sleepSensorsRunnable)
        mainHandler.removeCallbacks(screenTorchLaunchCheck)

        // Only switch the LED off if we switched it on; leave a flashlight someone else started alone.
        if (hardwareLedOwnedByUs) {
            flashCameraId?.let { camId ->
                try {
                    cameraManager.setTorchMode(camId, false)
                    trace("cleanup_led_api_returned", "OFF_REQUEST_RETURNED")
                } catch (error: Exception) {
                    trace("cleanup_led_failed", error.javaClass.simpleName, "message" to error.message)
                }
            }
        }
        hardwareLedOwnedByUs = false
        if (activeLightSource == ActiveLightSource.SCREEN_WHITE || ScreenTorchActivity.isScreenTorchVisible) {
            getSystemService(NotificationManager::class.java)?.cancel(SCREEN_TORCH_NOTIFICATION_ID)
            sendBroadcast(Intent(ScreenTorchActivity.ACTION_CLOSE_SCREEN_TORCH).setPackage(packageName))
        }
        activeLightSource = ActiveLightSource.NONE
        deactivateSensorsAndWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DeviceEligibility.isMotorolaDevice()) {
            prefs.edit { putBoolean(KEY_SERVICE_ENABLED, false) }
            trace("service_blocked", "MOTOROLA_DEVICE_EXCLUDED")
            stopSelf()
            return START_NOT_STICKY
        }
        trace("service_start_command", intent?.action ?: "START_OR_SYSTEM_RESTART",
            "flags" to flags, "start_id" to startId)
        if (intent?.action == ACTION_STOP_AND_OFF) {
            prefs.edit { putBoolean(KEY_SERVICE_ENABLED, false) }
            shutdownTorchAndCleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() call must be answered with startForeground() within ~5 s,
        // including repeat calls on an already-running service (e.g. MainActivity re-arming it).
        startForegroundWithSpecialUse()
        return START_STICKY
    }

    override fun onDestroy() {
        trace("service_destroyed", "ANDROID_ON_DESTROY")
        mainHandler.removeCallbacks(diagnosticHeartbeat)
        shutdownTorchAndCleanup()
        try {
            unregisterReceiver(systemReceiver)
        } catch (_: Exception) {}
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        cameraManager.unregisterTorchCallback(torchCallback)
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        trace("sensor_accuracy", "ACCURACY_CHANGED", "sensor_type" to sensor?.type, "accuracy" to accuracy)
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        trace("service_task_removed", "APP_TASK_SWIPED_AWAY")
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

