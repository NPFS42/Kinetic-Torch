# Motorola exclusion and resettable defaults

## Intent
Motorola provides its own proprietary chop-to-flashlight behavior. S.T.A.R.S. Kinetic Torch deliberately avoids enabling its competing gesture on Motorola phones, including sideloads.

## Runtime enforcement
`DeviceEligibility` tests the OEM manufacturer and retail brand, blocking `motorola` and `moto` (case-insensitive). MainActivity explains the exclusion; boot auto-start, foreground gesture detection, and the Quick Settings tile do not arm. Other Lenovo devices are not excluded.

## Google Play exclusion — **manual Play Console action required**
Per Google's [Device catalog documentation](https://support.google.com/googleplay/android-developer/answer/7353455), once an AAB has been uploaded, go to **Monitor and improve → Reach and devices → Device catalog**. Filter by manufacturer and manually exclude **each Motorola model**. Model exclusions apply across variants, and take effect for the entire app. Google Play does not document a blanket dynamic exclude-by-manufacturer rule: review newly introduced Motorola models periodically. Internal testers are an exception to Play model exclusion. Do not claim Play distribution is excluded until verified in the console.

## Default sliders (owner-tested baseline)
- Force threshold: **12.0 m/s²** (working owner setting)
- Double-chop window: **750 ms** (working owner setting)
- Automatic shutoff: **10 minutes**
- Standby: **Screen-Wake + 30 seconds**

The Reset Sliders button restores only these four settings, not the arm switch, screen-torch selection, notification prompts, or cooldown. Existing user choices survive app upgrades. The gesture defaults are known to work on the owner's device; cross-device validation is still required.

## Vibration confirmation (1.3.3)
- ON: two **240 ms** pulses separated by a **90 ms** silence; leave the proven ON pathway unchanged.
- Hardware LED OFF: one **220 ms** maximum-amplitude pulse, scheduled **140 ms** after an OS LED-off callback matching an in-flight app request. Android API 33+ specifies **USAGE_HARDWARE_FEEDBACK** for the OFF vibration to avoid silent background-usage rejection.
- Unrelated camera torch callbacks must not trigger a pulse. Cancel a pending OFF pulse on subsequent ON, service cleanup, or camera unavailability.
- Screen-white torch OFF still uses its direct toggle path and must be tested on device.
- A missing, disabled, or failing vibrator must not interrupt flashlight toggling. Neither a boot restart nor settings changes should vibrate.
- Device validation: owner confirmed the RC6 LED ON/OFF gestures and haptics on a Pixel 10 Pro XL. Broader devices, sleep states, screen-torch mode and accidental activation still require acceptance testing.

## Gesture reliability adjustments (RC3)
- Original quiet-valley criterion: movement had to fall below **45%** of active force threshold (5.4 m/s² at 12.0 m/s²).
- RC2 diagnostic samples showed **25** candidate attempts did not reach that level before expiring; their median minimum was around **9.41 m/s²**. RC3 uses an **80%** valley ratio (9.6 m/s² at the default ON threshold), encompassing **13/25** of those previously rejected minima. This is an empirical starting point, not proof that those 13 attempts were deliberate chops.
- Preserve a minimum **200 ms** from initial spike to accepted second spike, the screen-plane motion check, and the 1000 ms cooldown to reduce false triggers.
- OFF mode (light currently active, including lights started elsewhere) uses **75%** of the user's force threshold and adds **250 ms** to their selected double-chop window. ON mode retains the chosen threshold and window.
- Proximity blocking still applies when the torch is OFF, but cannot prevent turning an already-ON torch OFF.
- Keep the owner's configured 12.0 m/s² / 750 ms factory defaults and previously saved slider values. Test intentional gestures and accidental movement on hardware before release.

## Source/release caveat
The 1.3.3 source integrates the debug-logging and RC6 code paths on main and uses versionCode 18, above the previously reported 1.3.1 beta code 10. The user's tested RC6 APK had a different test package identity and debug signing certificate. Producing a production release still requires the original private signing/upload key, an actual package-upgrade/signature check and Play Console policy review. Updating main does not publish an app update.
