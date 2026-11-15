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

## Publishing responsibilities

1. Merge and validate shared source; check both build flavors and on-device gesture behavior.
2. Tag the exact reviewed release commit (e.g. `v1.3.5`), including the Fastlane listing metadata. Keep one versionName and versionCode for both streams.
3. For Play, build and sign the `playRelease` AAB using the existing private upload key. Submit it through Play Console under the original package ID.
4. For the official F-Droid repository, finalize the [staged build recipe](../distribution/fdroid/com.npfs.stars.kinetictorch.fdroid.yml.template) by replacing its placeholder with the exact reviewed tagged commit; then submit it as `metadata/com.npfs.stars.kinetictorch.fdroid.yml` to `fdroiddata`. Its `gradle: fdroid` entry builds the `fdroidRelease` variant. F-Droid performs its own review, source build, and signing.
5. The shared `fastlane/metadata/android/en-US/` files hold the approved title, descriptions, screenshots, and changelog for both streams. Do not duplicate or fork app code just for listing changes.

CI additionally inspects both APKs for their expected package/version identities and confirms neither app requests Internet or network-state permissions. This is a build-level check, not physical gesture acceptance.

CI artifacts marked `UNSIGNED-DO-NOT-PUBLISH` are build-validation artifacts only, not production-signed releases. The F-Droid recipe is **not yet submitted**; it needs an exact release commit, successful build validation, and F-Droid review. Under F-Droid's inclusion policy, apps normally need three months of active development unless an exception (such as demonstrated users) applies; this new project may need evidence of its beta users. Google Play App Signing and the NPFS upload key need not match F-Droid signing because the application IDs differ.

Before submission, also review [the project's license scope](../LICENSE-SCOPE.md): project code is AGPLv3 but STARS/NPFS branding, iconography and images do not automatically receive that grant. Check whether the artwork actually shipped in the APK and store screenshots is freely redistributable/modifiable under F-Droid's policies, and either document an appropriate asset permission or replace restricted assets in the F-Droid flavor. Do not assume the AGPL source-code license covers reserved marks or third-party graphics.

Do not publish either stream from CI without a separately authorized release step. Never commit signing secrets or private keys.
