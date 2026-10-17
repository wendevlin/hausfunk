# Babyphone App – AI Implementation Plan (revision 2)

Oct 14, 2026 · @Wendelin · revised for implementation by an AI coding agent

## Purpose and scope

Build a native Android baby monitor that streams live video and audio from one "baby" phone to several paired "parent" phones on the same Wi-Fi, with no server and no internet. This plan is written for an AI coding agent: work through the phases in order, keep each phase buildable, and do not add features outside this document without asking.

Priorities, in this order: **secure, stable, minimal**. When two options both meet the requirements, pick the one with less code and fewer dependencies.

**Goals**

- Reliable one-way audio + video, baby to parents, sub-second latency on the LAN.
- Several parent phones at once, each paired once and then reconnecting automatically.
- Keeps running with the screen off on both sides; picture-in-picture on parent phones.
- A parent always knows when monitoring has stopped (loud alarm on connection loss).
- Distributed as a sideloaded APK; no Play Store, no accounts.

**Non-goals (v1)**

- Remote access over mobile data or the internet (see Future-proofing).
- Talk-back and two-way calls (see Future-proofing).
- Home Assistant or any other integration.
- iOS, web or desktop clients.
- Recording or cloud storage.
- Any network traffic other than the LAN protocol in this document: no analytics, crash reporting, update checks or telemetry.

## Rules for the implementing agent

1. Work the phases in order. Each phase ends with an installable APK and a short checklist the human runs on two real phones. Do not start the next phase until the human reports the gate passed.
2. Before ending any work session, `./gradlew assembleDebug testDebugUnitTest lint` must pass from a clean checkout.
3. Use only the dependencies in the tech stack table. Anything else: ask first.
4. Build nothing outside this document. If a requirement turns out to be impossible or unwise against a real Android API, write the problem and the proposed change into `docs/DECISIONS.md` and ask.
5. Keep `docs/PROTOCOL.md` in sync with the wire protocol (framing, messages, pairing steps). Its initial content is the protocol in this plan.
6. Security rules are not negotiable: no "insecure" or "debug" switch that bypasses pairing or TLS, no logging of nonces, keys, certificates or pairing state, no traffic beyond the LAN protocol.
7. No dependency-injection framework, no multi-module build, no custom Gradle plugins, no code generation beyond what Compose needs. Wire singletons manually in the `Application` class.
8. Prefer boring, documented Android APIs. Where behaviour with the screen off or in the background is uncertain, say so in the phase handover so the human tests it explicitly.
9. All threading around WebRTC goes through one dedicated single-thread dispatcher per service; UI reads state only through `StateFlow`.

## Functional requirements

One APK serves both roles; everything below is in scope for v1 unless marked.

| ID | Requirement |
| --- | --- |
| R1 | First launch asks for a device name (e.g. "Kinderzimmer", "Wendelin Pixel"), max 32 characters. The name is editable later in settings. |
| R2 | Each launch the user picks a role: Baby or Parent. The last role is remembered and offered first. |
| R3 | Devices pair once. Pairing shows the same 7 emojis on both phones; the user confirms they match on both. Paired devices reconnect without asking. |
| R4 | Only paired devices may connect. Any other device on the Wi-Fi is rejected during the TLS handshake. |
| R5 | One baby device streams to several parent devices at the same time (soft limit 4). Parents join and leave at any time without affecting others. |
| R6 | Media is one-way, baby to parent: audio always, video only to parents currently watching (app in foreground or PiP). |
| R7 | Parent app enters picture-in-picture when the user leaves it. Closing PiP keeps audio playing in the background. |
| R8 | Both roles keep running with the screen off. Swiping the app away from recents does not stop monitoring; only "Stop" does. |
| R9 | Each parent device raises a loud, persistent alarm if its connection to the baby device drops and does not recover within a short grace period. |
| R10 | Parents are warned when the baby device battery is low or it is unplugged. |
| R11 | Baby screen shows how many parents are connected and their names. |
| R12 | Devices discover each other automatically on the LAN; the user never types an IP address. |
| R13 | Each parent shows when the last sound above its threshold happened ("Last sound 4 min ago"): on the watch screen, in PiP and in the ongoing notification. |
| R14 | Each parent sets its own noise threshold with a slider beside a live level meter. An optional "Only play sound above threshold" mode mutes everything quieter. |
| R15 | Parents see the sound level live and can expand a short history of the last 30 minutes. *(Phase 7; may be deferred to v1.1 if time is short.)* |
| R16 | Every screen works in portrait and landscape, on phones and tablets. Rotating never interrupts the stream. |
| R17 | The UI is minimal: video and sound level are the content, everything else stays small and out of the way. |
| R18 | A parent may be paired with several baby devices and monitors one at a time. |

## Architecture

The baby phone talks directly to each paired parent phone over the home Wi-Fi; there is no server.

```
baby phone                                        parent phones
┌───────────────────────────┐  mDNS _babyphone._tcp  ┌──────────────┐
│ camera + microphone       │ ─────────────────────▶ │ parent A     │ audio + video
│ TLS 1.3 server (mTLS)     │ ◀── signaling (JSON) ─ │ (watching)   │
│ 1 PeerConnection / parent │ ══ WebRTC DTLS-SRTP ═▶ └──────────────┘
│ shared audio+video source │                        ┌──────────────┐
│                           │ ══ WebRTC DTLS-SRTP ═▶ │ parent B     │ audio only
└───────────────────────────┘                        │ (background) │
                                                     └──────────────┘
```

- Discovery: mDNS via `NsdManager`.
- Signaling: the baby is always the TLS server and the WebRTC offerer; the parent is always the TLS client and answerer. Both sides authenticate with pinned certificates (mutual TLS).
- Media: one WebRTC peer connection per parent, host ICE candidates only, DTLS-SRTP.
- Roles are device states inside one app, not separate apps.

## Device identity and pairing

Every device has a permanent identity key. Pairing is a one-time exchange, confirmed by matching emojis, after which every connection is authenticated against pinned certificates.

**Identity**

- On first launch, generate an EC P-256 key pair in the Android Keystore (`PURPOSE_SIGN | PURPOSE_VERIFY`, digest SHA-256, no user-authentication requirement, no StrongBox) with the self-signed X.509 certificate the Keystore creates for it (subject `CN=babyphone`, validity far in the future; validity is never checked).
- The device ID is the lowercase hex SHA-256 fingerprint of that certificate's DER encoding.
- The user-chosen device name travels alongside the ID. It is a label only and never used for trust. Cap it at 32 characters on input and on receipt.
- If the Keystore key is missing or unusable at startup (OS update, wiped credentials), generate a new identity, clear the trust store and tell the user that all devices must be paired again. Never fall back to a software key silently.

**Trust store record**: device ID, name, certificate (DER, base64), role at pairing time, paired-at timestamp. Stored in a single DataStore file (`DataStore<TrustStore>` with a kotlinx.serialization JSON serializer). No database.

**Pairing flow** (commit-reveal short authentication string over TLS)

The pairing exchange runs inside the same TLS connection type used for signaling. The only difference is that during pairing each side's trust manager accepts one unknown certificate and records it. The emojis are derived from both certificates and two committed random nonces, so a man-in-the-middle on the LAN gets exactly one blind guess in 2^42.

1. Baby user taps "Pair new parent". The baby opens a pairing window for 120 s and advertises `pair=1` in its mDNS TXT record. While the window is open, the baby's server trust manager accepts a client certificate that is not in the trust store; the resulting connection is classified `PAIRING` and handed only to the pairing handler, never to signaling. At most one `PAIRING` connection at a time; others are closed.
2. Parent user taps "Add baby device", sees the names of devices advertising `pair=1`, picks one. The parent opens a TLS connection with a pairing-only trust manager that accepts any server certificate and records it. This trust manager is used for this one connection only.
3. After the handshake, both sides hold the peer certificate from the TLS session. Let `fpP` and `fpB` be the SHA-256 fingerprints (32 bytes each) of the parent and baby certificates.
4. Parent generates `nP` (32 random bytes from `SecureRandom`) and sends `pair_commit { commitment: base64(SHA-256("babyphone-commit-v1" || nP)) }`.
5. Baby generates `nB` and sends its own `pair_commit`.
6. Parent, only after receiving the baby's commitment, sends `pair_reveal { nonce: base64(nP), deviceId, name }`.
7. Baby verifies `SHA-256("babyphone-commit-v1" || nP)` equals the received commitment and that `deviceId` equals `fpP` in hex; on any mismatch it sends `pair_abort` and closes. Then it sends `pair_reveal { nonce: base64(nB), deviceId, name }`. The parent performs the same checks on the baby's reveal.
8. Both compute `sas = SHA-256("babyphone-sas-v1" || nP || nB || fpP || fpB)` (all inputs fixed length), take the first 42 bits as a big-endian integer and split them into 7 values of 6 bits. Each value indexes a fixed list of 64 emojis. Use the 64-emoji table from the Matrix specification's SAS verification, including its English and German names, so both the list and the translations are known-good.
9. Each phone shows the 7 emojis with their names and the buttons "They match" and "Don't match". Tapping "They match" sends `pair_confirm`; "Don't match", the back button or a timeout of 120 s sends `pair_abort` and closes.
10. A side stores the peer certificate in its trust store only when it has both tapped "They match" locally and received the peer's `pair_confirm`. Anything else, including a dropped connection, stores nothing. The pairing window closes when a pairing finishes or aborts.

**After pairing**

- Signaling runs over TLS 1.3 only (`setEnabledProtocols(["TLSv1.3"])`), with mutual authentication (`needClientAuth = true` on the server). The custom `X509TrustManager` accepts a certificate if and only if its SHA-256 fingerprint is in the trust store. It ignores validity dates, chains and hostnames, and `getAcceptedIssuers()` returns an empty array. No endpoint identification algorithm is set; pinning is the check.
- The custom `X509ExtendedKeyManager` always returns the identity alias, its certificate as a one-element chain and the Keystore `PrivateKey`, regardless of the issuer list in the request (the list is empty or absent with self-signed certificates).
- WebRTC's DTLS fingerprints travel in the SDP inside that authenticated channel, so the media stream is bound to the paired devices too. No extra check is needed.
- Settings list paired devices with name and pairing date, with "Rename" and "Remove". Removing deletes the record and closes any live connection from that device at once; the other side's next attempt fails in the TLS handshake. Re-pairing a device that is still in the trust store is not supported: remove it first.

**mTLS implementation notes** (where implementations usually go wrong)

- Keystore-backed private keys work with `SSLSocket` through the custom key manager; the platform TLS provider delegates signing to the Keystore. Do not export or copy the private key.
- Build one `SSLContext` for signaling (pinning trust manager) and separate contexts for the two pairing modes. The baby's server trust manager consults the pairing window flag at `checkClientTrusted` time; the connection is classified after the handshake by looking up the peer fingerprint again, and a window that closed in between leads to closing the connection.
- Handshake timeout 10 s, read timeout 30 s, at most 8 concurrent connections on the baby, at most 1 `PAIRING` connection.
- Framing on every connection: 4-byte big-endian length prefix followed by UTF-8 JSON, maximum 64 KiB. Unknown message types are ignored; malformed frames or oversize frames close the connection.
- The unit tests in "Testing" cover the trust manager, the commitment checks and the SAS derivation with fixed vectors.

## Media pipeline

The baby device captures once and fans out over one WebRTC peer connection per parent; video is only encoded for parents who are watching.

**Baby side**

- One `PeerConnectionFactory`, one Camera2 capturer (back camera by default), one shared video source and one shared audio source.
- One `PeerConnection` per connected parent, each with the shared audio and video tracks on `sendonly` transceivers, plus one ordered, reliable data channel named `control` created by the baby.
- libwebrtc runs a separate encoder per peer connection, so every watching parent costs one encoder. Soft limit: 4 parents; the baby refuses the fifth with a `busy` message.
- Video: 1280×720 at 15 fps, max bitrate 1.5 Mbit/s via `RtpSender.parameters`, hardware H.264 where available, otherwise VP8.
- Audio: Opus, mono. Turn off echo cancellation, auto gain, noise suppression and the high-pass filter (software ones through the `googEchoCancellation`, `googAutoGainControl`, `googNoiseSuppression`, `googHighpassFilter` constraints on `createAudioSource`, hardware ones through `setUseHardwareAcousticEchoCanceler(false)` and `setUseHardwareNoiseSuppressor(false)` on `JavaAudioDeviceModule`), so quiet sounds are not swallowed. Audio source `UNPROCESSED` where supported, else `VOICE_RECOGNITION`. Never set `MODE_IN_COMMUNICATION`.

**Signaling sequence** (JSON frames over the mTLS connection, each `{ "type": ..., ... }`)

| Message | Direction | Content |
| --- | --- | --- |
| `hello` | both, parent first | protocol version (1), device ID, name, app version; parent adds `watching` |
| `offer` | baby → parent | SDP |
| `answer` | parent → baby | SDP |
| `ice` | both | candidate, sdpMid, sdpMLineIndex |
| `busy` | baby → parent | parent limit reached; parent shows it and retries later |
| `bye` | both | orderly close |

- ICE configuration: `iceServers = []` (host candidates only). STUN and TURN are configuration values, not constants, for later.
- The signaling socket stays open for the lifetime of the peer connection. Its close counts as connection loss.
- Reconnection is always a full teardown and a fresh connection (new TLS, new `PeerConnection`). No ICE restart, no renegotiation in v1.

**Video gating**

- Each parent reports `watching: true/false` over the data channel (true while the app is in foreground or PiP) and in `hello`.
- When a parent is not watching, set that video sender's encoding `active = false` through `RtpSender.parameters`. The audio track keeps flowing.
- When no parent is watching at all, stop the camera capturer entirely and restart it on the next `watching: true`.

**Sound level and noise gate**

- The baby device reads the raw capture via `JavaAudioDeviceModule.setSamplesReadyCallback`, computes the level (RMS in dBFS, smoothed over 100 ms) and sends it 5 times per second as a `level` message.
- Thresholds live on each parent, stored per paired baby device, so every parent picks its own.
- A sound event = level above the threshold for at least 300 ms. The parent stores its time (`SystemClock.elapsedRealtime()`) and shows "Last sound X min ago".
- Gate mode (per parent, off by default): playback stays muted below the threshold (remote `AudioTrack.setVolume(0)` / `setVolume(1)`). A sound event opens the gate, and it stays open for a hold time (default 10 s) after the level drops, so a cry plays in full instead of chopped.
- The first ~300 ms of a sound are lost while the gate decides; acceptable for v1.
- Gate mode never affects video or the connection-loss alarm.

**Parent side**

- Receive-only peer connection, rendering into a `SurfaceViewRenderer` (in the Compose screen via `AndroidView`, `SCALE_ASPECT_FIT`). The `EglBase` lives in the service; the activity attaches and detaches the renderer on bind and unbind.
- Audio plays as media (`AudioAttributes` with `USAGE_MEDIA` set on `JavaAudioDeviceModule.Builder.setAudioAttributes`; if the library build lacks that method, note it in `DECISIONS.md` and use the default). Default output routing; the volume keys control it and it keeps playing with the screen off.
- Request audio focus (`AUDIOFOCUS_GAIN`, `USAGE_MEDIA`) once when monitoring starts so other media pauses. Ignore focus loss: a baby monitor never pauses itself. The system mutes media during phone calls and restores it afterwards; nothing else to do.

**Data channel messages** (JSON on the `control` channel)

| Message | Direction | Content |
| --- | --- | --- |
| `watching` | parent → baby | boolean |
| `status` | baby → parent | battery %, charging, connected parent count, thermal reduced flag; every 30 s and on change |
| `level` | baby → parent | audio level in dBFS, 5 times per second |
| `ping` / `pong` | parent → baby / baby → parent | every 2 s, used for liveness on both sides |

Every received message, on either side, is validated with kotlinx.serialization into a typed class; unknown types are ignored, invalid content is logged once and dropped.

## UI

Use only stock Jetpack Compose Material 3 components with a fixed colour scheme from the brand palette (light and dark, see `docs/brand`; no dynamic color, so screenshots look the same on every phone) and the Material icons shipped with Compose; no custom components, no icon fonts.

**Design principles**

- Video and sound level are the content; status and controls are small and secondary.
- One primary action per screen; everything else lives in an overflow menu or settings.
- On the watch screen, controls and status fade out after 3 s without touch; a tap brings them back.
- The watch screen is always dark, so it does not light up a dark bedroom; other screens follow the system theme.
- No illustrations, carousels or decorative badges.

| Screen | Contents |
| --- | --- |
| Onboarding | Device name field, then one screen per permission explaining why (camera, microphone, notifications), then the battery-optimisation exemption prompt. |
| Role picker | Two large cards, "Baby" and "Parent"; last role highlighted. |
| Baby | Status headline (waiting / streaming), list of connected parents with names, "Pair new parent" button, "Stop" button, camera switch in the overflow menu. |
| Parent home | Paired baby devices with online/offline state (seen via mDNS in the last 30 s), "Add baby device" button. Tapping a baby starts monitoring it. |
| Parent watch | Full-width video, thin sound-level bar with the threshold as a tick, "Last sound 4 min ago", baby battery and charging state, connection indicator, alarm-volume warning if the alarm stream is muted. |
| Pairing | The 7 emojis in a grid, each with its name below, "They match" and "Don't match" buttons. |
| Settings | Rename device, paired devices (rename, remove), alarm sound, alarm grace period, video quality; per baby device: noise threshold, "Only play sound above threshold" switch, hold time. |

**Picture-in-picture (parent watch screen)**

- Manifest: `supportsPictureInPicture="true"` and `configChanges` for orientation, screen size, smallest screen size, screen layout and keyboard on the watch activity.
- `setPictureInPictureParams` with the current video aspect ratio and `setAutoEnterEnabled(true)`; minSdk 31 makes this always available.
- In PiP show only the video plus a small connection dot and the last-sound time. Closing PiP finishes the activity; the service and audio keep running.

**Sound level display (parent)**

- Live: a thin bar under the video (along the side in landscape) that follows the `level` messages, with the threshold as a small tick. "Last sound 4 min ago" sits next to it.
- History (phase 7): tapping the bar expands a strip with the last 30 minutes of level (the peak of every 5 s, 360 values), the threshold as a dashed line and sound events as dots. Drawn with a Compose `Canvas`; kept in memory in the parent service only, never stored.

**Layouts and orientation**

- No orientation lock anywhere. Use `WindowSizeClass` from `material3-window-size-class` to choose between a single-pane layout (compact width) and a side-by-side layout (medium and expanded width: parent home beside the watch screen). Plain `Row`/`Column` composition; do not pull in the adaptive-layout scaffold libraries.
- Phone landscape on the watch screen: video full-screen, system bars hidden, status as a small overlay in a corner.
- Video keeps the camera's aspect ratio and is letterboxed on the background, never stretched or cropped.
- The baby device may stand in either orientation; WebRTC sends the rotation with each frame, so parents always see the picture upright.
- Rotation and resizing never restart the stream, because WebRTC lives in the service and the activity only re-renders.
- Edge-to-edge with window insets handled on every screen.

UI strings live in resources, English (`values/`) and German (`values-de/`) from the start, following the system locale. No in-app language setting.

## Background operation and reliability

Silence must never be ambiguous: either a parent hears the baby, or the parent's phone is alarming.

**Services**

- Baby: foreground service of type `camera|microphone`, started from the visible activity (required since Android 14). Holds a partial wake lock and a Wi-Fi lock. `START_NOT_STICKY`: a camera service cannot be restarted from the background on Android 14+, and the parents' alarm covers the case.
- Parent: foreground service of type `mediaPlayback`. Holds a partial wake lock and a Wi-Fi lock. Owns the peer connection, so audio survives the activity and PiP being closed. `START_REDELIVER_INTENT`: if the system restarts it, try to resume monitoring; if `startForeground` is refused because the app is in the background, post a high-importance "Monitoring stopped, tap to resume" notification with alarm sound and vibration and stop. Never let that exception crash the process.
- Both services own the WebRTC objects; activities only bind to them and render state. `onTaskRemoved` does nothing; only the Stop action stops a service.
- Wi-Fi lock: `WIFI_MODE_FULL_HIGH_PERF` (still compiles on newer releases; the platform maps it to the closest behaviour). Do not rely on it; the alarm is the safety net.
- No multicast lock is needed: `NsdManager` runs mDNS in the system service.

**Liveness and reconnection**

- Parent sends `ping` every 2 s; baby answers `pong`. Either side counts the link as lost after 6 s without the expected message, or when ICE goes to `disconnected` or `failed`, or when the signaling socket closes.
- On loss, the parent tears everything down and reconnects: first to the last address and port that worked (a runtime cache only, not pairing data; the TLS pinning makes this safe), then, if that fails within 3 s, to whatever mDNS resolves. Backoff 1, 2, 4, 8, then every 10 s, with discovery kept running throughout.
- A changed IP address is handled by re-resolving the baby's service name. Register a `ConnectivityManager` network callback so a Wi-Fi change triggers an immediate retry instead of waiting for the backoff.
- The baby drops a parent after 6 s without `ping`, closes its peer connection and updates the connected list.

**Connection-loss alarm (parent)**

- If the link is not back within the grace period (default 15 s, configurable 5 to 60 s), start the alarm.
- Play a looping sound (bundled raw resource or the default alarm ringtone, user-selectable) with `USAGE_ALARM` and vibrate. Post a notification on a high-importance channel with a full-screen intent when `canUseFullScreenIntent()` allows it.
- The alarm stops when the link is restored or the user taps "Acknowledge". After an acknowledge, keep a persistent "Not monitoring" notification until reconnect; no repeated alarm until a new loss.
- When monitoring starts, check `STREAM_ALARM` volume; if it is zero or very low, show a warning on the watch screen. The app cannot raise it itself.

**Battery and thermals (baby)**

- Send battery % and charging state in `status` (from the sticky `ACTION_BATTERY_CHANGED` broadcast). Parents show a warning notification below 20 % and immediately on unplug.
- Phase 7: listen to `PowerManager` thermal status. At `SEVERE` or above, drop to 640×480 at 10 fps via `VideoSource.adaptOutputFormat` and set the `thermal reduced` flag in `status`; return to normal at `MODERATE` or below.
- Onboarding asks for the battery-optimisation exemption on both roles, since some OEMs kill foreground services without it.

**Known limitations to document in the README**

- If the parent app itself is killed or the parent phone dies, no alarm can sound. The battery exemption reduces this risk; nothing else can.
- A phone call on the baby phone takes the microphone and interrupts the stream; recommend airplane mode with Wi-Fi on for the baby phone.
- Some OEMs stop the camera when the screen turns off. The phase 4 gate tests this on the actual phones.

## Security hardening checklist

Apply from phase 1 and keep through every phase.

- Manifest: `android:allowBackup="false"`, `android:exported="false"` on both services, only the launcher activity exported, `usesCleartextTraffic="false"`. Release builds have `debuggable=false`.
- Release build: `isMinifyEnabled = false` until the app-size work in phase 7, which turns on R8 with keep rules for libwebrtc (see "App size").
- Network: TLS 1.3 only, mutual authentication, fingerprint pinning, no trust-all code path outside the pairing handler, handshake and read timeouts, connection and frame size limits as specified above.
- Pairing: nonces from `SecureRandom`, reveal only after the peer's commitment, commitment verified before deriving the SAS, store only after both confirmations.
- Input: every network message is parsed into a typed class; names capped at 32 characters and treated as plain text; unknown types ignored.
- Logging: no nonces, keys, certificates, SDP or pairing state in logs. Keep logs at `Log.i` or above in release builds.
- Storage: private app storage only; identity key non-exportable in the Keystore; the trust store and settings are never backed up or exported.
- Build: the signing keystore lives in CI secrets; every tag is signed with the same key so updates install over each other. Debug builds use the debug key and cannot update a release install.

## Tech stack and project setup

Kotlin, Jetpack Compose and libwebrtc in a single Gradle module, buildable from the command line without Android Studio.

| Area | Choice |
| --- | --- |
| Language / build | Kotlin, Gradle Kotlin DSL, version catalog (`libs.versions.toml`), Gradle wrapper committed, JDK 17 |
| SDK levels | minSdk 31 (Android 12), target and compile SDK = latest stable at implementation time |
| UI | Compose BOM, Material 3, `material3-window-size-class`, `material-icons-core` (extended only if a needed icon is missing), `activity-compose`, Navigation Compose |
| WebRTC | Prebuilt libwebrtc for Android (`io.github.webrtc-sdk:android`), one pinned version |
| Discovery | `NsdManager`, service type `_babyphone._tcp`, TXT `id=<device ID>`, `v=1`, `pair=1` while the pairing window is open |
| Crypto | Android Keystore (EC P-256 identity), `SecureRandom`, SHA-256 via JCA, `SSLSocket`/`SSLServerSocket` TLS 1.3 with custom key and trust managers. Nothing else. |
| Storage | `androidx.datastore:datastore` with kotlinx.serialization JSON serializers: one file for settings, one for the trust store |
| Async / serialization | Kotlin coroutines and Flow, kotlinx.serialization (JSON) |
| Tests | JUnit, kotlin-test, coroutines-test. No Robolectric. |

Not used, on purpose: Room, Protobuf, Hilt or Koin, adaptive-layout scaffolds, Material Symbols, HKDF or any crypto library beyond JCA, BouncyCastle.

Pin every dependency to an exact version that has been released for at least 14 days.

**Supporting Android 12 and 13**

- Discovery: before Android 14, `NsdManager` resolves only one service at a time and has no IP-change callback. Queue resolve calls, and on Android 12–13 re-resolve the baby's service after every connection loss and every 30 s while disconnected. Use `registerServiceInfoCallback` on 14+. TXT attributes are only available after a resolve, so resolve every `_babyphone._tcp` service found (a home LAN has few) and match on the `id` attribute.
- Ask for `POST_NOTIFICATIONS` only on Android 13+; on 12 notifications are allowed by default. Warn, but do not block, if the parent denies it: the alarm sound still plays.
- Check full-screen intents via `NotificationManagerCompat.canUseFullScreenIntent()`; it returns true on 12–13 and for sideloaded apps on 14+.
- Keep these differences behind small helpers in one `compat` package, never scattered `SDK_INT` checks.

**Permissions**: `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_LOCAL_NETWORK`. Nothing else; in particular no Wi-Fi state or multicast permissions.

**Local network permission**: apps targeting Android 17 (API 37) need the runtime permission `ACCESS_LOCAL_NETWORK` (Nearby devices group) for `NsdManager` discovery and for connections to LAN addresses; without it discovery silently finds nothing. Both roles ask for it during onboarding on Android 17+, with an explanation step like the other permissions, and the baby and parent screens show a clear hint when it is denied. The system device picker (`NsdManager` with `FLAG_SHOW_PICKER`) is not used: it shows one device per request and cannot support automatic reconnection.

**App size**: the first debug APK was 64 MB and the unminified release APK 47 MB before libwebrtc was added. Reduce it in phase 7, measured on the release APK and recorded in `DECISIONS.md`:

- Replace `material-icons-extended` with the handful of icons actually used, copied as vector drawables (Apache 2.0).
- Turn on R8 (`isMinifyEnabled`, `isShrinkResources`) with keep rules for `org.webrtc` (JNI calls into Java by name) and kotlinx.serialization; run the full manual checklist on the minified build.
- Ship only the ABIs real phones need: `arm64-v8a`, plus `armeabi-v7a` only if a household device needs it; build one APK per ABI if both are kept.
- Target: a release APK well under 20 MB.

**Package layout** (`app/src/main/java/<applicationId>/`): `identity`, `pairing`, `discovery`, `signaling`, `media`, `service`, `alarm`, `data`, `compat`, `ui`. Pure-Kotlin logic (SAS derivation, commitment checks, trust decisions, backoff, gate timing, message models) must not import Android classes so it runs in JVM unit tests.

**Build and distribution**

- `./gradlew assembleDebug` and `assembleRelease` must work on a clean machine with only the JDK and Android SDK command-line tools.
- A GitHub Actions workflow runs `testDebugUnitTest` and `lint` on every push, and builds a signed release APK on every `v*` tag and attaches it to the release. The signing keystore and passwords live in repository secrets, never in the repo. Pin actions by commit SHA.
- Install by downloading the APK on the phone, or via `adb install -r`.
- The `applicationId` is permanent (changing it breaks updates). Decide it before phase 1 (see Decisions).

## Implementation phases

Eight phases, each ending in an installable APK that the user tests on two real phones before the next phase starts. Security comes in phase 3, before background operation, so that no later phase is built on an unauthenticated transport.

1. **Skeleton and CI**: Compose app with onboarding (device name, permissions, battery exemption), role picker, empty Baby and Parent screens built on window size classes from the start; settings and trust-store DataStore files; Keystore identity created on first launch; Gradle CLI build; GitHub Actions workflow producing a signed APK on tags.
   - Done when: the APK from CI installs on a phone, remembers name and last role, and a second tagged build installs as an update over it.
2. **First stream**: NSD advertise and discover; a `SignalingTransport` interface with a temporary plain-TCP implementation; one baby to one parent, audio and video, inside the activities; the framing and message classes from this document.
   - Done when: the parent phone shows live video with sound from the baby phone on the same Wi-Fi. This build must not leave the household.
3. **Identity, pairing and mutual TLS**: pairing flow with commit-reveal emojis, trust store, TLS transport replacing the plain-TCP one (delete it, keep no flag), settings for paired devices (rename, remove), unit tests for trust manager, commitment and SAS.
   - Done when: two phones pair via matching emojis, an unpaired third phone is rejected in the handshake, "Don't match" on either side stores nothing, and "Remove" cuts a live connection.
4. **Background and PiP**: move WebRTC into the two foreground services; parent PiP with auto-enter; audio continues when PiP is closed; wake and Wi-Fi locks; swipe-away keeps running.
   - Done when: both screens can be off for 30 minutes and audio still arrives; closing PiP keeps audio; rotating either phone does not interrupt the stream.
5. **Reliability**: data channel, ping/pong, reconnection with last-address fast path and backoff, connection-loss alarm with acknowledge, alarm-volume warning, battery and charging status, sound level messages, last-sound indicator and noise gate.
   - Done when: turning off the baby phone's Wi-Fi makes the parent alarm within the grace period, turning it back on reconnects without user action, force-stopping the baby app alarms, and with the gate on quiet noises stay silent while a loud sound plays in full.
6. **Multiple parents and video gating**: `watching` messages, per-sender `active` flag, camera stops when nobody watches, parent limit with `busy`, connected-parents list on the baby screen.
   - Done when: three parents connect at once, only those in foreground or PiP receive video, and the baby phone stays below the thermal `SEVERE` state for one hour.
7. **Polish**: app size (see "App size"), thermal fallback, sound history (R15), alarm settings, camera switch, German strings reviewed, README with install and limitations, edge cases from testing.
   - Galaxy Flip (foldables, owner's request): Flex mode when half folded (`androidx.window` `FoldingFeature`): parent shows the video on the upper half and level, last sound and controls on the lower half; baby stands half folded with the camera on the upright half and its screen off or a small status below the fold. Check the layouts on the Flip's cover screen (small, nearly square window) for both roles. Test on the Flip 7.

8. **Watching from outside, over a VPN (owner's request)**: no Hausfunk server. Whoever wants to watch from away sets up a VPN to the home network (WireGuard on the router, Tailscale with a subnet router, …); the phones then reach each other as on the LAN, and the end-to-end security stays exactly as it is.
   - Remember each paired baby's last working address (and port) persistently, in the settings file next to the trust store, never in the pairing data. Discovery does not cross a VPN.
   - Paired babies that are not found are still tappable ("Not found · tap to try"); the parent then tries the remembered address, and the usual reconnection and alarm rules apply.
   - The baby's port stays the same across restarts (a fixed default, chosen again only if taken), so a remembered address keeps working.
   - The "this phone is not on Wi-Fi" alarm text is not used while a VPN carries the connection.
   - README: a short guide for WireGuard and Tailscale; the baby phone needs no VPN when the router (or a home device) is the VPN endpoint.
   - Done when: a parent on mobile data with the VPN on watches the baby at home with sound and video, and the alarm rings when the baby goes offline.

Do not hand the APK to anyone outside the household before phase 3 is done: until then, anyone on the Wi-Fi can watch.

## Testing and acceptance

Unit tests cover the logic that can fail silently; everything else is accepted by hand on real phones.

**Unit tests (JVM)**

- SAS: both sides derive the same 7 emojis from the same nonces and fingerprints (fixed test vectors committed in the test); swapping the fingerprint order or changing one byte of any input changes the emojis.
- Commitment: a reveal whose nonce does not hash to the commitment aborts; a reveal that arrives before the peer's commitment aborts; a `pair_confirm` without a local confirmation stores nothing.
- Trust manager: accepts exactly the pinned fingerprints, rejects an unknown certificate when the pairing window is closed, classifies it as `PAIRING` when open, and never classifies an unknown certificate as `TRUSTED`. Test certificates are pre-generated DER files in test resources (generated once with OpenSSL), not generated in the test.
- Framing: oversize and truncated frames are rejected; data channel and signaling messages round-trip through serialization; unknown types are ignored.
- Reconnection backoff, liveness timeout, alarm grace period and noise-gate hold time, all with a fake clock.

**Manual acceptance checklist**

- [ ] Overnight run: 8 hours, both screens off, baby plugged in; no dropouts, no alarm.
- [ ] Latency: clap next to the baby phone; the parent hears it in under 1 second.
- [ ] Whisper-level sound 2 m from the baby phone is audible on the parent.
- [ ] Gate mode with a threshold set while the baby sleeps: turning over and breathing stay silent, crying plays in full.
- [ ] "Last sound" updates within 1 second of a sound and keeps counting correctly with the screen off.
- [ ] Sound history shows the last 30 minutes with the threshold line and sound events (if R15 was built).
- [ ] Rotating parent and baby phones during streaming: no interruption, picture stays upright.
- [ ] Every screen is usable on a tablet in both orientations and on a phone in landscape.
- [ ] Wi-Fi off on the baby phone: every parent alarms within the grace period.
- [ ] Router reboot: all devices reconnect on their own after it comes back.
- [ ] Baby app force-stopped: parents alarm.
- [ ] Parent app swiped away from recents: audio continues.
- [ ] Phone call on a parent phone: monitoring audio resumes after the call ends.
- [ ] Three parents connected; video only on those watching; baby phone does not overheat over 1 hour.
- [ ] PiP opens on Home gesture; closing PiP keeps audio.
- [ ] Unplugging the baby phone warns every parent.
- [ ] Unpaired phone on the same Wi-Fi cannot connect or see the stream; a packet capture on the LAN shows only TLS and DTLS-SRTP.
- [ ] "Don't match" during pairing on either phone leaves both trust stores unchanged.
- [ ] Removing a parent on the baby disconnects it immediately and it cannot reconnect.

Run the whole checklist on at least one Android 12 or 13 device and one Android 14+ device.

## Future-proofing

v1 stays LAN-only and one-way, but nothing in it may block internet use or two-way media later.

**Later goals (not v1)**: watching from outside over the user's own VPN (phase 8; a self-hosted relay/TURN server was considered and dropped as not worth its upkeep); two-way audio and video; possibly a browser client.

**Design rules for v1** (each costs nothing now)

- Signaling is transport-agnostic: one `SignalingTransport` interface, implemented in v1 by the local TLS socket. Messages are JSON envelopes that carry the sender's device ID, so a relay can forward them unchanged later. End-to-end authentication of envelopes across an untrusted relay is designed when the relay is, not in v1; the mTLS connection authenticates everything on the LAN.
- Media is always end-to-end encrypted (DTLS-SRTP). No server ever decodes media, including a future TURN relay.
- ICE servers are configuration, not constants: v1 uses host candidates only.
- Media direction is negotiated per transceiver. v1 sets baby `sendonly` and parent `recvonly`; adding a parent camera or microphone later changes only the transceiver directions.
- Roles are device states, not separate code paths.
- Pairing data holds device ID, name and certificate only, never IP addresses or network details. The last-known-address cache lives outside the trust store (runtime state until phase 8, then a settings entry).

## Decisions (formerly open questions)

- Maximum number of parent devices: soft limit of 4; confirm after the phase 6 heat test.
- A parent may be paired with several baby devices and monitors one at a time (R18). Watching several at once is out of scope.
- Night vision: out of scope for v1. No exposure boost; recommend a dim night light in the README.
- App name: the visible name is a single string resource and can change at any time. The `applicationId` cannot; choose it before phase 1 (suggestion: a reverse-domain ID under a domain you control, independent of the final product name). Icon: the Hausfunk mark from `docs/brand` on navy, with a single-colour variant for themed icons.

**Later, out of scope for v1**: remote access (signaling server + TURN), talk-back, recording, night vision.

## Revision notes

What changed from the first version and why:

- **Pairing is simpler and still sound.** The separate ephemeral-ECDH protocol with its own encrypted channel is gone. Pairing now runs inside the same TLS connection as signaling, with a commit-reveal of two random nonces and emojis derived from the nonces plus both certificate fingerprints. Same security (one blind guess in 2^42 for an attacker), one protocol fewer, no HKDF, no hand-rolled encryption.
- **Security moved from phase 5 to phase 3**, so background operation, reliability and multi-parent work are built on the authenticated transport instead of retrofitted.
- **Concrete mTLS requirements** (TLS 1.3 only, `needClientAuth`, how the custom key and trust managers must behave, timeouts, frame and connection limits) because those details are where implementations usually fail silently.
- **Dropped for minimalism**: Room, Protobuf, per-message signatures on top of TLS, per-app language setting, adaptive-layout scaffolds, Material Symbols, multicast lock and the Wi-Fi permissions (not needed with `NsdManager`), any DI framework. (R8 came back in phase 7 for app size.)
- **Added for stability**: last-known-address reconnect fast path, network-change callback, alarm-volume warning, audio-focus behaviour, graceful handling of a refused foreground-service restart, Keystore-loss recovery, a documented limitations list, and explicit service restart policies.
- **Added for the agent**: working rules, a hardening checklist, `docs/PROTOCOL.md` and `docs/DECISIONS.md`, tighter unit-test targets, and decisions on the former open questions so nothing blocks phase 1.
