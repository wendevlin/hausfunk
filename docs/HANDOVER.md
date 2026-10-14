# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phase 1 is merged** (`main`): skeleton, onboarding, role picker, settings, identity, CI, logo.
  The owner checked it on two phones. The signed-release test (`v0.1.0` → `v0.1.1`) was
  deferred by the owner to the end of phase 2.
- **Phase 2 is written and builds, but has not run on a phone yet.** Nothing could be tested
  on a device here (no emulator). `assembleDebug testDebugUnitTest lint` passes; 21 unit tests
  cover framing, message codec, the plain TCP transport (loopback) and `SerialExecutor`.
  - Discovery: `discovery/` (NsdManager), Android 12–13 resolve queue in `compat/NsdCompat.kt`.
  - Signaling: `signaling/` messages, codec, framing, `SignalingTransport`, and the temporary
    `PlainTcpTransport` (unauthenticated, phase 2 only; delete in phase 3).
  - Media: `media/BabySession.kt` (camera + mic, one parent), `media/ParentSession.kt`
    (receive, render, play as media). Both live in the activity until phase 4.
  - UI: baby screen shows waiting/streaming and a test-build warning; parent home lists
    unpaired "nearby" babies (temporary) and opens the watch screen/pane.
  - New onboarding step for `ACCESS_LOCAL_NETWORK` on Android 17+ (approved, see DECISIONS).
- Plan updated: local network permission, and app size work in phase 7.

## Next steps (human): phase 2 gate

Install the debug APK from the CI run of this branch on both phones (same Wi-Fi).

1. Phone A: role Baby. It should say "Waiting for parents" and show the red test-build warning.
2. Phone B: role Parent. Under "Nearby" phone A's name should appear within a few seconds.
3. Tap it: within a few seconds phone B shows phone A's camera with sound. Check volume keys,
   rotating either phone, and that the picture is upright.
4. Tap the X on phone B: phone A goes back to "Waiting for parents".
5. Report: Android versions of both phones, and anything that did not work.

Uncertain, please check explicitly:
- On Android 17 phones: does discovery work after allowing "local network" in onboarding?
- Rotating the baby phone during streaming (the activity is not recreated, but the camera
  orientation handling is untested).
- Sound quality: echo cancellation and noise suppression are off on purpose (quiet sounds).
- The baby phone keeps its screen on while streaming (temporary until phase 4).

After the gate: tag `v0.1.0`/`v0.1.1` for the deferred release test (signing secrets first), then
phase 3.

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
