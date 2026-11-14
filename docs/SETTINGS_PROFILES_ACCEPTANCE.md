# Kinetic Torch 1.3.5 — calibration UI acceptance

Source baseline: signed 1.3.4 (`3d7348f`). This change is not yet built, signed, or submitted to Play.

## Test on an LED-equipped Samsung and Pixel

1. Update without uninstalling: existing force, window, timeout, wake mode, and armed state must survive.
2. Configure Lucas's known settings: **5.0 m/s²**, **451 ms**, **10 min**, **screen-wake + 15 s**. Reopen the screen; all four numbers must remain exact.
3. While unlocked, tap **Save custom profile**, name it `Fast Shake`, change one slider, and use **Restore saved settings → Fast Shake**. The original four values must return.
4. Tap **Lock settings**. The four sliders, reset, save, and restore buttons must be disabled. The gesture-control switch, Quick Settings tile action, battery action, and source link must remain available.
5. Reopen the app. Lock state must persist. A short tap must not change any tuning value; holding the lock control for two seconds must unlock it. Test the accessible confirmation path with TalkBack as well.
6. Change one value after unlocking. **Restore saved settings → Last locked settings** must recover the values captured when lock was set; the named custom profile must remain separate.
7. Reset to defaults. Only the four sliders return to **12.0 / 750 / 10 / 30-second window**. Arm state must not change.
8. Validate double-chop ON/OFF and haptics. Watch for false activations during ordinary hand movement; a preference UI change must not change gesture-detector logic.
9. Rotate / use gesture navigation / enlarge display text. Controls must stay inside safe insets and remain scrollable.
10. Verify the 1.3.4 LED-only behavior remains: no white-screen torch control, no arm on flashless devices, Motorola exclusion unchanged.

## Distribution gate

- Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` on a machine with Android SDK and Gradle 8.13.
- Verify an install-over-update from the existing signed beta; release builds must use the same NPFS signing identity.
- New 1.3.5/code 20 APK/AAB must be built and signed independently; never relabel the previously signed 1.3.4/code 19 artifacts.
