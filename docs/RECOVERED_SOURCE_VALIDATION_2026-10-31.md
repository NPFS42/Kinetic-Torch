# Kinetic Torch v1.3.1 — recovered source audit (2026-10-31)

This note records inspection of the owner-supplied `KineticTorch_v1_3_1_AndroidStudio_Project.zip` present in draft PR #1. The archive has been extracted and inspected locally.

## Files positively identified
- Android app Gradle scripts, Manifest, UI resources and activities.
- `ChopDetectionService.kt`, `ChopDetector.kt`, `ChopTileService.kt`, `MainActivity.kt`, `BootReceiver.kt` and `ScreenTorchActivity.kt`.
- `ChopDetectorTest.kt` JVM test source.
- Gradle wrapper configuration specifying Gradle 8.7, **but no `gradle-wrapper.jar` in this archive**.

## Verification boundary
- Source files and tests exist; this is not a compiled or field-tested release.
- The archive reports versionName 1.3.0/versionCode 4 in its `app/build.gradle.kts`; the ZIP filename says v1.3.1. Preserve both labels until provenance is reconciled.
- A full Gradle/Android build has not been confirmed.
- The owner's **double-chop OFF defect remains unverified/failing pending device tests**; do not mark it fixed.
- No APK should be described as validated merely because it was recovered.

## Acceptance plan
1. Import extracted source into a reviewable branch, excluding signing credentials and local machine configuration.
2. Restore and verify standard Gradle wrapper; verify dependency integrity.
3. Run JVM gesture tests, then build debug APK.
4. Exercise ON/OFF gestures at least 30 cycles per condition: screen on, screen off, locked, unlocked, LED already on, and screen-torch mode.
5. Log false triggers and missed OFF gestures by Android version/device model.
6. Merge/tag only after reproducible results are recorded.

This is a provenance and recovery checkpoint, not a release.
