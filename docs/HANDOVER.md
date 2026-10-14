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

Install the debug APK from the PR's CI run on both phones (uninstall the release build first:
different signing key). Then:

1. Baby phone: Baby → "Pair new parent". Parent phone: Parent → "Add baby device" → tap the baby.
2. Both phones show 7 emojis with names; they must be identical. Tap "They match" on both.
   Both say "Paired"; the baby now appears in the parent's list as online. Tap it: video.
3. Pair again with a third phone (or after removing), tap "Don't match" on one side: neither
   phone stores anything.
4. A third, unpaired phone on the Wi-Fi cannot watch (it does not even see the baby as pairable
   unless the window is open).
5. Settings → remove the baby on the parent (or the parent on the baby) while watching: the
   stream stops at once and does not come back.

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
