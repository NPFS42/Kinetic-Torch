# AGPL-3.0-only APK and Play release gate

Project-owned application source is GNU AGPL version 3 only (`AGPL-3.0-only`), effective November 8, 2026. Earlier versions distributed under GPLv3 remain under those previous grants. Third-party source libraries, Android framework APIs and STARS trademarks retain their original rights.

## Before publishing any new APK / AAB

- [ ] Confirm all contributor rights and file-level notices; do not remove historical GPL grants.
- [ ] Verify the exact commit, versionName, versionCode, package ID, and signing certificate expected for the public build.
- [ ] Run instrumented/unit tests and validate torch ON/OFF, proximity protection, slider-reset behavior and OEM restrictions.
- [ ] Review Gradle dependency coordinates and include applicable upstream copyright/license notices.
- [ ] Check that the built APK contains `assets/COPYING` (full GNU AGPLv3 text).
- [ ] Confirm the app's Settings button opens [the publicly accessible source repository](https://github.com/NPFS42/Kinetic-Torch). No runtime network permission is required for this external browser action.
- [ ] Tag the precise source commit for the signed release, archive its build scripts and relevant dependencies, and ensure users can obtain corresponding source for that APK version at no charge.
- [ ] Verify Play Store release notes/listing identify the open-source license and provide the matching source/tag URL. Never point to unreviewed private repository content.
- [ ] Scan the source release and artifact for signing keys, keystore properties, local paths, user data and production secrets. Keep release credentials private.
- [ ] Only then publish a newly signed build; changing source files in GitHub does **not** release an APK or update Play Store.

**Status:** release gate prepared; no new signed Android package was published by this licensing work. Existing installed/previously distributed builds still need to be evaluated against their historic sources separately.

### Offline licensing

The full AGPL license is packaged under `app/src/main/assets/COPYING` for offline inspection in the built artifact. The new visible Settings button sends a user-initiated Android ACTION_VIEW intent to the public repository; it does not make network requests or add INTERNET permission.

### Third-party notes

AndroidX, Material Components, Kotlin, Gradle tooling and Android platform tools are not relicensed by the application root LICENSE. Verify their notices and distribution requirements from the exact resolved dependency graph on the release machine.
