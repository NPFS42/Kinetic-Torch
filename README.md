# S.T.A.R.S. Kinetic Torch

Offline, ad-free Android flashlight utility with double-chop gesture control, white-screen fallback, and Quick Settings tile.

## Current source: 1.3.3 (versionCode 18)

Package: `com.npfs.stars.kinetictorch` · minSdk 26 · targetSdk 36 · compileSdk 36 · Java/Kotlin 17. The debug application installs separately under `com.npfs.stars.kinetictorch.debug`.

The 1.3.3 source integrates the double-chop fixes and haptic confirmation tested on a Pixel 10 Pro XL (Android 17). In the final RC6 side-by-side candidate, the owner confirmed both LED ON/OFF and the haptic feedback worked. Other device and screen-torch scenarios still need validation.

- Gesture defaults: **12 m/s²**, **750 ms**; Reset sliders restores those and the other slider defaults without changing arm state.
- More forgiving OFF gesture: **75%** of configured force, **+250 ms** timing allowance; adjusted inter-chop quiet-valley detector with a 200 ms recoil guard.
- Vibration: **two 240 ms pulses** for ON; **one 220 ms pulse** for OFF after OS LED-off confirmation, classified as Android hardware feedback.
- Proximity blocks covered accidental ON but never prevents deliberate OFF of an active torch.
- Motorola/Moto gestures are disabled in-app to avoid conflicting with the OEM feature; store-side model exclusions remain a separate Play Console task.
- The settings and white-screen torch respect modern Android status/navigation insets.
- Debug builds record local diagnostic events; release builds disable diagnostic recording via `BuildConfig.DIAGNOSTICS_ENABLED=false`. No internet account or data transfer is required.

See [CHANGELOG.md](CHANGELOG.md) and [docs/MOTOROLA_EXCLUSION_AND_DEFAULTS.md](docs/MOTOROLA_EXCLUSION_AND_DEFAULTS.md) for implementation and validation notes.

## Build and distribution

The GitHub Actions workflow verifies unit tests, builds a debug APK and a release-like diagnostic variant, and checks the debug APK signature. Debug APKs are **not** the Play Store update.

Configure the original app signing or Play upload keystore through private environment variables `KINETIC_KEYSTORE_PATH`, `KINETIC_KEYSTORE_PASSWORD`, `KINETIC_KEY_ALIAS`, and `KINETIC_KEY_PASSWORD` on the trusted release machine; never commit keys or passwords. Confirm upgrade signature compatibility with the published beta before distributing a release APK. Moving source to `main` does not deploy a Play update.

## Historical source recovery

On November 2, 2026, recovered working source was imported as version 1.3.0/code 4. An alternate tree is retained in `historical/downloaded-v1_3_1/`; its archive name is not proof of the APK's actual version. The earlier assessment at `docs/LOCAL_RECOVERY_2026-11-02.md` and `docs/UPLOAD_ANALYSIS_2026-11-02.md` is historical and predates the 1.3.3 device testing.

## License

GNU GPL version 3 only (`GPL-3.0-only`); see [LICENSE](LICENSE). Dependencies retain their own licenses; STARS/NPFS trademarks are excluded.
