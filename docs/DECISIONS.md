# Decisions

Deviations from, or clarifications of, the implementation plan. Newest first.

## Phase 1

- **applicationId `dev.wendelin.hausfunk`.** Reverse-domain ID chosen by the owner, independent
  of the product name. Permanent once the first release is tagged.
- **`dataExtractionRules` in addition to `allowBackup="false"`.** From Android 12,
  `allowBackup="false"` does not stop device-to-device transfer. The rules exclude every domain
  from both cloud backup and device transfer, so identity references, trust store and settings
  never leave the device.
- **Camera and microphone declared as optional hardware features.** Lets parent-only devices
  (e.g. tablets without a rear camera) install the app. The baby role still needs both.
- **`material-icons-extended`.** The core set has no camera, microphone, child or hearing icons;
  the plan allows the extended set for this case.
- **Settings and trust store writes use the application scope**, not the UI scope, so a write is
  never cancelled by navigation or the activity going away.
- **Version numbers are literals in `app/build.gradle.kts`.** CI checks that a `v*` tag equals
  `v$versionName`. Literal values are what F-Droid's update checker reads.
- **`dependenciesInfo` disabled.** F-Droid rejects the encrypted dependency metadata block.

## Open points for later phases

- **F-Droid and prebuilt libwebrtc.** F-Droid builds from source and is strict about prebuilt
  binaries. `io.github.webrtc-sdk:android` is a prebuilt AAR from Maven Central. This needs
  checking with F-Droid before phase 2 is final; alternatives are building libwebrtc from
  source in the F-Droid recipe (slow, but other apps do it) or publishing through an own F-Droid
  repository first.
