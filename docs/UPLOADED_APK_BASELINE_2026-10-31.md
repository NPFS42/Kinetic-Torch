# Kinetic Torch — user-uploaded APK baseline (2026-10-31)

**Source:** user-supplied `Kinetic Torch.apk`, analyzed as an Android binary. The APK was **not** published or committed to this public repository.

| Field | Observed |
|---|---|
| SHA-256 | `b8b67d6b157a10fa0036d7c50db275bd1b6eec5d7812b5ca2a8194afbe71f85f` |
| Size | 1,674,181 bytes |
| Package | `com.npfs.stars.kinetictorch` |
| APK version | `1.3.0`, versionCode `4` |
| SDK | min `26`, target `35`, compile `35` |
| Android Gradle plugin metadata | `8.6.1` |

This package and version **match** the `app/build.gradle.kts` in the owner-provided ZIP named `KineticTorch_v1_3_1_AndroidStudio_Project.zip`. The archive's filename is v1.3.1 but the APK/Gradle application version is **1.3.0**; this is not proof the files were compiled together or are byte-identical.

## Components and permission baseline

The manifest declares `MainActivity`, `ScreenTorchActivity`, `ChopDetectionService`, `ChopTileService`, and `BootReceiver`.

Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `USE_FULL_SCREEN_INTENT`; plus an app-scoped dynamic receiver permission. **No INTERNET or CAMERA permission is declared.** The flashlight API is `CameraManager.setTorchMode`, not a camera capture flow.

Compiled app symbols include `ChopDetector`, `GravityHighPass`, `ChopDetectionService.handleMotion`, `toggleActiveTorch`, `deactivateActiveTorch`, `setHardwareLedState`, `handleProximity`, `scheduleScreenOffWindow` and `onTorchModeChanged`.

## Reproducible offline finding
From the recovered `ChopDetector.kt` source (not from an Android device), an OFF gesture attempted less than 1,000 ms after the first successful toggle is ignored by the cooldown guard. The next valid double-chop, begun after the cooldown, triggers the detector. Reproduced with Kotlin CLI locally and covered by expanded source-import smoke tests.

**This does not prove the owner's reported double-chop OFF failure is only cooldown-related.** The foreground service, sensor registration, proximity/pocket guard, power state, screen torch and camera callback may also matter.

## Field acceptance needed
- Build a debug APK from reviewed source with the correct Gradle wrapper; compare manifest/package/version and debug behavior.
- Test ON→OFF with deliberate 2-second pause, then with immediate successive gestures; repeat across screen-on/off, device-lock state and pocket proximity.
- Log sensor events and detector return values before diagnosing the exact failure.
- Verify safe OFF fallbacks, especially when the LED was turned on elsewhere.
- Do not tag or merge as a fixed release without measured device results and permission review.

**Status:** uploaded APK identified and aligned to project version; original OFF bug still unverified on hardware.
