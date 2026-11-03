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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private lateinit var txtLivePeakReadout: TextView
    private lateinit var switchService: Switch
    private var hasLedFlash = false
    private var sessionPeakAccel = 0.0f
    private var currentThreshold = 18.5f

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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
        prefs.edit().putBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, checked).apply()
        val intent = Intent(this, ChopDetectionService::class.java)
        if (checked) {
            startServiceCompat(intent)
            requestNotificationPermissionThenPromptFullScreen()
        } else {
            // Calls onDestroy() -> shutdownTorchAndCleanup() to guarantee torch turns off
            stopService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15 enforces edge-to-edge at targetSdk 35: draw behind the bars, then pad by insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.rootScroll)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        prefs = getSharedPreferences(ChopDetectionService.PREFS_NAME, Context.MODE_PRIVATE)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val txtHardwareStatus = findViewById<TextView>(R.id.txtHardwareStatus)
        txtLivePeakReadout = findViewById(R.id.txtLivePeakReadout)
        switchService = findViewById(R.id.switchServiceEnable)
        val switchScreenTorch = findViewById<Switch>(R.id.switchScreenTorchFallback)

        val seekForce = findViewById<SeekBar>(R.id.seekForceThreshold)
        val txtForceValue = findViewById<TextView>(R.id.txtForceValue)

        val seekWindow = findViewById<SeekBar>(R.id.seekChopWindow)
        val txtWindowValue = findViewById<TextView>(R.id.txtWindowValue)

        val seekAutoOff = findViewById<SeekBar>(R.id.seekAutoOffMinutes)
        val txtAutoOffValue = findViewById<TextView>(R.id.txtAutoOffValue)

        val seekWakeMode = findViewById<SeekBar>(R.id.seekWakeMode)
        val txtWakeModeValue = findViewById<TextView>(R.id.txtWakeModeValue)

        val btnAddQsTile = findViewById<Button>(R.id.btnAddQuickSettingsTile)
        val btnBatteryExempt = findViewById<Button>(R.id.btnBatteryExemption)

        // Hardware Audit: Check Rear/Front Flash LED and Proximity Sensor presence
        val flashId = ChopDetectionService.findFlashCameraId(cameraManager)
        hasLedFlash = (flashId != null)
        val hasProximity = (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null)

        val ledStatus = if (hasLedFlash) "Rear LED Flash ($flashId)" else "No LED (100% White Screen Torch Active)"
        val proxStatus = if (hasProximity) "Proximity Guard: Ready" else "No Proximity Sensor (Use Screen-Wake Mode)"
        txtHardwareStatus.text = "$ledStatus • $proxStatus"

        // Full-Screen 100% White Screen Torch Switch (Auto-locked ON if device has no LED).
        // The full-screen-intent permission page is NOT opened here any more: it is requested when the
        // user arms the service (once) or flips this switch on, never on every app launch.
        if (!hasLedFlash) {
            prefs.edit().putBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, true).apply()
            switchScreenTorch.isChecked = true
            switchScreenTorch.isEnabled = false
            switchScreenTorch.text = "100% White Screen Torch (Auto-Enabled: No LED Detected)"
        } else {
            switchScreenTorch.isChecked = prefs.getBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, false)
            switchScreenTorch.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, checked).apply()
                if (checked) {
                    promptFullScreenIntentPermissionIfNeeded(force = true)
                }
            }
        }

        // 1. Master Service Toggle (state is re-synced from prefs in onResume)
        refreshServiceSwitch()

        // 2. Chop Force Threshold Slider (10.0 to 35.0 m/s^2, mapped as progress 0..250)
        currentThreshold = prefs.getFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, 18.5f)
        seekForce.max = 250
        seekForce.progress = ((currentThreshold - 10.0f) * 10).toInt().coerceIn(0, 250)
        txtForceValue.text = String.format("1. Chop Force Threshold: %.1f m/s²", currentThreshold)

        seekForce.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = 10.0f + (progress / 10.0f)
                txtForceValue.text = String.format("1. Chop Force Threshold: %.1f m/s²", currentThreshold)
                prefs.edit().putFloat(ChopDetectionService.KEY_FORCE_THRESHOLD, currentThreshold).apply()
                sessionPeakAccel = 0.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Double-Chop Time Window Slider (300 ms to 1000 ms, mapped as progress 0..700)
        val savedWindow = prefs.getInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, 650)
        seekWindow.max = 700
        seekWindow.progress = (savedWindow - 300).coerceIn(0, 700)
        txtWindowValue.text = "2. Double-Chop Window: ${savedWindow} ms"

        seekWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val windowMs = 300 + progress
                txtWindowValue.text = "2. Double-Chop Window: ${windowMs} ms"
                prefs.edit().putInt(ChopDetectionService.KEY_CHOP_WINDOW_MS, windowMs).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. Auto-Shutoff Safety Timer Slider (0 = Disabled, 1 to 30 minutes)
        val savedAutoOff = prefs.getInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, 10)
        seekAutoOff.max = 30
        seekAutoOff.progress = savedAutoOff.coerceIn(0, 30)
        txtAutoOffValue.text = formatAutoOffLabel(savedAutoOff)

        seekAutoOff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtAutoOffValue.text = formatAutoOffLabel(progress)
                prefs.edit().putInt(ChopDetectionService.KEY_AUTO_OFF_MINUTES, progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 5. Standby Power Mode Slider (0 = Screen-On Only .. 2 = Screen-Wake + 30s .. 5 = Always-On 24/7)
        val savedWakeMode = prefs.getInt(ChopDetectionService.KEY_WAKE_MODE, 2).coerceIn(0, 5)
        seekWakeMode.max = 5
        seekWakeMode.progress = savedWakeMode
        txtWakeModeValue.text = "4. Standby Mode: ${wakeModeLabels[savedWakeMode]}"

        seekWakeMode.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mode = progress.coerceIn(0, 5)
                txtWakeModeValue.text = "4. Standby Mode: ${wakeModeLabels[mode]}"
                prefs.edit().putInt(ChopDetectionService.KEY_WAKE_MODE, mode).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

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
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Unrestricted battery already active.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Sync the master switch with the stored state without firing the listener. The service can be
     * disarmed from the notification or the Quick Settings tile while this screen is in the background.
     */
    private fun refreshServiceSwitch() {
        val enabled = prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val screenTorchInUse = !hasLedFlash ||
                prefs.getBoolean(ChopDetectionService.KEY_USE_SCREEN_TORCH, false)
        if (!force && !screenTorchInUse) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.canUseFullScreenIntent()) return
        if (!force && prefs.getBoolean(ChopDetectionService.KEY_FSI_PROMPTED, false)) return

        prefs.edit().putBoolean(ChopDetectionService.KEY_FSI_PROMPTED, true).apply()
        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {}
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
        refreshServiceSwitch()
        // Heal a service that should be armed but isn't running (force-stop, restored settings, OEM kill)
        if (prefs.getBoolean(ChopDetectionService.KEY_SERVICE_ENABLED, false)) {
            startServiceCompat(Intent(this, ChopDetectionService::class.java))
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
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val type = event.sensor.type
        if (type != Sensor.TYPE_LINEAR_ACCELERATION && type != Sensor.TYPE_ACCELEROMETER) return

        val v = event.values
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
            val status = if (sessionPeakAccel >= currentThreshold) "PASS (Triggers Chop)" else "Below Threshold"
            txtLivePeakReadout.text = String.format(
                "Live Chop-Plane Peak: %.1f m/s² [%s]",
                sessionPeakAccel,
                status
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
