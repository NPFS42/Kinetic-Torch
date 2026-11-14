# White-screen torch retirement (1.3.4)

**Status:** Deprecated and disabled for the next release. The implementation has not been deleted.

- `ScreenTorchActivity.kt` remains in source and is marked deprecated; the `<activity>`
  registration in `AndroidManifest.xml` is commented out.
- The full-screen notification permission is commented out of the manifest, and the old
  settings permission prompt is commented out in `MainActivity.kt`.
- The white-screen settings control in `activity_main.xml` and its setup code in `MainActivity.kt`
  are commented out.
- The service's white-screen launch, fallback, broadcast receiver and high-priority alert
  channel are commented out. The active path only toggles the physical LED.
- Old `use_screen_torch` and `fsi_prompted` preferences are kept for compatibility but ignored.
- Flash hardware is now required for device eligibility; missing/busy LEDs do not trigger a
  screen-based substitute. An existing legacy alert is cancelled on service shutdown.
- Historical screen-torch strings, theme and source are retained for a deliberate future revival.

## Before restoring

Re-review Google Play's full-screen-intent policy, test the screen-wake and notification paths
on current Android devices, update the Play disclosure/marketing text, and deliberately re-enable
the manifest permission, activity, UI switch and service route together.

## Release boundary

The Play Console's already-uploaded 1.3.3/code 18 AAB still contains the older functionality.
This source change needs a fresh signed 1.3.4/code 19 AAB and a revised listing that no longer
advertises a screen torch. Completing the foreground-service declaration remains a separate task.
