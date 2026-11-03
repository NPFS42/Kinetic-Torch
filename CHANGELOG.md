# 1.3.0

## Fixed
- Request POST_NOTIFICATIONS (Android 13+) when arming; Disarm button and lock-screen alert now appear.
- Screen torch: notification is tappable; service reports if Android blocked the launch; Back/Home/screen-off
  now tell the service (no more "double-chop twice" desync); rotation no longer recreates the activity.
- Full-screen-intent settings page shown once on arming / on explicit switch flip, not on every launch.
- Edge-to-edge insets (Android 15), dp-based hint margin, display-cutout fill, readable hint colour.
- Gesture detector rewritten on gravity-free linear acceleration (TYPE_LINEAR_ACCELERATION, with a
  high-pass fallback); extracted into ChopDetector.kt with unit tests; min 200 ms between counted spikes.
- Proximity "covered" test now handles sensors that report ~3 cm as near.
- LED ownership tracking: a flashlight turned on elsewhere is no longer auto-shut-off or killed on stop.
- Master switch re-syncs in onResume; an armed-but-dead service is restarted when the app opens.
- Repeat startForegroundService() calls are always answered with startForeground().
- allowBackup=false (stops "armed" flag being restored onto a new device).
- Adaptive + themed launcher icon; white glyph for tile/notification.
- Registered dynamic broadcast receivers with explicit `RECEIVER_NOT_EXPORTED` flags (`systemReceiver`, `closeReceiver`).
- Upgraded switches in `activity_main.xml` to Material Design `MaterialSwitch`.
- Extracted UI string literals to `strings.xml`, adopted `SharedPreferences` KTX extensions, and added `WakeLock` safety timeout.
- AGP 8.6.1 (official compileSdk 35 support). Version 1.3.0 / code 4.

## Not fixed (needs your machine / a decision)
- gradle-wrapper.jar is missing: open in Android Studio, or run `gradle wrapper --gradle-version 8.7`.
- Play policy review risk: USE_FULL_SCREEN_INTENT, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, specialUse FGS.
- Always-On mode (5) still holds a wake lock + 50 Hz sampling; it is labelled "high battery use".
- Detector defaults (18.5 m/s², 650 ms) were tuned on raw-accelerometer semantics; re-tune with the live readout.
