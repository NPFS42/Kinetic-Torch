# Kinetic Torch — shared source, separate store identities

One repository and one release commit serve two Android distribution streams:

| Stream | Gradle variant | Application ID | Expected store artifact |
| --- | --- | --- | --- |
| Google Play | `playRelease` | `com.npfs.stars.kinetictorch` | `.aab` |
| F-Droid | `fdroidRelease` | `com.npfs.stars.kinetictorch.fdroid` | `.apk` built from source |

The F-Droid flavor retains the same app functionality, dependencies, permissions and source files. Its different package ID allows installation alongside the Play version. An F-Droid-specific launcher label identifies that copy on the device. App preferences, notifications, service identities, and update signatures are separate. Both apps control the same physical flash LED; only one should be armed for gesture listening at a time.

## Local build commands

From the repository root, with Android SDK and compatible JDK available:

```sh
./gradlew :app:testPlayDebugUnitTest :app:testFdroidDebugUnitTest
./gradlew :app:bundlePlayRelease
./gradlew :app:assembleFdroidRelease
```

The `play` flavor deliberately preserves the original application ID and version code. The legacy unflavored `bundleRelease`/`assembleRelease` Gradle tasks are replaced by flavored tasks. Any external signing/upload scripts must use `bundlePlayRelease` rather than `bundleRelease`.

## Installable test variant for F-Droid acceptance

GitHub Actions also builds and signs a **debug-only F-Droid flavor** for physical testing:

- Task: `:app:assembleFdroidDebug`
- Package: `com.npfs.stars.kinetictorch.fdroid.debug`
- Artifact: `kinetic-torch-fdroid-debug-APK-FOR-DEVICE-TESTING`

The `.debug` suffix lets testers install it beside the existing Play beta **without replacing either future store app**. Test sensor responsiveness, LED ON/OFF, haptics, Quick Settings, persisted calibration profiles, and co-installed behavior with only one listener armed at a time. Debug logging is enabled and stays local. This APK is signed with CI's temporary Android debug key; it is **not** the independently signed official F-Droid package, cannot update it, and must not be submitted as a store build. Remove the debug build after acceptance.

## Publishing responsibilities

1. Merge and validate shared source; check both build flavors and on-device gesture behavior.
2. Tag the exact reviewed release commit (e.g. `v1.3.5`), including the Fastlane listing metadata. Keep one versionName and versionCode for both streams.
3. For Play, build and sign the `playRelease` AAB using the existing private upload key. Submit it through Play Console under the original package ID.
4. For the official F-Droid repository, finalize the [staged build recipe](../distribution/fdroid/com.npfs.stars.kinetictorch.fdroid.yml.template) by replacing its placeholder with the exact reviewed tagged commit; then submit it as `metadata/com.npfs.stars.kinetictorch.fdroid.yml` to `fdroiddata`. Its `gradle: fdroid` entry builds the `fdroidRelease` variant. F-Droid performs its own review, source build, and signing.
5. The shared `fastlane/metadata/android/en-US/` files hold the approved title, descriptions, screenshots, and changelog for both streams. Do not duplicate or fork app code just for listing changes.

CI additionally inspects both debug packages and the F-Droid release candidate for their expected identities/versions, confirms none requests Internet/network-state or the deprecated full-screen-intent permission, and compares the packaged AGPLv3 license to the source copy. This is a build-level check, not physical gesture acceptance.

CI artifacts marked `UNSIGNED-DO-NOT-PUBLISH` are build-validation artifacts only, not production-signed releases. The F-Droid recipe is **not yet submitted**; it needs an exact release commit, successful build validation, and F-Droid review. Under F-Droid's inclusion policy, apps normally need three months of active development unless an exception (such as demonstrated users) applies; this new project may need evidence of its beta users. Google Play App Signing and the NPFS upload key need not match F-Droid signing because the application IDs differ.

**Artwork approach selected: retain STARS branding.** The owner approved a [specific copyright grant for original Kinetic Torch artwork plus official-app trademark redistribution permission](../ARTWORK-AND-TRADEMARKS.md). The original artwork identified there is AGPLv3-covered and can be modified/redistributed; the names and marks remain separately protected against misleading representations of origin. Before submission, verify the underlying rights and screenshot provenance and obtain F-Droid reviewer acceptance of this arrangement. Do not assume the grant extends to third-party graphics or any other STARS project.

Do not publish either stream from CI without a separately authorized release step. Never commit signing secrets or private keys.
