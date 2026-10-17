# Hausfunk

A baby monitor for Android that streams live video and audio from one "baby" phone to several
paired "parent" phones on the same Wi-Fi. No server, no internet, no accounts, no tracking.

Everything stays between your phones: they find each other on the Wi-Fi, pair once by comparing
four emojis, and from then on talk only to each other, end-to-end encrypted (mutual TLS 1.3 with
pinned keys, WebRTC DTLS-SRTP). See [docs/PROTOCOL.md](docs/PROTOCOL.md) for the details.

## Install

1. Download `hausfunk-X.Y.Z.apk` from the latest
   [release](https://github.com/wendevlin/hausfunk/releases/latest) on both phones and open it.
   Android asks once to allow installs from the browser or file manager. Google Play Protect may
   warn about an unknown app; "Install anyway" goes ahead.
2. Open Hausfunk on each phone, give it a name and allow what it asks for (camera and microphone
   on the baby phone, notifications and the battery exemption on both, "nearby devices" / local
   network where Android asks for it).
3. Choose **Baby** on the phone that stays with the baby and **Parent** on the other. On the
   parent phone, tap the baby phone in the list and check that both screens show the same four
   emojis. From then on the parent phone connects by itself.

Updates: install the newer APK over the old one; pairings and settings stay. The app is for
arm64 phones (every phone from the last years); Android 12 or newer.

The app follows the phone's language (English or German); Android's per-app language setting
(Settings → Apps → Hausfunk → Language) overrides it.

## Using it

- **Baby phone**: put it 1–2 m away from the baby, plugged in. "Screen off" darkens the screen
  while monitoring continues. Up to four parent phones can watch at once.
- **Parent phone**: shows the video, the sound level and when the last sound was. Leaving the app
  keeps the video in a small window (picture in picture); with the screen off you keep hearing
  the baby. Tap the sound level for the last 30 minutes; the gear on it sets the noise threshold
  and "only play sounds above the threshold".
- **Alarm**: if the connection is lost for longer than the time set in the settings (15 s by
  default), the parent phone rings at alarm volume, also in silent mode, until you tap
  "Acknowledge". The sound can be changed in the settings.
- **Galaxy Flip**: stood half open, the picture sits above the fold and everything else below it.

## Known limitations

- If the parent app itself is killed or the parent phone dies, no alarm can sound. The battery
  exemption makes this unlikely; nothing can rule it out.
- Both phones must be on the same Wi-Fi. Guest networks and some mesh or business networks block
  the devices from seeing each other ("client isolation").
- A phone call on the baby phone takes the microphone and interrupts the stream. Airplane mode
  with Wi-Fi on avoids that.
- Some manufacturers stop the camera when the screen turns off; the parent then sees "no
  picture" while sound continues. Use "Screen off" in the app instead of the power button.
- When the baby phone gets very hot, it lowers the video to 640×480 at 10 frames per second until
  it has cooled down; the parent phone shows a note.
- Not a medical device: it does not watch breathing or heart rate.

## Build

Requirements: JDK 17 and the Android SDK command-line tools (`ANDROID_HOME` set).

```sh
./gradlew assembleDebug testDebugUnitTest lint
```

The debug APK lands in `app/build/outputs/apk/debug/`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk` or by opening the file on the phone.

## Releases

Create a tag `X.Y.Z` (no `v`) that equals `versionName` in `app/build.gradle.kts` (bump `versionCode`
too), either with `git tag X.Y.Z && git push origin X.Y.Z` or on GitHub's "Draft a new release" page
(new tag on `main`, then "Publish release"). CI builds a signed release APK and attaches it to the
GitHub release. For a tag whose build did not run, start the "Build" workflow by hand (Actions →
Build → Run workflow) with that tag. The signing key lives in
these repository secrets, never in the repository:

| Secret | Content |
| --- | --- |
| `HAUSFUNK_KEYSTORE_BASE64` | the release keystore, base64-encoded |
| `HAUSFUNK_KEYSTORE_PASSWORD` | keystore password |
| `HAUSFUNK_KEY_ALIAS` | key alias |
| `HAUSFUNK_KEY_PASSWORD` | key password |

Create the keystore once and keep a backup: every release must be signed with the same key, or
it will not install as an update.

```sh
keytool -genkeypair -v -keystore hausfunk-release.jks -alias hausfunk -keyalg EC -groupname secp256r1 -validity 36500
base64 -w0 hausfunk-release.jks   # value for HAUSFUNK_KEYSTORE_BASE64 (macOS: base64 -i)
```

The keystore is PKCS12, which has a single password: use it for both password secrets.

Debug builds use the debug key and cannot update a release install (uninstall first).

## Licence

[Apache License 2.0](LICENSE).
