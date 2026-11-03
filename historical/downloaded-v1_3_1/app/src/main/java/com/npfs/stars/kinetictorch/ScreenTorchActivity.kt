package com.npfs.stars.kinetictorch

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen 100% pure white (#FFFFFF) activity at 100% hardware screen brightness (1.0f).
 * Launched via full-screen high-priority notification intent (when locked/backgrounded) or direct
 * intent when a device has no rear camera LED flash unit or when Screen Torch mode is enabled.
 *
 * The torch is only "on" while this screen is showing. However it ends — tap, Back, Home, screen
 * off, or the service asking us to close — the service is told, so its state never drifts.
 */
class ScreenTorchActivity : AppCompatActivity() {

    companion object {
        const val ACTION_CLOSE_SCREEN_TORCH = "com.npfs.stars.kinetictorch.ACTION_CLOSE_SCREEN_TORCH"
        const val ACTION_USER_DISMISSED_SCREEN_TORCH = "com.npfs.stars.kinetictorch.ACTION_USER_DISMISSED_SCREEN_TORCH"
        @Volatile
        var isScreenTorchVisible = false
    }

    /** True when the service itself asked us to close, so it doesn't need to be told again. */
    private var closedByService = false
    private var visibleSinceUptimeMs = 0L

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_SCREEN_TORCH) {
                closedByService = true
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: paint white behind the status bar, nav bar and display cutout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Force 100% maximum hardware screen brightness (1.0f)
        val params = window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = params

        // Pure 100% White (#FFFFFF) root container; tap to dismiss manually. The dismissal is
        // reported to the service from onDestroy so Back/Home/screen-off take the same path.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            keepScreenOn = true
            setOnClickListener { finishAndRemoveTask() }
        }

        val bottomMarginPx = (32 * resources.displayMetrics.density).toInt()
        val hintText = TextView(this).apply {
            text = "SCREEN TORCH 100% • DOUBLE-CHOP OR TAP TO TURN OFF"
            setTextColor(Color.parseColor("#757575"))
            textSize = 11f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = bottomMarginPx
            }
        }
        root.addView(hintText)
        setContentView(root)

        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val filter = IntentFilter(ACTION_CLOSE_SCREEN_TORCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        isScreenTorchVisible = true
        visibleSinceUptimeMs = SystemClock.elapsedRealtime()
        // Dismiss the high-priority full-screen launch notification once the activity is visible
        getSystemService(NotificationManager::class.java)
            ?.cancel(ChopDetectionService.SCREEN_TORCH_NOTIFICATION_ID)
    }

    override fun onStop() {
        isScreenTorchVisible = false
        // Home, screen-off, an incoming call, etc.: a screen torch that isn't showing isn't a torch.
        // (Rotation/config changes are declared in the manifest, so they never reach here.)
        // Guard against the 100-300 ms lock-screen wake lifecycle bounce (onResume -> onStop -> onResume)
        // when waking a sleeping display via fullScreenIntent; only auto-finish once stable for >= 600 ms.
        val awakeDurationMs = SystemClock.elapsedRealtime() - visibleSinceUptimeMs
        if (!isFinishing && !isChangingConfigurations && visibleSinceUptimeMs > 0L && awakeDurationMs >= 600L) {
            finishAndRemoveTask()
        }
        super.onStop()
    }

    override fun onDestroy() {
        isScreenTorchVisible = false
        getSystemService(NotificationManager::class.java)
            ?.cancel(ChopDetectionService.SCREEN_TORCH_NOTIFICATION_ID)
        if (isFinishing && !closedByService) {
            // Tap / Back / Home / screen-off: tell the service so it stops thinking the torch is on
            sendBroadcast(Intent(ACTION_USER_DISMISSED_SCREEN_TORCH).setPackage(packageName))
        }
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
