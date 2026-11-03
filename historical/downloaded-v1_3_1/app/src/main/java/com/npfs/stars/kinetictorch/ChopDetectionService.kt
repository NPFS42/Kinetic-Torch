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
        const val KEY_FORCE_THRESHOLD = "force_threshold"       // Default: 18.5 m/s^2 (10.0..35.0)
        const val KEY_CHOP_WINDOW_MS = "chop_window_ms"         // Default: 650 ms (300..1000)
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
        if (isOwnedTorchActive()) {
            deactivateActiveTorch()
        }
    }

    // Screen-Off Sleep Window Runnable (for Wake Modes 0..4)
    private val sleepSensorsRunnable = Runnable {
        if (!isOwnedTorchActive() && wakeMode < 5 && !powerManager.isInteractive) {
            deactivateSensorsAndWakeLock()
        }
    }

    // Android 10+ can silently block a background startActivity(); the full-screen-intent alert is
    // then the only way in. If the torch screen never appeared, say so in the status notification.
    private val screenTorchLaunchCheck = Runnable {
        if (activeLightSource == ActiveLightSource.SCREEN_WHITE && !ScreenTorchActivity.isScreenTorchVisible) {
            updateNotificationStatus("Screen Torch blocked by Android • tap the alert to open it")
        }
    }

    private var lastStatusText = "Armed • Torch: OFF"

    // Screen State & ScreenTorch Dismissal Receiver
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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
            if (cameraId != flashCameraId) return
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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        flashCameraId = findFlashCameraId(cameraManager)
        hasProximityHardware = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (flashCameraId == null && !prefs.getBoolean(KEY_USE_SCREEN_TORCH, false)) {
            prefs.edit().putBoolean(KEY_USE_SCREEN_TORCH, true).apply()
        }
        loadSensitivityPrefs(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        cameraManager.registerTorchCallback(torchCallback, mainHandler)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ScreenTorchActivity.ACTION_USER_DISMISSED_SCREEN_TORCH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }

        createNotificationChannels()
        startForegroundWithSpecialUse()
        applyWakeModePolicy()
    }

    private fun loadSensitivityPrefs(sharedPrefs: SharedPreferences) {
        useScreenTorch = (flashCameraId == null) || sharedPrefs.getBoolean(KEY_USE_SCREEN_TORCH, false)
        detector.forceThreshold = sharedPrefs.getFloat(KEY_FORCE_THRESHOLD, 18.5f)
        detector.chopWindowMs = sharedPrefs.getInt(KEY_CHOP_WINDOW_MS, 650).toLong()
        detector.cooldownMs = sharedPrefs.getInt(KEY_COOLDOWN_MS, 1000).toLong()
        autoOffMinutes = sharedPrefs.getInt(KEY_AUTO_OFF_MINUTES, 10)
        wakeMode = sharedPrefs.getInt(KEY_WAKE_MODE, 2).coerceIn(0, 5)
    }

    private fun resetGestureState() {
        detector.reset()
        gravityFilter.reset()
    }

    private fun applyWakeModePolicy() {
        mainHandler.removeCallbacks(sleepSensorsRunnable)
        if (wakeMode == 5 || powerManager.isInteractive || isOwnedTorchActive()) {
            activateSensorsAndWakeLock()
        } else {
            scheduleScreenOffWindow()
        }
    }

    private fun scheduleScreenOffWindow() {
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
            wakeLock?.acquire()
        }
        if (!sensorsActive) {
            resetGestureState()
            isPocketBlocked = hasProximityHardware && !powerManager.isInteractive

            // Prefer the OS's gravity-free linear-acceleration sensor; fall back to the raw
            // accelerometer + our own high-pass filter on devices without it.
            val motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            motionSensor?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { prox ->
                sensorManager.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL, 0)
            }
            sensorsActive = true
        }
    }

    private fun deactivateSensorsAndWakeLock() {
        resetGestureState()
        if (sensorsActive) {
            sensorManager.unregisterListener(this)
            sensorsActive = false
        }
        wakeLock?.let {
            if (it.isHeld) it.release()
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
        if (blockedNow && !isPocketBlocked) {
            resetGestureState()
        }
        isPocketBlocked = blockedNow
    }

    private fun handleMotion(event: SensorEvent) {
        if (isPocketBlocked) {
            resetGestureState()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val v = event.values
        val lin = linearBuffer
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            lin[0] = v[0]; lin[1] = v[1]; lin[2] = v[2]
        } else if (!gravityFilter.filter(v[0], v[1], v[2], now, lin)) {
            return // raw accelerometer fallback still settling
        }
        if (detector.onLinearAcceleration(lin[0], lin[1], lin[2], now)) {
            toggleActiveTorch()
        }
    }

    private fun toggleActiveTorch() {
        if (isAnyTorchActive()) {
            deactivateActiveTorch()
        } else {
            activatePreferredTorch()
        }
    }

    private fun activatePreferredTorch() {
        if (useScreenTorch || flashCameraId == null) {
            setScreenTorchState(true)
        } else {
            setHardwareLedState(true)
        }
    }

    private fun deactivateActiveTorch() {
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
        val camId = flashCameraId
        if (camId == null) {
            if (targetState) setScreenTorchState(true)
            return
        }
        try {
            cameraManager.setTorchMode(camId, targetState)
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
            activeLightSource = ActiveLightSource.NONE
            hardwareLedOwnedByUs = false
            updateNotificationStatus("Torch error: ${e.javaClass.simpleName}")
        }
    }

    private fun setScreenTorchState(targetState: Boolean) {
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
                } catch (_: Exception) {}
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
        mainHandler.removeCallbacks(autoShutoffRunnable)
        if (autoOffMinutes > 0) {
            mainHandler.postDelayed(autoShutoffRunnable, autoOffMinutes * 60_000L)
        }
    }

    private fun triggerHapticConfirmation(turnedOn: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val duration = if (turnedOn) 70L else 45L
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        lastStatusText = statusText
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun startForegroundWithSpecialUse() {
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
        mainHandler.removeCallbacks(autoShutoffRunnable)
        mainHandler.removeCallbacks(sleepSensorsRunnable)
        mainHandler.removeCallbacks(screenTorchLaunchCheck)

        // Only switch the LED off if we switched it on; leave a flashlight someone else started alone.
        if (hardwareLedOwnedByUs) {
            flashCameraId?.let { camId ->
                try {
                    cameraManager.setTorchMode(camId, false)
                } catch (_: Exception) {}
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
        if (intent?.action == ACTION_STOP_AND_OFF) {
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, false).apply()
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
        shutdownTorchAndCleanup()
        try {
            unregisterReceiver(systemReceiver)
        } catch (_: Exception) {}
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        cameraManager.unregisterTorchCallback(torchCallback)
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onBind(intent: Intent?): IBinder? = null
}
