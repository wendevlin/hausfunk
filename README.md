# Hausfunk

A baby monitor for Android that streams live video and audio from one "baby" phone to several
paired "parent" phones on the same Wi-Fi. No server, no internet, no accounts, no tracking.

> **Status: phase 1 of 7 (skeleton).** Onboarding, role picker, settings and the device identity
> exist; there is no streaming yet. See [docs/PLAN.md](docs/PLAN.md).

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

## Known limitations

Filled in as the phases land; see [docs/PLAN.md](docs/PLAN.md).

## Licence

[Apache License 2.0](LICENSE).
