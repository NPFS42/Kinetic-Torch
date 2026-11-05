# Motorola exclusion and resettable defaults

## Intent
Motorola provides its own proprietary chop-to-flashlight behavior. S.T.A.R.S. Kinetic Torch deliberately avoids enabling its competing gesture on Motorola phones, including sideloads.

## Runtime enforcement
`DeviceEligibility` tests the OEM manufacturer and retail brand, blocking `motorola` and `moto` (case-insensitive). MainActivity explains the exclusion; boot auto-start, foreground gesture detection, and the Quick Settings tile do not arm. Other Lenovo devices are not excluded.

## Google Play exclusion — **manual Play Console action required**
Per Google's [Device catalog documentation](https://support.google.com/googleplay/android-developer/answer/7353455), once an AAB has been uploaded, go to **Monitor and improve → Reach and devices → Device catalog**. Filter by manufacturer and manually exclude **each Motorola model**. Model exclusions apply across variants, and take effect for the entire app. Google Play does not document a blanket dynamic exclude-by-manufacturer rule: review newly introduced Motorola models periodically. Internal testers are an exception to Play model exclusion. Do not claim Play distribution is excluded until verified in the console.

## Default sliders (provisional)
- Force threshold: **12.0 m/s²** (working owner setting)
- Double-chop window: **750 ms** (working owner setting)
- Automatic shutoff: **10 minutes**
- Standby: **Screen-Wake + 30 seconds**

The Reset Sliders button restores only these four settings, not the arm switch, screen-torch selection, notification prompts, or cooldown. Existing user choices survive app upgrades. The gesture defaults are known to work on the owner's device; cross-device validation is still required.

## Vibration confirmation (RC2+)
- ON: two **240 ms** pulses separated by a **90 ms** silence.
- OFF: one **220 ms** pulse.
- A haptic is emitted from the actual LED/screen torch toggle path, not from the asynchronous camera LED callback, so matching callbacks don't double-vibrate.
- A missing, disabled, or failing vibrator must not interrupt flashlight toggling. Neither a boot restart nor settings changes should vibrate.
- Test both LED and screen torch modes on hardware: successful double-chop ON yields two pulses, successful double-chop OFF yields one pulse, failed activation produces no success signal, and automatic safety shutoff behavior remains unchanged.

## Gesture reliability adjustments (RC3)
- Original quiet-valley criterion: movement had to fall below **45%** of active force threshold (5.4 m/s² at 12.0 m/s²).
- RC2 diagnostic samples showed **25** candidate attempts did not reach that level before expiring; their median minimum was around **9.41 m/s²**. RC3 uses an **80%** valley ratio (9.6 m/s² at the default ON threshold), encompassing **13/25** of those previously rejected minima. This is an empirical starting point, not proof that those 13 attempts were deliberate chops.
- Preserve a minimum **200 ms** from initial spike to accepted second spike, the screen-plane motion check, and the 1000 ms cooldown to reduce false triggers.
- OFF mode (light currently active, including lights started elsewhere) uses **75%** of the user's force threshold and adds **250 ms** to their selected double-chop window. ON mode retains the chosen threshold and window.
- Proximity blocking still applies when the torch is OFF, but cannot prevent turning an already-ON torch OFF.
- Keep the owner's configured 12.0 m/s² / 750 ms factory defaults and previously saved slider values. Test intentional gestures and accidental movement on hardware before release.

## Source/release caveat
This change is based on `debug-logging`, which contains the current diagnostic source. Do not ship it as an APK/AAB without reconciling it with the owner's installed working 1.3.1 production build (code 10), verifying the signing key and increasing the Play version code above 10. No released artifact is produced by this change alone.
