# S.T.A.R.S. Kinetic Torch

Android flashlight utility with double-chop gesture detection, white-screen fallback, and Quick Settings tile.

## Source recovered — November 2, 2026
The project root contains the recovered Documents working source (configured version 1.3.0, code 4). The alternate downloaded source is preserved in `historical/downloaded-v1_3_1/`; the archive filename is not proof of application version. These trees differ in five Kotlin implementation files, layout and strings. Neither has been established as the source of the distributed beta APK.

Package: `com.npfs.stars.kinetictorch`. Android SDK 35, minimum 26, JDK 17. Read `docs/LOCAL_RECOVERY_2026-11-02.md` for evidence and remaining work.

## Development and remaining work
Open the root project in Android Studio. The Gradle wrapper JAR is absent from both recovered copies; regenerate it from a trusted Gradle installation before building. Local signing keys and machine-specific configuration are excluded. The recovered release configuration references a local signing key; configure your own signing or use a debug build.

The double-chop OFF defect remains unresolved/unverified. No fresh build or device validation was performed for this import. Test ON/OFF detection, proximity guard, Quick Settings controls, background behavior, notifications, screen fallback and battery use on real hardware. Stored build/test artifacts are historical evidence only.

## License
GNU GPL version 3 only (`GPL-3.0-only`); see LICENSE. Dependencies retain their licenses; STARS/NPFS trademarks are excluded. Publishing this source does not establish correspondence to the existing beta APK.
