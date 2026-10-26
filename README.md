# S.T.A.R.S. Kinetic Torch

> **Migration status (October 26, 2026):** The GitHub repository currently contains the README and initial safety/provenance documentation. The archived Kotlin/Android source tree is prepared separately but has **not yet been committed**. The specifications below describe that prepared baseline, not verified contents of this repository.


**Kinetic Torch** is an Android flashlight utility exploring a user-configurable double-chop gesture to toggle a hardware flashlight, with a white-screen fallback and optional Quick Settings tile.

This public repository begins with a **historical, unverified source snapshot** recovered from the owner's Drive project archive. It is intended as a development baseline, **not a tested release**.

## Current baseline

- Android package: `com.npfs.stars.kinetictorch`
- Version configured in Gradle: `1.3.0` (`versionCode = 4`)
- Source archive: `KineticTorch_v1_3_1_AndroidStudio_Project.zip` (archive revision and configured app version differ)
- Kotlin + Android Gradle Plugin 8.6.1, Gradle 8.7 configuration, compile/target SDK 35, minimum SDK 26
- No ad or analytics SDKs are declared in `app/build.gradle.kts`.
- The manifest does not request CAMERA or INTERNET permission, and explicitly removes merged variants of INTERNET and ACCESS_NETWORK_STATE.

## Known limitations

- **Flashlight off-chop issue:** previously reported; not confirmed fixed or retested in this imported revision.
- `gradle/wrapper/gradle-wrapper.jar` is missing from the recovered archive. The checked-in `gradlew` scripts alone cannot run a build until the wrapper JAR is restored from a trusted source or the wrapper is regenerated.
- Android foreground-service, full-screen intent, and battery-optimization permission paths require review on currently supported devices and for store policies.
- Unit-test source exists, but **no build, unit tests, emulator tests, or device tests have been executed during this migration**.

## Development setup

1. Clone this repository in Android Studio with Android SDK / JDK 17 installed.
2. Reconstruct or regenerate the trusted Gradle wrapper JAR if needed; verify wrapper distribution and checksums.
3. Sync Gradle, then run unit tests with `gradlew testDebugUnitTest` once the wrapper is functional.
4. Test both ON and OFF chop detection, proximity/pocket guard, tile controls, and battery-use modes on real hardware.
5. Do not treat the current code as a Google Play-ready release without additional review.

## Contents

- `app/src/main/java/.../` — app activities, foreground gesture service, tile service, boot receiver, gesture detection
- `app/src/test/` — detector unit tests, currently unexecuted in this migration
- `app/src/main/res/` — layouts, strings, vector icons and themes
- `docs/PROVENANCE.md` — original archive identification and verification gaps
- `docs/TEST_PLAN.md` — suggested minimum verification matrix

## Privacy and security

This app is designed to perform local flashlight actions. Review the merged manifest and all runtime behavior before asserting permissions or privacy claims for any release. Do not upload signing keys, private user data, or device logs with personal identifiers.

## Licensing

**STARS Kinetic Torch software is licensed under GNU GPL version 3 only (`GPL-3.0-only`).** Read the complete terms in [`LICENSE`](./LICENSE). This applies to software STARS has the right to license; independently owned dependencies retain their applicable licenses. STARS and NPFS trademarks are not licensed by the software license.

The repository's historical Android source files are **not yet completely imported**, and a corresponding, reproducible source version for the uploaded beta APK has not been verified. The existence of a GPL license file is not a representation that the currently running APK has a complete published source package.

See the [STARS licensing policy](https://github.com/NPFS42/STARS-Master-Archive/blob/main/licensing/LICENSING_POLICY.md) if you have access to the private Master Archive.
