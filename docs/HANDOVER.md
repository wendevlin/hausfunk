# Handover notes

State of the work for whoever continues it (human or agent). Update at the end of every session.

**Temporary file.** Delete `docs/HANDOVER.md`, `docs/PLAN.md` and `docs/DECISIONS.md` (and
any reference to them in `README.md`) once the project is done, i.e. after phase 7. Keep
`docs/PROTOCOL.md`; it documents the wire protocol.

## Where we are

- **Phase 1 is merged** (`main`): skeleton, onboarding, role picker, settings, identity, CI, logo.
  The owner checked it on two phones. The signed-release test (`0.1.0` → `0.1.1`) was
  deferred by the owner to the end of phase 2.
- **Phase 2 is merged and passed its gate** on two Android 16 phones (2026-10-14): discovery,
  video with sound, rotation, stopping. Found there: portrait video was cropped, because
  `SurfaceViewRenderer` crops frames to its own aspect ratio; the view is now sized to the
  video's aspect ratio (`ui/parent/VideoView.kt`).
  - Not yet checked: Android 17 local network permission (no Android 17 phone in the household).
- Plan updated: local network permission, and app size work in phase 7.

## Next steps

1. Owner adds the four `HAUSFUNK_*` signing secrets; then tag `0.1.0` and `0.1.1` for the
   deferred release/update test (uninstall the debug build first).
2. Phase 3: pairing, trust store, mutual TLS; delete `PlainTcpTransport`.

## Notes for the next agent session

- Maven Central sometimes answers HTTP 429 to the sandbox's shared IP. Retrying after a short
  wait works; Gradle caches what it already fetched.
- Kotlin 2.4.21 is out but was too new (14-day rule) on 2026-10-14.
