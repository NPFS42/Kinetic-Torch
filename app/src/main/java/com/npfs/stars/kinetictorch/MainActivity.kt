package com.npfs.stars.kinetictorch

import android.Manifest
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private lateinit var txtLivePeakReadout: TextView
    private lateinit var switchService: MaterialSwitch
    private var hasLedFlash = false
    private var sessionPeakAccel = 0.0f
    private var currentThreshold = TorchDefaults.FORCE_THRESHOLD
    private var suppressSliderWrites = false

    // Same motion pipeline as ChopDetectionService, so the live readout matches what triggers a chop
    private val gravityFilter = GravityHighPass()
    private val linearBuffer = FloatArray(3)

    private val wakeModeLabels = arrayOf(
        "Screen-On Only (0s after lock)",
        "Screen-Wake + 15s Window",
        "Screen-Wake + 30s Window (Recommended)",
        "Screen-Wake + 2 min Window",
        "Screen-Wake + 10 min Window",
        "Always-On 24/7 (Continuous Wake Lock — high battery use)"
    )

    private val exportLogLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            if (uri != null) {
                DebugLog.event("log_export", "reason" to "USER_REQUESTED_SNAPSHOT")
                val appContext = applicationContext
                Thread({
                    val result = runCatching {
                        val output = appContext.contentResolver.openOutputStream(uri)
                            ?: error("Could not open export destination")
                        output.use { DebugLog.export(it) }
                    }
                    runOnUiThread {
                        Toast.makeText(this, if (result.isSuccess) "Debug log saved. Attach it to the chat."
                            else "Export failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }, "kinetic-log-export").start()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            DebugLog.event("notification_permission", "reason" to if (granted) "GRANTED" else "DENIED")
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifications are off, so the Disarm button and the lock-screen Screen Torch alert " +
                            "can't appear. Enable them in system Settings > Apps > Kinetic Torch.",
                    Toast.LENGTH_LONG
                ).show()
            }
            promptFullScreenIntentPermissionIfNeeded(force = false)
        }

    private val serviceToggleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        DebugLog.event("ui_arm_toggle", "reason" to if (checked) "USER_ARMED" else "USER_DISARMED")
        prefs.edit { putBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, checked) }
        val intent = Intent(this, ChopDetectionService::class.java)
        if (checked) {
            DebugLog.action("ui_start_service") { startForegroundService(intent) }
            requestNotificationPermissionThenPromptFullScreen()
        } else {
            // Calls onDestroy() -> shutdownTorchAndCleanup() to guarantee torch turns off
            DebugLog.action("ui_stop_service") { stopService(intent) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.start(this)
        DebugLog.event("ui_created", "reason" to "SETUP_ACTIVITY_STARTED")

        // Android 15+ draws edge-to-edge. Keep interactive settings entirely inside the
        // status bar, navigation/gesture bar, and display-cutout safe areas.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val settingsScroll = findViewById<View>(R.id.rootScroll)
        val initialPadding = intArrayOf(
            settingsScroll.paddingLeft, settingsScroll.paddingTop,
            settingsScroll.paddingRight, settingsScroll.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(settingsScroll) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialPadding[0] + safe.left, initialPadding[1] + safe.top,
                initialPadding[2] + safe.right, initialPadding[3] + safe.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(settingsScroll)

        prefs = getSharedPreferences(ChopDetectionService.PREFS_NAME, MODE_PRIVATE)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        // Defense in depth: Play Console model exclusions alone do not block sideloaded installs.
        if (DeviceEligibility.isMotorolaDevice()) {
            prefs.edit { putBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false) }
            stopService(Intent(this, ChopDetectionService::class.java))
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.motorola_excluded_title)
                .setMessage(R.string.motorola_excluded_message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return
        }

        val txtHardwareStatus = findViewById<TextView>(R.id.txtHardwareStatus)
        txtLivePeakReadout = findViewById(R.id.txtLivePeakReadout)
        switchService = findViewById(R.id.switchServiceEnable)
        val switchScreenTorch = findViewById<MaterialSwitch>(R.id.switchScreenTorchFallback)

        val seekForce = findViewById<SeekBar>(R.id.seekForceThreshold)
        val txtForceValue = findViewById<TextView>(R.id.txtForceValue)

        val seekWindow = findViewById<SeekBar>(R.id.seekChopWindow)
        val txtWindowValue = findViewById<TextView>(R.id.txtWindowValue)

        val seekAutoOff = findViewById<SeekBar>(R.id.seekAutoOffMinutes)
        val txtAutoOffValue = findViewById<TextView>(R.id.txtAutoOffValue)

        val seekWakeMode = findViewById<SeekBar>(R.id.seekWakeMode)
        val txtWakeModeValue = findViewById<TextView>(R.id.txtWakeModeValue)

        val btnResetSliders = findViewById<Button>(R.id.btnResetSliders)
        val btnAddQsTile = findViewById<Button>(R.id.btnAddQuickSettingsTile)
        val btnBatteryExempt = findViewById<Button>(R.id.btnBatteryExemption)
        findViewById<View>(R.id.debugPanel).visibility = if (BuildConfig.DIAGNOSTICS_ENABLED) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnExportDebugLog).setOnClickListener {
            exportLogLauncher.launch("kinetic-torch-debug-${System.currentTimeMillis()}.jsonl")
        }
        findViewById<Button>(R.id.btnMarkDebugAttempt).setOnClickListener {
            DebugLog.event("test_marker", "reason" to "USER_ABOUT_TO_ATTEMPT_DOUBLE_CHOP",
                "armed_pref" to prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false))
            Toast.makeText(this, "Attempt marked. Try the double-chop now.", Toast.LENGTH_SHORT).show()
        }

        // Hardware Audit: Check Rear/Front Flash LED and Proximity Sensor presence
        val flashId = ChopDetectionService.findFlashCameraId(cameraManager)
        hasLedFlash = (flashId != null)
        val hasProximity = (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null)

        val ledStatus = if (hasLedFlash) "Rear LED Flash ($flashId)" else "No LED (100% White Screen Torch Active)"
        val proxStatus = if (hasProximity) "Proximity Guard: Ready" else "No Proximity Sensor (Use Screen-Wake Mode)"
        txtHardwareStatus.text = getString(R.string.hardware_status_format, ledStatus, proxStatus)
        DebugLog.event("ui_hardware", "reason" to "HARDWARE_AUDIT", "flash_id" to flashId,
            "has_proximity" to hasProximity)

        // Full-Screen 100% White Screen Torch Switch (Auto-locked ON if device has no LED).
        // The full-screen-intent permission page is NOT opened here any more: it is requested when the
        // user arms the service (once) or flips this switch on, never on every app launch.
        if (!hasLedFlash) {
            prefs.edit { putBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, true) }
            switchScreenTorch.isChecked = true
            switchScreenTorch.isEnabled = false
            switchScreenTorch.text = getString(R.string.screen_torch_auto_enabled)
        } else {
            switchScreenTorch.isChecked = prefs.getBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, false)
            switchScreenTorch.setOnCheckedChangeListener { _, checked ->
                prefs.edit { putBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, checked) }
                if (checked) {
                    promptFullScreenIntentPermissionIfNeeded(force = true)
                }
            }
        }

        // 1. Master Service Toggle (state is re-synced from prefs in onResume)
        refreshServiceSwitch()

        // 2. Chop Force Threshold Slider (10.0 to 35.0 m/s^2, mapped as progress 0..250)
        currentThreshold = prefs.getFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, TorchDefaults.FORCE_THRESHOLD)
        seekForce.max = 250
        seekForce.progress = ((currentThreshold - 10.0f) * 10).toInt().coerceIn(0, 250)
        txtForceValue.text = getString(R.string.force_threshold_format, currentThreshold)

        seekForce.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = 10.0f + (progress / 10.0f)
                txtForceValue.text = getString(R.string.force_threshold_format, currentThreshold)
                if (!suppressSliderWrites) prefs.edit { putFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, currentThreshold) }
                sessionPeakAccel = 0.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Double-Chop Time Window Slider (300 ms to 1000 ms, mapped as progress 0..700)
        val savedWindow = prefs.getInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, TorchDefaults.CHOP_WINDOW_MS)
        seekWindow.max = 700
        seekWindow.progress = (savedWindow - 300).coerceIn(0, 700)
        txtWindowValue.text = "2. Double-Chop Window: ${savedWindow} ms"

        seekWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val windowMs = 300 + progress
                txtWindowValue.text = "2. Double-Chop Window: ${windowMs} ms"
                if (!suppressSliderWrites) prefs.edit { putInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, windowMs) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. Auto-Shutoff Safety Timer Slider (0 = Disabled, 1 to 30 minutes)
        val savedAutoOff = prefs.getInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, TorchDefaults.AUTO_OFF_MINUTES)
        seekAutoOff.max = 30
        seekAutoOff.progress = savedAutoOff.coerceIn(0, 30)
        txtAutoOffValue.text = formatAutoOffLabel(savedAutoOff)

        seekAutoOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtAutoOffValue.text = formatAutoOffLabel(progress)
                if (!suppressSliderWrites) prefs.edit { putInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, progress) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 5. Standby Power Mode Slider (0 = Screen-On Only .. 2 = Screen-Wake + 30s .. 5 = Always-On 24/7)
        val savedWakeMode = prefs.getInt(ChopDetectionService.KEY_WAKE_MODE, TorchDefaults.WAKE_MODE).coerceIn(0, 5)
        seekWakeMode.max = 5
        seekWakeMode.progress = savedWakeMode
        txtWakeModeValue.text = "4. Standby Mode: ${wakeModeLabels[savedWakeMode]}"

        seekWakeMode.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mode = progress.coerceIn(0, 5)
                txtWakeModeValue.text = "4. Standby Mode: ${wakeModeLabels[mode]}"
                if (!suppressSliderWrites) prefs.edit { putInt(ChopDetectionService.KEY_WAKE_MODE, mode) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Restore only the four visible sliders. Never disarm the service or wipe other settings.
        btnResetSliders.setOnClickListener {
            prefs.edit {
                putFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, TorchDefaults.FORCE_THRESHOLD)
                putInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, TorchDefaults.CHOP_WINDOW_MS)
                putInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, TorchDefaults.AUTO_OFF_MINUTES)
                putInt(ChopDetectionService.KEY_WAKE_MODE, TorchDefaults.WAKE_MODE)
            }
            suppressSliderWrites = true
            try {
                currentThreshold = TorchDefaults.FORCE_THRESHOLD
                seekForce.progress = ((TorchDefaults.FORCE_THRESHOLD - 10.0f) * 10).toInt()
                seekWindow.progress = TorchDefaults.CHOP_WINDOW_MS - 300
                seekAutoOff.progress = TorchDefaults.AUTO_OFF_MINUTES
                seekWakeMode.progress = TorchDefaults.WAKE_MODE
            } finally {
                suppressSliderWrites = false
            }
            txtForceValue.text = getString(R.string.force_threshold_format, TorchDefaults.FORCE_THRESHOLD)
            txtWindowValue.text = "2. Double-Chop Window: ${TorchDefaults.CHOP_WINDOW_MS} ms"
            txtAutoOffValue.text = formatAutoOffLabel(TorchDefaults.AUTO_OFF_MINUTES)
            txtWakeModeValue.text = "4. Standby Mode: ${wakeModeLabels[TorchDefaults.WAKE_MODE]}"
            sessionPeakAccel = 0f
            txtLivePeakReadout.text = getString(R.string.live_peak_format, 0f, "Reset")
            DebugLog.event("sliders_reset", "reason" to "USER_RESTORED_DEFAULTS")
            Toast.makeText(this, R.string.reset_sliders_done, Toast.LENGTH_SHORT).show()
        }

        // 6. Button to Enable / Add Quick Settings Tile (Android 13+ StatusBarManager API)
        btnAddQsTile.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val statusBarManager = getSystemService(StatusBarManager::class.java)
                val tileComponent = ComponentName(this, ChopTileService::class.java)
                statusBarManager.requestAddTileService(
                    tileComponent,
                    getString(R.string.tile_label),
                    Icon.createWithResource(this, R.drawable.ic_chop_torch),
                    Executors.newSingleThreadExecutor()
                ) { result ->
                    DebugLog.event("tile_add_result", "result_code" to result)
                    runOnUiThread {
                        when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                Toast.makeText(this, "Quick Settings Tile added!", Toast.LENGTH_SHORT).show()
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                Toast.makeText(this, "Tile is already in Quick Settings.", Toast.LENGTH_SHORT).show()
                            else ->
                                Toast.makeText(this, "Tile prompt dismissed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(
                    this,
                    "Swipe down the notification shade and tap Edit (Pencil) to drag 'Chop Torch' into Quick Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // 7. OEM Unrestricted Battery Bypass Button (Samsung One UI / Pixel / Xiaomi)
        btnBatteryExempt.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Unrestricted battery already active.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Sync the master switch with the stored state without firing the listener. The service can be
     * disarmed from the notification or the Quick Settings tile while this screen is in the background.
     */
    private fun refreshServiceSwitch() {
        val enabled = prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)
        DebugLog.event("ui_arm_state", "reason" to "PREFERENCE_STATE_NOT_SERVICE_LIVENESS", "armed_pref" to enabled)
        switchService.setOnCheckedChangeListener(null)
        switchService.isChecked = enabled
        switchService.setOnCheckedChangeListener(serviceToggleListener)
    }

    /** Android 13+ hides the Disarm notification and the lock-screen torch alert without this. */
    private fun requestNotificationPermissionThenPromptFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            promptFullScreenIntentPermissionIfNeeded(force = false)
        }
    }

    /**
     * Opens the "full-screen notifications" settings page (Android 14+) when it is actually needed.
     * Automatic prompts happen at most once; [force] is for an explicit user action.
     */
    private fun promptFullScreenIntentPermissionIfNeeded(force: Boolean) {
        DebugLog.event("full_screen_permission_check", "forced" to force,
            "sdk" to Build.VERSION.SDK_INT, "screen_fallback_setting" to
                prefs.getBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, false))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val screenTorchInUse = !hasLedFlash ||
                prefs.getBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, false)
        if (!force && !screenTorchInUse) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.canUseFullScreenIntent()) return
        if (!force && prefs.getBoolean(ChopDetectionService.KEY_FSI_PROMPTED, false)) return

        prefs.edit { putBoolean(ChopDetectionService.KEY_FSI_PROMPTED, true) }
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = "package:$packageName".toUri()
        }
        try {
            startActivity(intent)
        } catch (error: Exception) {
            DebugLog.event("settings_launch_failed", "exception" to error.javaClass.simpleName,
                "message" to error.message)
        }
    }

    private fun formatAutoOffLabel(minutes: Int): String {
        return if (minutes == 0) {
            "3. Safety Auto-Shutoff Timer: Disabled (Manual Only)"
        } else {
            "3. Safety Auto-Shutoff Timer: $minutes min"
        }
    }

    override fun onResume() {
        super.onResume()
        if (DeviceEligibility.isMotorolaDevice()) return
        DebugLog.event("ui_resumed", "reason" to "CALIBRATION_READOUT_IS_NOT_GESTURE_ACCEPTANCE")
        refreshServiceSwitch()
        // Heal a service that should be armed but isn't running (force-stop, restored settings, OEM kill)
        if (prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)) {
            DebugLog.action("ui_resume_rearm") { startForegroundService(Intent(this, ChopDetectionService::class.java)) }
        }

        sessionPeakAccel = 0.0f
        gravityFilter.reset()
        val motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        motionSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        DebugLog.event("ui_paused", "reason" to "CALIBRATION_SENSOR_UNREGISTERED")
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val type = event.sensor.type
        if (type != Sensor.TYPE_LINEAR_ACCELERATION && type != Sensor.TYPE_ACCELEROMETER) return

        val v = event.values
        DebugLog.event("ui_motion_sample", "sensor_type" to type, "sensor_timestamp_ns" to event.timestamp,
            "x" to v[0], "y" to v[1], "z" to v[2],
            "armed_pref" to prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false))
        val lin = linearBuffer
        if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
            lin[0] = v[0]; lin[1] = v[1]; lin[2] = v[2]
        } else if (!gravityFilter.filter(v[0], v[1], v[2], SystemClock.elapsedRealtime(), lin)) {
            return
        }

        // Exact same plane filter + magnitude as ChopDetector, for 1:1 parity with the service
        if (!ChopDetector.isChopPlane(lin[0], lin[1], lin[2])) return
        val force = ChopDetector.magnitude(lin[0], lin[1], lin[2])
        if (force > sessionPeakAccel) {
            sessionPeakAccel = force
            val status = if (sessionPeakAccel >= currentThreshold) "Above threshold only; full gesture not confirmed" else "Below threshold"
            txtLivePeakReadout.text = getString(
                R.string.live_peak_format,
                sessionPeakAccel,
                status
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

