# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phases 1 and 2 are merged and passed their gates** on two Android 16 phones. The release
  path works: `0.1.0` and `0.1.1` built and signed by CI, `0.1.1` installed as an update.
  Release tags are the bare version (no `v`).
- **Phase 3 is written and builds, but has not run on a phone yet.** 44 unit tests pass,
  including SAS test vectors, the pairing state machine and real TLS 1.3 handshakes on the JVM
  with test certificates. What only phones can show: the Keystore key signing TLS handshakes
  (see DECISIONS, `DIGEST_NONE`), and pairing over mDNS.
- Google Play Protect warns when installing the APK ("unknown app"). Long-term fix: Google's
  developer verification (registering the package name and signing key); see the chat of
  2026-10-14. Not done yet.

## Next steps (human): phase 3 gate

Install the debug APK from the PR's CI run on both phones. Debug builds are a separate app
("Hausfunk (Debug)", red-ringed icon, ID `dev.wendelin.hausfunk.debug`) next to the release app,
signed with a shared debug key, so later CI builds install as updates. Then:

1. Close the release app on both phones (swipe it away from recents): it may hold the camera.
2. Phone A: Baby. It shows its own camera picture. Phone B: Parent. A appears under "Neu in
   diesem WLAN"; tap it. Both show 7 emojis with names; tap "Passt" on both.
3. Tap A in B's list: video with sound. Then swap roles: no new pairing is needed.
4. "Passt nicht" on either phone stores nothing.
5. Settings → remove the other device while watching: the stream stops at once.
6. Baby: "Bild aus" turns the screen black; a tap brings it back. Camera switch works.

First phone test (2026-10-14, Flip 7 + Tab S9, both Android 16): pairing worked one way; the other
way found nothing (fixed: role-independent trust); sound but black video in both directions
(unclear; the release app was installed alongside, see step 1, and the baby preview now shows
whether the camera delivers); locking the baby stops sound without a warning (expected until
phases 4 and 5).

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
