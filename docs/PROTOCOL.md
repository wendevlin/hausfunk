# Hausfunk wire protocol (version 1)

Kept in sync with the code. Phase 1 implements none of this on the wire yet; it is the
contract the later phases build to.

## Discovery

- mDNS via `NsdManager`, service type `_babyphone._tcp`.
- TXT attributes: `id=<device ID>`, `v=1`, and `pair=1` while the baby's pairing window is open.
- TXT attributes are read only after a resolve, so every `_babyphone._tcp` service found is
  resolved and matched on `id`.

## Identity

- EC P-256 key in the Android Keystore with its self-signed certificate (`CN=babyphone`).
- Device ID: lowercase hex SHA-256 of the certificate's DER encoding.
- Device names are labels only (max 32 characters, capped on input and on receipt).

## Transport

- The baby is always the TLS server and WebRTC offerer; the parent is the TLS client and answerer.
- TLS 1.3 only, mutual authentication (`needClientAuth = true`).
- Trust: a certificate is accepted if and only if its SHA-256 fingerprint is in the trust store.
  Validity dates, chains and hostnames are ignored.
- Limits: handshake timeout 10 s, read timeout 30 s, at most 8 concurrent connections on the baby,
  at most 1 `PAIRING` connection.
- Framing: 4-byte big-endian length prefix, then UTF-8 JSON, maximum 64 KiB per frame.
  Unknown message types are ignored; malformed or oversize frames close the connection.
- Every message is `{ "type": ..., ... }` and carries the sender's device ID.

## Pairing (commit-reveal SAS inside TLS)

1. Baby opens a 120 s pairing window and advertises `pair=1`. While open, its server trust
   manager accepts one unknown client certificate; the connection is classified `PAIRING` and
   handled only by the pairing handler.
2. Parent picks the advertised baby and connects with a one-connection trust manager that accepts
   and records any server certificate.
3. `fpP`, `fpB`: SHA-256 fingerprints (32 bytes) of parent and baby certificates from the TLS session.
4. Parent → `pair_commit { commitment: base64(SHA-256("babyphone-commit-v1" || nP)) }`, `nP` = 32 random bytes.
5. Baby → `pair_commit` with its own `nB`.
6. Parent, only after receiving the baby's commitment → `pair_reveal { nonce: base64(nP), deviceId, name }`.
7. Baby verifies the commitment and `deviceId == hex(fpP)`, else `pair_abort` and close; then
   → `pair_reveal { nonce: base64(nB), deviceId, name }`. Parent verifies the same way.
8. `sas = SHA-256("babyphone-sas-v1" || nP || nB || fpP || fpB)`; the first 42 bits, big-endian,
   split into 7 × 6 bits, index the 64-emoji table of the Matrix SAS verification.
9. Both users compare the emojis. "They match" → `pair_confirm`; "Don't match", back or 120 s
   timeout → `pair_abort` and close.
10. A side stores the peer only after its own "They match" **and** the peer's `pair_confirm`.

## Signaling

| Message | Direction | Content |
| --- | --- | --- |
| `hello` | both, parent first | protocol version (1), device ID, name, app version; parent adds `watching` |
| `offer` | baby → parent | SDP |
| `answer` | parent → baby | SDP |
| `ice` | both | candidate, sdpMid, sdpMLineIndex |
| `busy` | baby → parent | parent limit (4) reached |
| `bye` | both | orderly close |

- `iceServers = []`: host candidates only.
- The signaling socket lives as long as the peer connection; its close counts as connection loss.
- Reconnect is always a full teardown and a fresh TLS connection and `PeerConnection`.

## Data channel `control` (ordered, reliable, created by the baby)

| Message | Direction | Content |
| --- | --- | --- |
| `watching` | parent → baby | boolean |
| `status` | baby → parent | battery %, charging, connected parent count, thermal reduced flag; every 30 s and on change |
| `level` | baby → parent | audio level in dBFS, 5 times per second |
| `ping` / `pong` | parent → baby / baby → parent | every 2 s; 6 s without one means the link is lost |
