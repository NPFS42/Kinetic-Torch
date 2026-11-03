# Kinetic Torch work list — upload review, November 2, 2026 (Pacific)

## Status
SOURCE_RECOVERED; BUILD_AND_DEVICE_VALIDATION_PENDING. This review inspects source at commit `5ced8c4f6d4db23b980934a82cf0fdacf22d2a3e`, compares the Documents and downloaded trees, and reconciles issue #2 / draft PR #1. No fresh compile, Android test, or device run was performed. This review environment has no javac, kotlinc or Gradle on PATH.

## Completed by the upload
- [x] Recover and publish extracted Documents Android source on main.
- [x] Preserve the alternate downloaded tree under `historical/downloaded-v1_3_1/`.
- [x] Identify both trees as configured app version 1.3.0 / code 4; the ZIP name is not app-version proof.
- [x] Compare the source trees: ChopDetector (including GravityHighPass), detector test source and manifest are identical; five other Kotlin files, layout/strings and build configuration differ.
- [x] Locate recovery notes and pointers to privately archived APK/AAB/test evidence. Archive byte integrity was not rechecked in this review.

## P1 — Restore repeatable development
- [ ] Select and document one tested build toolchain. Documents uses AGP 8.13.2 / Gradle 8.13 with daemon JDK 21 criteria, while Java/Kotlin compilation targets 17. The downloaded tree uses AGP 8.6.1 / Gradle 8.7. Do not assume either builds from metadata alone.
- [ ] Restore a trusted Gradle wrapper JAR, record/verify wrapper and distribution integrity, then run a clean debug build and unit tests. Neither recovered Kinetic tree has the JAR.
- [ ] Remove embedded signing passwords from active build configuration and use external local signing configuration; retain the existing signing key privately for upgrade continuity. Debug development must not require production keys.
- [x] Locate all 13 historical detector/filter test methods: `GravityHighPassTest` is a second class inside `ChopDetectorTest.kt`, not a missing file. Content inspection corrected the earlier filename-based conclusion. The debug-logging branch's first CI build subsequently passed these 13 plus four trace tests (17 total); on-device behavior remains unverified.
- [ ] Review draft PR #1 against main; retain any useful APK provenance/offline regression checks, then retire the superseded import PR. Do not merge the older archive over Documents source.

## P1 — Diagnose and fix double-chop OFF (issue #2)
- [ ] Instrument detector acceptance, proximity blocking, sensor registration, light ownership and camera callbacks locally. An accepted gesture uses the same toggle path for ON and OFF; the two snapshots do not contain different detector logic.
- [ ] Compare immediate OFF attempts with a fresh gesture begun after the 1,000 ms cooldown. Cooldown is visible in source; it is not established as the owner's complete root cause.
- [ ] Test pocket-covered/uncovered transitions and LED/screen-torch callback synchronization, including camera takeover and external flashlight use.
- [ ] Review the Documents-only two-hour wake-lock timeout: acquisition occurs on activation, with no independent renewal timer visible. Test screen-off mode 5 after two hours and choose/document renewal or bounded-operation behavior. This is a source-derived reliability concern, not a reproduced device failure.
- [ ] Preserve reliable manual OFF / Disarm, auto-off and screen-dismissal behavior while fixing the gesture.

## P2 — Device acceptance
- [ ] Build/install a recorded debug APK; log device, Android version, commit, build tools, APK hash and results.
- [ ] Perform at least 30 ON/OFF cycles per applicable condition: unlocked/screen on, locked/screen off, LED, screen fallback; include delayed and immediate OFF, proximity transitions, external LED use and camera takeover.
- [ ] Check notification permission, tile, boot/restart, battery modes, auto-off and long-duration operation. Record missed OFF and false triggers.
- [ ] Compare archived APK metadata/code behavior against the chosen source; matching version numbers do not prove correspondence.
- [ ] Gate any tagged release on repeatable builds and device evidence. Store/policy review is required only if pursuing distribution; personal/family reliability remains the immediate goal.

## Scope boundary
No website deployment or application behavior changed during this work-list update. Any public page still claiming source is absent needs an editorial/source update and separate live verification; source recovery does not mean the OFF defect is fixed.
