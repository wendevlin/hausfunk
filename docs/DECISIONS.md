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
- **Version numbers are literals in `app/build.gradle.kts`.** CI checks that a release tag equals
  `versionName`. Literal values are what F-Droid's update checker reads.
- **`dependenciesInfo` disabled.** F-Droid rejects the encrypted dependency metadata block.
- **compileSdk and targetSdk 37.** API 37 is the latest stable platform, and Navigation 2.10,
  Compose 1.12 and core 1.19 refuse to build against 36.
- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 compiles Kotlin itself and fails the build
  if the plugin is applied. The compose and serialization compiler plugins are still applied.
- **Lint's newer-version checks disabled.** They contact Maven repositories on every run and
  flag versions that are too new to pass the 14-day rule.

## Phase 2

- **`ACCESS_LOCAL_NETWORK` added to the permission list.** Targeting API 37 makes local network
  access a runtime permission; without it `NsdManager` finds nothing. Approved by the owner on
  2026-10-14. Requested in onboarding on Android 17+ only; older versions need nothing.
- **App size goes into the plan (phase 7).** Requested by the owner after installing the first
  debug APK.

- **libwebrtc `io.github.webrtc-sdk:android:144.7559.15`.** Latest patch (2026-09-05) of the
  mature 144 line; 150.7871.01 was the first release of a new line.
- **Only `arm64-v8a` and `armeabi-v7a` are packaged.** The x86 builds of libwebrtc (~29 MB) only
  serve emulators and Chromebooks. Debug APK went from 107 MB to 80 MB; the rest is phase 7.
- **mDNS service name = device name.** Parents need a name before pairing (the pairing list in
  phase 3 shows advertising babies by name). It is a label only, capped like every name.
- **Read timeout only during setup.** A 30 s read timeout for the whole connection would cut an
  idle, healthy session. Liveness comes from ICE state now and data-channel pings in phase 5.
- **Device ID only in `hello`.** PROTOCOL.md said every message carries it; over one connection
  that adds nothing, and from phase 3 TLS identifies the peer anyway.
- **One parent at a time in phase 2.** A second parent gets `busy`. Phase 6 raises the limit to 4.
- **Parent home and watch are one navigation route.** The session must survive switching between
  list and video (compact) and drive both panes (wide). Phase 4 moves it into the service.
- **Baby screen stays on while streaming (temporary).** In phase 2 the session lives in the
  activity, so a screen timeout would stop it. Removed in phase 4 with the foreground service.
- **`SerialExecutor` drops tasks after shutdown.** Socket readers and NSD callbacks can deliver
  events after a session ended; throwing on their threads would crash the app.

- **Release tags are the bare version (`0.1.0`, no `v`).** The owner's preference. The Build
  workflow can also be started by hand for an existing tag, which builds that tag and attaches
  the APK to its release.

## Phase 3

- **Identity key also allows `DIGEST_NONE`.** The plan said SHA-256 only, but Android's TLS stack
  asks the Keystore to sign an already computed digest (`NONEwithECDSA`); without that purpose the
  handshake fails. Keys from earlier builds are replaced at startup; with nothing paired yet this
  is silent, otherwise the usual "pair again" notice appears.
- **`X509ExtendedTrustManager`, not the plain interface.** The JDK and Conscrypt wrap plain trust
  managers with extra checks (algorithm constraints, endpoint identification); the extended one is
  used as written.
- **Handshakes run on their own thread** after `accept`, so a slow or hostile client cannot block
  other parents from connecting. Limit: 8 open connections including handshakes.
- **`pair=1` is changed by registering the service again**, so every Android version sees it
  (Android 12–13 resolve a service only once).
- **The parent checks the baby's certificate against the announced `id`** before pairing. The
  emojis would catch a swap anyway; the check gives a clear failure instead of mismatching emojis.
- **Emoji table taken from the npm package `@matrix-org/spec` 1.16.0** (`sas-emoji.json`, Apache
  2.0): the spec website was not reachable from the build environment. Names shown in German when
  the system language is German, otherwise English.
- **Plain TCP transport deleted**, as the plan requires; no flag keeps it.
- **Re-pairing needs removal on both phones.** A phone still in the other's trust store is
  classified as paired, not as a pairing peer.

- **Debug builds are a separate app** (`applicationIdSuffix = ".debug"`, name "Hausfunk (Debug)",
  red ring around the icon), signed with a debug keystore committed to the repository
  (`app/debug.keystore`, standard debug passwords). Owner's request: test builds next to the
  release app, and one test build updating the previous one. That key can only sign the debug
  app ID, so it never touches releases.

## Open points for later phases

- **F-Droid and prebuilt libwebrtc.** F-Droid builds from source and is strict about prebuilt
  binaries. `io.github.webrtc-sdk:android` is a prebuilt AAR from Maven Central. This needs
  checking with F-Droid before phase 2 is final; alternatives are building libwebrtc from
  source in the F-Droid recipe (slow, but other apps do it) or publishing through an own F-Droid
  repository first.
