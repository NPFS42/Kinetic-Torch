# 1.3.5 — settings protection and interface refresh (unreleased)

- Adds a persistent calibration lock with a two-second touch-and-hold unlock; torch arming and Quick Settings remain usable while locked.
- Automatically captures a last-locked recovery snapshot; adds one named local custom profile and a confirmation-based restore flow.
- Restores the 5.0 m/s² lower-force slider setting used successfully by a Samsung tester while retaining existing defaults and detector logic.
- Redesigns the settings interface with dark translucent cards, a subdued STARS torch-mark background, clearer status, and grouped calibration/actions.
- Based on the signed but not yet published 1.3.4/code 19 LED-only candidate (`3d7348f`). This is source work only; Android build and hardware acceptance are pending.

---

# 1.3.4 — Unreleased (LED-only deprecation)

- Deprecated the white-screen fallback without deleting its source. The UI switch, activity
  manifest registration, full-screen intent permission, notification channel, auto-fallback,
  and service activation pathways are now disabled/commented out.
- Ignore previously stored white-screen preferences. No-flash devices cannot arm the service,
  including through Quick Settings and automatic boot restoration.
- A busy or unavailable camera LED now reports the problem instead of opening the screen.
- Updated manifest hardware requirement to a physical flash LED and revised the FGS subtype.
- VersionCode 19 / versionName 1.3.4. Shop PC has signed APK/AAB artifacts from commit `3d7348f`; hardware acceptance and distribution status remain separate checks.

---

# 1.3.3 — November 5, 2026

## Tested changes
- Owner-confirmed working on Pixel 10 Pro XL (Android 17) using RC6 test APK: double-chop LED ON and OFF, including the distinct ON and OFF haptic confirmation.
- Improved double-chop recognition: inter-chop quiet valley increased from 45% to 80% of the active threshold, with a 200 ms minimum gap to reject recoil; OFF uses 75% of selected force and 250 ms longer completion window.
- While torch is ON, proximity guard permits OFF. Covered proximity still prevents unintended ON.
- Flashlight ON confirmation remains two 240 ms vibrations with a 90 ms gap. LED OFF confirmation uses one 220 ms pulse, 140 ms after a matching OS torch-off callback, with Android hardware-feedback vibration usage on supported versions. No vibration is triggered by unrelated camera callbacks.
- Default force threshold **12 m/s²** and double-chop window **750 ms**; Reset sliders restores calibrated settings without resetting arm state or user preferences.
- Motorola/Moto device exclusion, edge-to-edge safe-area fixes for settings and white-screen torch, and local diagnostic logging on debug builds.
- Release package stays `com.npfs.stars.kinetictorch`, versionName **1.3.3**, versionCode **18**. Production signing requires the existing private key (never committed). No Play Store upload is performed by this source merge.

## Verification boundaries
- Automated tests and CI assembly cover the debug and diagnostic variants. A physical test of the side-by-side RC6 debug candidate confirms the main LED gesture/haptic behavior, but it is not proof of production-signing continuity, Play policy compliance, or behavior on every Android device.
- Full multi-device, screen-locked, screen-torch, long-duration and battery-use acceptance remains outstanding. This is a source release, not a declaration of Play deployment.

---

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
