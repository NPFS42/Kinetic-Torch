# Recovered Android Studio source — v1.3.1

**Status: imported source archive; build and real-device behavior NOT verified.**

Source ZIP: [KineticTorch_v1_3_1_AndroidStudio_Project.zip](../imports/KineticTorch_v1_3_1_AndroidStudio_Project.zip)

Provenance: user-owned Google Drive file named `KineticTorch_v1_3_1_AndroidStudio_Project.zip`, archived October 26, 2026. ZIP contents dated October 17, 2026.

Included in ZIP:
- Gradle configuration and Android manifest / resources
- `ChopDetectionService.kt`, `ChopDetector.kt`, `MainActivity.kt`
- `ScreenTorchActivity.kt`, `BootReceiver.kt`, `ChopTileService.kt`
- `ChopDetectorTest.kt`, changelog, wrapper *configuration*
  
The ZIP contains no complete Gradle wrapper JAR; if necessary, install a compatible Gradle version or regenerate the wrapper on a trusted development machine. Never run downloaded code without inspecting it.

## Known issue

User reported on October 22: **double-chop turns the torch on, but the double-chop off operation does not respond**. The source imported here predates that report; it must not be described as fixed.

## Release gates

1. Extract archive into Android Studio; confirm Gradle/SDK setup and run an actual debug build.
2. Run the bundled unit tests and report their real results.
3. On supported test devices, repeatedly verify OFF transition (including while the screen is off or the phone is locked).
4. Validate proximity/pocket guard, other-camera takeover, auto-shutoff, reboot behavior, power drain and battery restrictions.
5. Reconcile this archive with any *newer* source on the shop and home PCs before replacing the main project.
6. Only after that, move a reviewed *extracted* source tree into the repository, tag a build and publish a signed APK.

## Confidentiality

This import contains only the Kinetic Torch project archive, not Jarvis or STARS master-archive materials.
