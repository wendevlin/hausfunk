# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phase 1 builds.** `./gradlew assembleDebug testDebugUnitTest lint` passes locally and in
  GitHub Actions (branch `claude/peaceful-johnson-yioskf`, not yet merged into `main`; `main`
  is still red from the never-compiled skeleton).
- Versions are pinned to real releases at least 14 days old: Gradle 9.8.0 (with checksum),
  AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00. Compile and target SDK 37.
- A signed `assembleRelease` was verified locally with a throwaway keystore.
- Screenshots of every screen (phone, phone landscape, tablet, dark German) were rendered with
  Paparazzi in a scratch copy outside the repository and sent to the human. Layoutlib cannot
  render `OnboardingScreen` itself (no `PowerManager`), so the name step was rendered directly.
- Phase 2 must not start until the human has run the phase 1 gate on real phones (see `PLAN.md`).

## Next steps (human)

1. Merge the branch into `main`.
2. Install the debug APK from the CI run's `hausfunk-debug` artifact on a phone and check the
   screens.
3. Add the four `HAUSFUNK_*` signing secrets (see `README.md`), tag `v0.1.0`, install the
   release APK; then bump `versionCode`/`versionName`, tag `v0.1.1` and check it installs as an
   update. Report the gate result.

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
