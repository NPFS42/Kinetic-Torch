# Known issues and release blockers

- Double-chop gesture activation was reported to work, but the subsequent gesture to turn the flashlight **off** was not behaving reliably. This has not been verified fixed.
- The recovered source ZIP lacks `gradle/wrapper/gradle-wrapper.jar`; restore from a trusted wrapper before building.
- Foreground-service, full-screen intent and battery-optimization permissions need current Android and Play policy review.
- Build, unit tests and on-device validation have not been performed during migration.
- No open-source license has been selected for public redistribution.

**Do not publish an APK as a working release solely on the basis of the imported historical snapshot.**
