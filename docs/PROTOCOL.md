# Hausfunk wire protocol (version 1)

Kept in sync with the code. Implemented: discovery, mutual TLS, pairing and signaling (phase 3).
The data channel exists but carries no messages yet (phase 5).

## Discovery

- mDNS via `NsdManager`, service type `_babyphone._tcp`.
- Service name: the baby's device name (NSD may append " (2)" on a clash); shown to parents.
- Port: the baby's signaling port, chosen by the system.
- TXT attributes: `id=<device ID>`, `v=1`.
- TXT attributes are read only after a resolve, so every `_babyphone._tcp` service found is
  resolved and matched on `id`.

## Identity

- EC P-256 key in the Android Keystore with its self-signed certificate (`CN=babyphone`).
- Device ID: lowercase hex SHA-256 of the certificate's DER encoding.
- Device names are labels only (max 32 characters, capped on input and on receipt).

## Transport

Implemented in `signaling/TlsTransport.kt`; trust decisions in `identity/TlsKeys.kt`.

- The baby is always the TLS server and WebRTC offerer; the parent is the TLS client and answerer.
- TLS 1.3 only, mutual authentication (`needClientAuth = true`).
- Trust: a certificate is accepted if and only if its SHA-256 fingerprint is in the trust store.
  Validity dates, chains and hostnames are ignored. The parent additionally requires the
  fingerprint to equal the device ID of the baby it meant to connect to.
- The baby classifies each client after the handshake: `TRUSTED` if pinned, else `PAIRING`.
  A `PAIRING` connection only ever reaches the pairing exchange, never signaling. One at a time;
  a device whose attempt failed is refused for 60 s.
- The identity key signs TLS handshakes inside the Keystore (purposes SIGN/VERIFY, digests
  SHA-256 and NONE; the platform hands TLS signatures to the Keystore as raw digests).
- Limits: connect/handshake timeout 10 s, at most 8 concurrent connections on the baby, at most 1
  `PAIRING` connection.
- Read timeout 30 s while the session is set up (until the baby has the `answer`, the parent
  the `offer`). After that the socket may stay idle; liveness comes from the data channel
  `ping`/`pong` (phase 5) and the ICE state.
- Framing: 4-byte big-endian length prefix, then UTF-8 JSON, maximum 64 KiB per frame.
  Unknown message types are ignored; malformed or oversize frames close the connection.
- Every message is `{ "type": ..., ... }`. The sender's device ID travels in `hello` and must equal
  the fingerprint of the certificate the peer presented, otherwise the connection is closed.

## Pairing (commit-reveal SAS inside TLS)

Messages: `pair_commit { commitment }`, `pair_reveal { nonce, deviceId, name }`, `pair_confirm`,
`pair_abort`; commitment and nonce are base64. Implemented in `pairing/` (pure logic in
`PairingProtocol.kt`, emoji table in `SasEmoji.kt` from `@matrix-org/spec` 1.16.0).

1. The parent lists every `_babyphone._tcp` service; the user taps one that is not paired.
   (Changed from the plan's 120 s pairing window, see DECISIONS.) While open, its server trust
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
    Any message out of order, a second commitment or reveal, a timeout (120 s) or a dropped
    connection aborts and stores nothing.
11. The parent refuses a baby whose certificate does not match the `id` it announced, or that is
    already in its trust store.
12. Re-pairing after the parent removed the baby: the baby still trusts the parent, so the
    connection is `TRUSTED`; a `pair_commit` as its first message hands it to the pairing exchange.
13. Pairing is mutual and independent of roles: once paired, either device may be baby or parent.

## Signaling

| Message | Direction | Content |
| --- | --- | --- |
| `hello` | both, parent first | `version` (1), `deviceId`, `name`, `appVersion`; parent adds `watching` |
| `offer` | baby → parent | `sdp` |
| `answer` | parent → baby | `sdp` |
| `ice` | both | `candidate`, `sdpMid`, `sdpMLineIndex` |
| `busy` | baby → parent | parent limit reached (1 until phase 6, then 4) |
| `bye` | both | orderly close |

- Sequence: parent connects and sends `hello`; baby answers `hello`, then `offer`; parent sends
  `answer`; both send `ice` as candidates appear. Candidates that arrive before the remote
  description is set are held until it is.
- A `hello` with another protocol version, or (on the parent) a device ID other than the one
  discovered, closes the connection.
- `iceServers = []`: host candidates only.
- The signaling socket lives as long as the peer connection; its close counts as connection loss.
- Reconnect is always a full teardown and a fresh TLS connection and `PeerConnection`.

## Data channel `control` (ordered, reliable, created by the baby)

| Message | Direction | Content |
| --- | --- | --- |
| `watching` | parent → baby | boolean |
| `status` | baby → parent | `battery` (%), `charging`, `parents` (count), `thermalReduced`; every 30 s and on change |
| `level` | baby → parent | `dbfs`, 5 times per second |
| `ping` / `pong` | parent → baby / baby → parent | every 2 s; 6 s without one means the link is lost |
