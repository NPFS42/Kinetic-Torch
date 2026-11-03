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

## Source/release caveat
This change is based on `debug-logging`, which contains the current diagnostic source. Do not ship it as an APK/AAB without reconciling it with the owner's installed working 1.3.1 production build (code 10), verifying the signing key and increasing the Play version code above 10. No released artifact is produced by this change alone.
