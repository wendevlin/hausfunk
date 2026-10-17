# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phases 1 to 3 are merged and passed their gates** on a Galaxy Z Flip 7 and a Galaxy Tab S9
  (Android 16): pairing with emojis both ways, mutual TLS, video and sound. Release path works
  (`0.1.0` → `0.1.1`, bare version tags). Debug builds are a separate app ("Hausfunk (Debug)").
- **Phases 4 and 5 are merged** (tested by the owner on a Samsung and a Pixel). **Phase 6 is
  written, not yet tested.** Feedback
  fixed in the same PR (see DECISIONS, "Phase 4 and 5 test feedback"): 4 bundled emoji images in
  one row, one shared discovery, the "no picture yet" hint after reconnects, a calmer role picker. Phase 4: `BabyService` and `ParentService` own the sessions, wake and
  Wi-Fi locks, "Stop" notification, picture-in-picture, single-pane parent screens, better video,
  media-volume warning. Phase 5: data channel with ping/pong, automatic reconnection with
  backoff, connection-loss alarm with "Acknowledge", alarm-volume warning, baby battery, live
  sound level, last sound, noise threshold and gate.
- Google Play Protect warns when installing ("unknown app"); developer verification with Google
  is the long-term fix, not done yet.

## Next steps (human): phase 6 gate

Install the debug APK from the PR's CI run on all phones.

1. Two (or more) parents watch the same baby at once; each sees video and hears sound. The baby
   screen lists their names.
2. One parent goes to the home screen with the PiP window open: still video. Close the PiP window
   or turn its screen off: it keeps hearing sound, and the baby only sends video to the others.
3. No parent watching (all screens off) and the baby screen off or in the background: the baby's
   camera is off (the camera indicator dot of Android disappears). A parent opens the app: video
   comes back within a second or two.
4. A fifth parent gets "already streaming to four parent devices".
5. The parent list: online babies first with a green dot.
6. One hour with parents connected: the baby phone does not get hot (thermal `SEVERE`).

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
