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
- **The parent checks the baby's certificate against the announced `id`** before pairing. The
  emojis would catch a swap anyway; the check gives a clear failure instead of mismatching emojis.
- **Emoji table taken from the npm package `@matrix-org/spec` 1.16.0** (`sas-emoji.json`, Apache
  2.0): the spec website was not reachable from the build environment. Names shown in German when
  the system language is German, otherwise English.
- **Plain TCP transport deleted**, as the plan requires; no flag keeps it.
- **Pairing without a window (owner's design after the first phone test).** Instead of "Pair new
  parent" (120 s window, `pair=1`) and "Add baby device", the parent lists all baby devices on the
  Wi-Fi, paired and new; tapping a new one asks the baby, which shows the emojis. Both phones
  still have to tap "Passt": with only the baby confirming, a man in the middle could make the
  parent trust it. Cost: anyone on the Wi-Fi can make the pairing screen appear on the baby (one
  at a time, 60 s pause after a failed attempt); storing still needs both confirmations.
- **Trust is role-independent.** The first test paired one way and then found nothing the other
  way, because lists filtered on the role stored at pairing. The role is now only a label.
- **Baby preview, camera switch and "Bild aus" pulled forward from phase 7** (owner's request):
  the baby shows its own picture for placing the phone; "Bild aus" blacks the screen at minimum
  brightness, any tap brings it back. Camera errors are shown on the baby, and the parent says so
  when no picture arrives within 5 s.

- **Debug builds are a separate app** (`applicationIdSuffix = ".debug"`, name "Hausfunk (Debug)",
  red ring around the icon), signed with a debug keystore committed to the repository
  (`app/debug.keystore`, standard debug passwords). Owner's request: test builds next to the
  release app, and one test build updating the previous one. That key can only sign the debug
  app ID, so it never touches releases.

## Phase 4

- **The services publish their session through the Application** (`babySession`,
  `parentSession`) instead of a bound service interface. Same process, so this is the plain
  version of "activities only bind and render"; nothing else in the app can reach them.
- **Single-pane parent screens on every device (owner's request).** The plan's side-by-side list
  and video on tablets is gone: one baby at a time, either the list or the video. Two babies at
  once may come later as a special case.
- **Back on the baby screen moves the app to the background** instead of stopping: only "Stop"
  (on screen or in the notification) ends monitoring, as the plan requires for swipe-away.
- **Video: capture 1080p at 15 fps, up to 4 Mbit/s, start at 2.5 Mbit/s, `BALANCED` scaling**
  (plan: 720p, 1.5 Mbit/s). The owner found the picture poor; on a home Wi-Fi WebRTC's cautious
  default start and the low cap were the limit. WebRTC still scales resolution and frame rate
  down by itself when the link or the encoder cannot keep up. The parent's view ignores aspect
  changes below 2 %, so scaling no longer makes the picture jump (phase 3 test). More heat on the
  baby phone; the thermal fallback stays in phase 7.
- **Debug builds show the received resolution, frame rate, data rate and codec** on the watch
  screen, to judge quality on real networks. Release builds do not.
- **The same parent connecting again replaces its old connection** instead of getting `busy`
  (phase 3 test: tapping the baby again showed the old picture with "no connection").
- **Parent restart refused in the background** posts a high-importance "Monitoring stopped"
  notification and stops; it never crashes.

- **Media-volume warning on the watch screen** (owner's request): "Sound is off" / "very quiet"
  (below 25 %) with a "Louder" button. Monitoring sound plays as media, so that stream is checked;
  the plan's alarm-volume warning (phase 5) is separate.

## Phase 5

- **The alarm plays the phone's alarm sound** (falling back to the ringtone, then the
  notification sound) on the alarm stream, looping, with vibration as an alarm. No bundled sound
  and no sound picker yet; both belong to the alarm settings in phase 7.
- **"Acknowledge" in the alarm notification goes to a small non-exported broadcast receiver**
  instead of the parent service, so tapping it can never start or restart the service.
- **Noise threshold, gate and hold time sit in a sheet on the watch screen** (tune icon), next to
  the live level, instead of the settings screen: the plan asks for the slider beside a live
  meter, which only the watch screen has. They are stored per baby device in the settings file;
  removing a paired device forgets them. The alarm grace period is in the settings screen.
- **Sound event**: two level messages in a row at or above the threshold (each covers 200 ms, so
  at least ~300 ms). "Last sound" is the latest moment of the latest event. Default threshold
  -40 dBFS, scale -80 to 0, slider -70 to -10.
- **The level message carries the loudest 100 ms window since the previous message** (plan:
  smoothed over 100 ms), so a short cry between two messages still shows.
- **Busy is retried every 10 s.** If the parent had been connected before, busy counts as a lost
  connection and the alarm covers it.
- **A parent service restarted by the system (redelivered intent) counts as already connected**,
  so if the baby cannot be reached the alarm rings after the grace period.
- **The ongoing notification shows the last sound as its time stamp** ("Last sound · 4 min"),
  which the system keeps current, instead of rewriting the text every minute. While the link is
  down it says "No connection to … Reconnecting".
- **Battery warnings** (below 20 % while not charging, and on unplugging) are notifications on a
  new "Warnings" channel, once per crossing; the watch screen shows the percentage all the time.
- **Android 12–13**: while reconnecting, discovery is restarted every 30 s, since those versions
  report no address changes. A new default network triggers an immediate reconnect attempt.

## Phase 4 and 5 test feedback

- **4 emojis instead of 7, in one row (owner's request).** 24 bits instead of 42. The
  commit-reveal exchange leaves an attacker in the middle one guess per attempt, so the chance is
  1 in 16.7 million per attempt, and a failed attempt blocks that device for 60 s. For
  comparison, Bluetooth numeric comparison uses 6 digits (about 20 bits) with the same kind of
  commitments.
- **Bundled emoji images.** A Samsung and a Pixel drew the same emojis so differently that they
  looked like a mismatch. The 64 SAS emojis ship as small images from
  Google's Noto emoji (Apache 2.0, rendered from the SVGs in `@iconify-json/noto` 1.2.9), so
  every phone shows identical pictures. About 350 KB.
- **One discovery for the whole app.** The parent screens and the parent service now share one
  `BabyFinder` (reference counted). With two discoveries, stopping the service's one could make
  the baby disappear from the list until the screen was reopened (test: after "X" the baby could
  not be tapped again).
- **"No picture yet" follows the session**, which watches for frames on every connection; the
  renderer's first-frame event fired only once, so the hint stayed up after a reconnect.
- **Role picker: two list entries in a fixed order** (Baby, then Parent) instead of two big cards
  with the last role first and filled with colour. The last role gets a border and a "Last used"
  label. `material3-window-size-class` is no longer used.

## Second round of test feedback

- **Pinch to zoom on the watch screen**, drag to move, double tap to reset; up to 4×. The frames
  are cropped before the renderer (a SurfaceView ignores Compose transforms), so picture-in-picture
  keeps the same zoom.
- **The screen stays on while the watch screen is open**, with light status bar icons on its dark
  background. Not in picture-in-picture: then the user has left the app on purpose.
- **Removing a pairing tells the other device** (`unpair`, see PROTOCOL.md); it forgets the pairing
  too and shows a notice once. If the other device was not connected, it learns on its next
  connection. (Parent removed offline: the baby keeps its entry, which is harmless; the parent sees
  the baby as new and can pair again.)
- **Signaling sockets are bound to the Wi-Fi or Ethernet network** that reaches the baby, instead of
  the default network, which can be mobile data right after rejoining the Wi-Fi. When such a network
  appears or goes, discovery starts over, a hanging attempt is dropped and the next attempt uses a
  fresh `PeerConnectionFactory` (test: after leaving and rejoining the Wi-Fi the parent only
  reconnected after leaving the app).
- **The noise gate mutes the audio output** (`JavaAudioDeviceModule.setSpeakerMute`) instead of the
  remote track's volume, which did not mute in the owner's test. A crossed-out speaker next to "Last
  sound" shows while it is muted.

- **Zooming in first uses the black bars**, then crops (owner's request): the view grows to the
  shown part's aspect ratio until it fills the screen. Pure region and pinch maths in
  `ui/common/ZoomRegion.kt` with unit tests; up to 6× the fitted size.

## Third round of test feedback

- **One role per device at a time.** Starting the baby stops the parent service, and watching stops
  the baby service. A parent service restarted by the system after the app died resumes only if
  the device's last role is still "parent" and it does not run as the baby; and the alarm never
  rings while the baby service runs (it ends parent monitoring instead). Test: after a crash the
  restarted parent service reconnected in the background while the user had made the phone the
  baby, and it rang the connection alarm there.
- **Zoom crash:** picture-in-picture got the zoomed part's aspect ratio, which can be as tall as
  the screen (0.43 on the Flip), and Android refused it with an exception. PiP now gets the whole
  picture's ratio, the allowed range is narrowed to 0.5–2.33, a refused setting is logged instead
  of crashing, and cropping falls back to the whole picture if a frame cannot be cropped.
- **Debug builds keep the last crash** (stack trace in private storage) and show it on the next
  start with "Copy", so test crashes can be reported. Release builds record nothing.

## Review before the phase 4–5 test

Owner's request: review everything, remove leftovers, keep it simple, split what is too big.

- **Parent: "connected" means the baby answers** (first `pong`), no longer ICE connected. A baby
  whose app hangs while the phone still answers ICE could otherwise silence the alarm again and
  again. The alarm logic is now its own pure class `LossAlarm` with unit tests; the grace period
  goes into `connect()`, so a resumed watch uses the user's setting from the first second.
- **Parent service:** every start (not only stops) bumps the generation, so of two quick starts the
  later one wins; `stopSelf(startId)` keeps a start the system queued meanwhile; a link that is no
  longer current never sends `hello` (a late one could push the new connection off the baby).
- **Pairing no longer fails after 30 s of waiting** for the other user: pairing connections have
  no read timeout; the exchange's 120 s limit ends them.
- **Baby: one dispatcher for every connection's first message** (see PROTOCOL.md, Transport), read
  within one deadline. A trusted parent that wants to pair again is no longer told `busy` while
  another parent watches, and trust is checked again when a connection is accepted. After any
  failed pairing nobody can pair for 10 s, so new keys per attempt gain nothing. A failed attempt
  that never reached the emojis no longer covers the baby screen.
- **Baby service:** the coroutine owns the session, so "Stop" while it is still starting can no
  longer leave camera and microphone running without a service; a failed start releases them at
  once.
- **The "pairing window" is gone from the code** (the baby accepts any certificate in the
  handshake and classifies afterwards), as it was already gone from the UI.
- **Split:** `ParentSession` → `LossAlarm`, `ParentAudio`, `VideoStatsMeter`; `BabySession` →
  `BabyMedia`; `ParentHomeScreen.kt` → `ParentRoute.kt` and the list. Picture-in-picture and the
  watch screen share one video view, so entering PiP no longer restarts the renderer.
- **Smaller fixes:** quick sound-setting changes no longer undo each other (stored as a change, not
  a copy); the parent's permission hint no longer mentions the camera; unreadable settings or trust
  files start over instead of stopping the app; discovery retries a failed start and ignores late
  results for lost services; the mDNS name fits 63 bytes; a new network counts once its routes are
  known.
- Removed: `kotlinx-coroutines-test` (unused), the renderer's first-frame callback, `disconnect()`,
  `Reconnecting.since`.

- **No "OK" after pairing (owner's request):** the baby goes straight back to its screen and the
  parent straight to the video. A new pairing is trusted from the moment both confirmed, before
  the trust store write finishes, so that first connection cannot be refused (or answered with
  `unpair`) by a slower write on either phone.

## Phase 6

- **Up to 4 parents** as planned (the owner would be fine with 2); the limit is one constant.
- **"Watching" comes from the parent activity's lifecycle**: started (open or picture-in-picture)
  means watching; stopped (closed PiP, screen off, another app) means sound only. The parent
  starts not watching and tells the baby as soon as the video is visible.
- **The baby's own preview keeps the camera on**, but only while its screen is really visible
  (started), not while it runs in the background or with "Screen off".
- **The camera is switched on and off on its own thread**: stopping waits for a camera that is still
  opening, and the session thread must never wait (its pongs keep the parents' alarm quiet). A
  camera that stopped by itself (error, taken away) is opened again after 3 s if still wanted.
- **Paired babies that are online come first in the parent's list**, with a green dot and text;
  offline ones are muted (owner's request).

- **Before the alarm rings, the parent tries hard (owner's request):** every 2 s during the grace
  period instead of backing off, each network change at once, and an attempt still running when
  the grace period ends gets up to 5 s more. If this phone has no Wi-Fi at all, the alarm says
  "This phone is not on Wi-Fi" instead of blaming the baby.

## Phase 7

- **App size: R8 instead of hand-copied icons.** R8 with resource shrinking drops every unused
  class of `material-icons-extended` by itself, so copying the icons as vector drawables would
  only add files. `arm64-v8a` only (both household phones and every phone of the last years are
  arm64). Release APK 47 MB → 16 MB, debug 23 MB.
- **R8 also on debug builds**, with `-dontobfuscate`: the debug APK is what gets tested, so it must
  be the same code as the release, and stack traces stay readable. libwebrtc is kept whole (JNI
  calls into Java by name).
- **Thermal fallback**: at `THERMAL_STATUS_SEVERE` and above the baby drops to 640×480 at 10 fps
  and tells the parents (note on the parent screen); at `MODERATE` or below it returns to normal.
  The gap between the two avoids flapping. The camera itself captures less too (most of the heat).
- **No sound history**: built, then removed after testing (owner: not understandable, not
  needed). The live level and "last sound" are enough. R15 is dropped.
- **Alarm sound**: the system sound picker, without "Silent"; "Default" follows the phone's alarm
  sound. A chosen sound that can no longer be read falls back to the phone's alarm sound, checked
  when the alarm starts.
- **Language**: the app declares English and German (`generateLocaleConfig`), so Android offers a
  per-app language; English is the fallback for every other language.
- **Galaxy Flip Flex mode** (`androidx.window`): only a half-open, horizontal fold counts. The
  parent shows the video above the fold, the baby its preview, everything else goes below.

## Open points for later phases

- **F-Droid and prebuilt libwebrtc.** F-Droid builds from source and is strict about prebuilt
  binaries. `io.github.webrtc-sdk:android` is a prebuilt AAR from Maven Central. This needs
  checking with F-Droid before phase 2 is final; alternatives are building libwebrtc from
  source in the F-Droid recipe (slow, but other apps do it) or publishing through an own F-Droid
  repository first.
