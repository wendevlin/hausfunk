# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

## Where we are

- **Phase 1 (skeleton and CI) is written but has never been compiled.** The first build
  environment could not reach `dl.google.com` (Android SDK and Google Maven) or
  `downloads.gradle.org` (Gradle distribution).
- No APK has been produced yet, and there are no screenshots yet.
- Phase 2 must not start until the human has run the phase 1 gate on real phones (see `PLAN.md`).

## Next steps

1. Build with `./gradlew assembleDebug testDebugUnitTest lint` and fix compile errors.
2. Pin the Google-hosted versions in `gradle/libs.versions.toml` (AGP, Compose BOM, AndroidX).
   They were written from memory and are unchecked. Every version must be at least 14 days old.
   AGP and the Gradle wrapper version (`gradle/wrapper/gradle-wrapper.properties`, currently
   9.8.0) must be compatible with each other. AGP 9 has built-in Kotlin, so the
   `kotlin-android` plugin may have to go.
3. Add `distributionSha256Sum` to `gradle-wrapper.properties`.
4. Send the human the debug APK and screenshots of every screen (onboarding, role picker, baby,
   parent home in compact and wide layouts, settings). With no emulator available, render them
   with Compose's layoutlib (e.g. Paparazzi) in a scratch project outside this repository; the
   plan does not allow extra dependencies in the app.
5. Set up the release signing secrets (see `README.md`), then tag `v0.1.0` to test the release
   workflow. The phase 1 gate also needs a second tag (`v0.1.1`) installing as an update.

## Decisions already made

See `DECISIONS.md`. Key ones: applicationId `dev.wendelin.hausfunk`, Apache 2.0 licence,
F-Droid as the eventual distribution channel (open question about prebuilt libwebrtc).
