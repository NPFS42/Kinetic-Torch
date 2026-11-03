# Debug logging branch

Branch: `debug-logging`. This is a diagnostic build, not a claim that the torch defect is fixed.

## Install and capture on the phone
1. Build the debug variant on this branch, or download `kinetic-torch-debug-logging` from its successful GitHub Actions run and extract the APK.
2. Install **Kinetic Torch Debug** (`com.npfs.stars.kinetictorch.debug`). It installs alongside the normal app with separate settings. Disarm the normal app while testing so two gesture services do not compete for the torch.
3. Open the debug app, choose the same sensitivity/power settings, and arm it. Allow notifications so Disarm/Off is available.
4. Tap **Mark test attempt**, do the double-chop, wait a few seconds, then try OFF. Include a test with a two-second pause between completed gestures. Repeat locked/screen-off if needed.
5. Tap **Export debug log**, choose a destination, then attach the `.jsonl` file to the chat. Export soon after reproducing; oldest records rotate away.

No cable or Logcat is required for export. The current UI calibration peak is explicitly labelled as threshold evidence only: it is not proof that a complete gesture was accepted or that the service is alive.

## Recorded evidence
- Wall/monotonic times, process session, sequence, thread, app version, device model and Android API.
- UI calibration samples separately from service samples; raw sensor axes/type/timestamps and filtered axes/magnitude.
- Detector phases and reasons: threshold, plane, first chop, quiet valley, recoil, elapsed gesture window, cooldown and completed double-chop. Gesture IDs connect decisions to toggle requests; LED request IDs connect commands to matching callbacks.
- Proximity/pocket blocking, gravity-filter warmup, sensor registration success/failure, service/boot/tile/UI lifecycle, arming state and settings.
- Screen/lock/power mode, wake-lock state, sleep/auto-off timers, camera availability, chosen light route, API success/exception/stack, authoritative LED callbacks, unconfirmed request timeouts, screen-torch visibility/dismissal and notification/full-screen permission state.
- Two-second service heartbeat with age of last motion sample; queue overflow and storage failures are explicit. Uncaught exceptions are recorded best-effort before passing to Android's original handler.

An API returning without an exception is **not** logged as confirmed physical activation. Screen launch nonvisibility is recorded as observed; the app cannot infer an OS reason Android did not expose. Abrupt force-stop, kernel kill and power loss cannot reliably log their own cause or guarantee the final queued records are persisted.

## Storage and overhead
Logs remain in app-private internal storage until the user exports them. No network/storage permission is added. A bounded 4,096-record queue writes on a background thread to four rotating files of about 2 MiB each (about 8 MiB retained). Overflow records how many events were lost. Raw streams are file-only; decisions also use Logcat tag `KineticTorchDebug`. Export flushes prior queued records, then copies retained files oldest first off the UI thread.

Logging is guarded by `BuildConfig.DEBUG`; release builds do not capture/export logs and hide the debug panel. Diagnostic logging increases battery/CPU usage; use this build for reproduction rather than unattended daily operation. Existing app control policy, threshold defaults and pocket/auto-off protections are retained.

## Build and verification
The recovered root toolchain is AGP 8.13.2 / Gradle 8.13 with Java/Kotlin target 17 and local daemon JDK 21 criteria. The CI job installs JDK 21 and Android 35, uses installed Gradle 8.13 directly (the recovered wrapper JAR is missing), and removes the local daemon-vendor selection only in the runner workspace. It runs existing detector/filter tests plus trace-reason and observer-isolation regressions before assembling the debug APK. No production signing key is used.

Physical acceptance still requires a phone: export after failed/successful gestures; confirm chronological JSONL, screen-off behavior, camera callbacks, pocket guard, manual OFF, rotation and device restart. Do not mark the underlying ON/OFF defect fixed based on compilation alone.

Source-review correction: `GravityHighPassTest` is present as a second class inside `ChopDetectorTest.kt` (three methods), despite no separately named test file. The earlier upload work list's missing-test-source item was based on filenames and is superseded by this content inspection.
