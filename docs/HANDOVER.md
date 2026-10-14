# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phases 1 to 3 are merged and passed their gates** on a Galaxy Z Flip 7 and a Galaxy Tab S9
  (Android 16): pairing with emojis both ways, mutual TLS, video and sound. Release path works
  (`0.1.0` → `0.1.1`, bare version tags). Debug builds are a separate app ("Hausfunk (Debug)").
- **Phase 4 is written and builds, not yet run on a phone.** `BabyService` (camera|microphone)
  and `ParentService` (mediaPlayback) own the sessions, with wake and Wi-Fi locks and a "Stop"
  notification. Picture-in-picture while watching. Single-pane parent screens.
- Google Play Protect warns when installing ("unknown app"); developer verification with Google
  is the long-term fix, not done yet.

## Next steps (human): phase 4 gate

Install the debug APK from the PR's CI run (installs as an update over the previous debug build).

1. Baby phone: Baby (a notification "sending picture and sound" appears). Parent: tap the baby.
2. Turn both screens off for 30 minutes: sound still arrives.
3. Parent: Home gesture while watching → picture-in-picture. Close the PiP window → sound
   continues (notification "Listening to …").
4. Swipe the app away from recents on both phones: monitoring continues; "Stop" in the
   notification ends it.
5. Rotate both phones while streaming: no interruption. Parent in landscape: full-screen video.

Uncertain, please check explicitly: whether the Flip 7 keeps the camera running with the screen
off (some phones stop it), and whether the video still jumps now and then.

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
