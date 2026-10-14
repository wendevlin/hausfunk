# Decisions

Deviations from, or clarifications of, the implementation plan. Newest first.

## Phase 1

- **Fixed brand colours instead of dynamic color.** The owner wants the app, and its
  screenshots, to look the same on every phone. `HausfunkTheme` uses a light and a dark Material 3
  scheme built from the four brand colours in `docs/brand`; it still follows the system
  light/dark setting. The watch screen stays black.
- **Logo only in colour on dark backgrounds.** On light backgrounds the mark is drawn in a
  single colour (`LogoMark` tints it with `onSurface`). The launcher icon is the mark on navy,
  with a white monochrome layer for themed icons; the splash screen is navy in both themes.
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
- **compileSdk and targetSdk 37.** API 37 is the latest stable platform, and Navigation 2.10,
  Compose 1.12 and core 1.19 refuse to build against 36.
- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 compiles Kotlin itself and fails the build
  if the plugin is applied. The compose and serialization compiler plugins are still applied.
- **Lint's newer-version checks disabled.** They contact Maven repositories on every run and
  flag versions that are too new to pass the 14-day rule.

## Open points for later phases

- **F-Droid and prebuilt libwebrtc.** F-Droid builds from source and is strict about prebuilt
  binaries. `io.github.webrtc-sdk:android` is a prebuilt AAR from Maven Central. This needs
  checking with F-Droid before phase 2 is final; alternatives are building libwebrtc from
  source in the F-Droid recipe (slow, but other apps do it) or publishing through an own F-Droid
  repository first.
