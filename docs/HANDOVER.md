# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phases 1 to 3 are merged and passed their gates** on a Galaxy Z Flip 7 and a Galaxy Tab S9
  (Android 16): pairing with emojis both ways, mutual TLS, video and sound. Release path works
  (`0.1.0` → `0.1.1`, bare version tags). Debug builds are a separate app ("Hausfunk (Debug)").
- **Phases 4 and 5 are written and build, not yet run on a phone** (both in one PR; the owner
  tests them together). Phase 4: `BabyService` and `ParentService` own the sessions, wake and
  Wi-Fi locks, "Stop" notification, picture-in-picture, single-pane parent screens, better video,
  media-volume warning. Phase 5: data channel with ping/pong, automatic reconnection with
  backoff, connection-loss alarm with "Acknowledge", alarm-volume warning, baby battery, live
  sound level, last sound, noise threshold and gate.
- Google Play Protect warns when installing ("unknown app"); developer verification with Google
  is the long-term fix, not done yet.

## Next steps (human): phase 4 and 5 gates

Install the debug APK from the PR's CI run on both phones (installs as an update over the
previous debug build).

Phase 4:

1. Baby phone: Baby (a notification "sending picture and sound" appears). Parent: tap the baby.
2. Turn both screens off for 30 minutes: sound still arrives.
3. Parent: Home gesture while watching → picture-in-picture. Close the PiP window → sound
   continues (notification "Listening to …").
4. Swipe the app away from recents on both phones: monitoring continues; "Stop" in the
   notification ends it.
5. Rotate both phones while streaming: no interruption. Parent in landscape: full-screen video.

Phase 5:

1. Turn off the baby phone's Wi-Fi: within about 15 s the parent alarms (sound, vibration,
   full-screen). "Acknowledge" silences it; the notification keeps saying "No connection".
2. Turn the Wi-Fi back on: the parent reconnects by itself and the alarm stops.
3. Force-stop the baby app (Settings → Apps → Hausfunk (Debug) → Force stop): the parent alarms.
4. Make a noise at the baby: the level bar moves, "Last sound just now" appears (watch screen,
   PiP, notification time).
5. Tune icon on the watch screen: set the threshold mark above the room noise, turn on "Only play
   sound above the threshold": quiet noises stay silent, a loud sound plays in full and keeps
   playing for the hold time.
6. Battery on the watch screen; unplug the charging baby phone → the parent gets a notification.
7. Turn the parent's alarm volume down: the watch screen warns "Alarm is very quiet".

Uncertain, please check explicitly: whether the Flip 7 keeps the camera running with the screen
off, whether the video still jumps now and then, and whether the alarm shows over the lock
screen (Android 14+ may need "full-screen notifications" allowed for the app).

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
