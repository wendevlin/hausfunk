# Hausfunk wire protocol (version 1)

Kept in sync with the code. Implemented: discovery, mutual TLS, pairing and signaling (phase 3),
the data channel with liveness, status and sound level (phase 5).

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
- The baby lets any client certificate complete the handshake (anyone may ask to pair) and
  classifies it afterwards: `TRUSTED` if pinned, else `PAIRING`. Every connection then sends its
  first message, read within 15 s of the TCP accept (handshake included):
  - `pair_commit` starts the pairing exchange (also from a trusted device that wants to pair
    again);
  - `hello` from a `TRUSTED` device that is still in the trust store starts signaling;
  - `hello` from any other device is answered with `unpair` (see Signaling);
  - anything else closes the connection.
- A `PAIRING` connection only ever reaches the pairing exchange, never signaling. One pairing at
  a time; after a failed attempt no device can pair for 10 s and that device not for 60 s.
- The identity key signs TLS handshakes inside the Keystore (purposes SIGN/VERIFY, digests
  SHA-256 and NONE; the platform hands TLS signatures to the Keystore as raw digests).
- Limits: connect/handshake timeout 10 s, at most 8 concurrent connections on the baby, at most 1
  `PAIRING` connection.
- Read timeout 30 s while the session is set up (until the baby has the `answer`, the parent
  the `offer`). After that the socket may stay idle; liveness comes from the data channel
  `ping`/`pong` and the ICE state (see "Data channel").
- Framing: 4-byte big-endian length prefix, then UTF-8 JSON, maximum 64 KiB per frame.
  Unknown message types are ignored; malformed or oversize frames close the connection.
- Every message is `{ "type": ..., ... }`. The sender's device ID travels in `hello` and must equal
  the fingerprint of the certificate the peer presented, otherwise the connection is closed.

## Pairing (commit-reveal SAS inside TLS)

Messages: `pair_commit { commitment }`, `pair_reveal { nonce, deviceId, name }`, `pair_confirm`,
`pair_abort`; commitment and nonce are base64. Implemented in `pairing/` (pure logic in
`PairingProtocol.kt`, emoji table in `SasEmoji.kt` from `@matrix-org/spec` 1.16.0).

1. The parent lists every `_babyphone._tcp` service; the user taps one that is not paired.
   (Changed from the plan's 120 s pairing window, see DECISIONS.) The baby takes the connection
   as described under Transport; its first message `pair_commit` routes it to the pairing
   handler.
2. Parent picks the advertised baby and connects with a one-connection trust manager that accepts
   and records any server certificate.
3. `fpP`, `fpB`: SHA-256 fingerprints (32 bytes) of parent and baby certificates from the TLS session.
4. Parent → `pair_commit { commitment: base64(SHA-256("babyphone-commit-v1" || nP)) }`, `nP` = 32 random bytes.
5. Baby → `pair_commit` with its own `nB`.
6. Parent, only after receiving the baby's commitment → `pair_reveal { nonce: base64(nP), deviceId, name }`.
7. Baby verifies the commitment and `deviceId == hex(fpP)`, else `pair_abort` and close; then
   → `pair_reveal { nonce: base64(nB), deviceId, name }`. Parent verifies the same way.
8. `sas = SHA-256("babyphone-sas-v1" || nP || nB || fpP || fpB)`; the first 24 bits, big-endian,
   split into 4 × 6 bits, index the 64-emoji table of the Matrix SAS verification. The app shows
   bundled Noto emoji images, not the system font, so every phone shows the same pictures. 24 bits
   suffice because of the commitments: an attacker in the middle gets one guess per attempt
   (1 in 16.7 million) and a failed attempt blocks the device for 60 s.
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
| `unpair` | both | "I removed the pairing with you"; then the connection closes |

`unpair` is sent when a user removes a paired device while it is connected, and by a baby that
receives `hello` on a connection it classified `PAIRING` (a parent that still trusts a baby which
removed it). The receiver removes the sender from its trust store and tells its user once. It is
trusted only on a connection pinned to the sender's certificate (the parent's side) or from a
peer whose certificate the receiver trusts (the baby's side).

- Sequence: parent connects and sends `hello`; baby answers `hello`, then `offer`; parent sends
  `answer`; both send `ice` as candidates appear. Candidates that arrive before the remote
  description is set are held until it is.
- A `hello` with another protocol version, or (on the parent) a device ID other than the one
  discovered, closes the connection.
- `iceServers = []`: host candidates only.
- The signaling socket lives as long as the peer connection; its close counts as connection loss.
- Reconnect is always a full teardown and a fresh TLS connection and `PeerConnection`.

## Data channel

One ordered, reliable data channel named `control`, created by the baby. Each message is one
UTF-8 JSON text `{ "type": ..., ... }`, decoded into a typed class; unknown types are ignored,
malformed ones dropped (logged once per connection).

| Message | Direction | Content |
| --- | --- | --- |
| `ping` / `pong` | parent → baby / baby → parent | every 2 s |
| `status` | baby → parent | `battery` (0–100, -1 unknown), `charging`, `parents`, `thermalReduced`; on open, after the first `ping`, every 30 s and on every battery change |
| `level` | baby → parent | `dbfs`: the loudest 100 ms RMS window of the raw capture since the last message, 5 times per second, -90 to 0 |
| `watching` | parent → baby | `watching`: the video is visible (app open or picture-in-picture); sent on channel open and on every change |

Liveness and reconnection:

- The link counts as up only when the first `pong` arrives (not when ICE connects): only then is
  the parent "connected" and a running alarm stops.
- The parent counts the link as lost after 6 s without `pong`, on ICE `disconnected`/`failed`,
  when the signaling socket closes, or if no `pong` arrived within 20 s of starting the attempt.
- Pairing connections have no read timeout (waiting for the other user may take long); the
  exchange's own 120 s limit ends them.
- The baby drops a parent after 6 s without `ping` (15 s allowed for setup after the `answer`).
- The parent reconnects on its own with a full new connection: first to the address that last
  worked (3 s connect timeout) if discovery now reports a different one, then to the discovered
  one. Backoff 1, 2, 4, 8, then every 10 s; a new network triggers an immediate attempt.
  After `busy` the parent retries every 10 s.

Several parents (phase 6):

- The baby serves up to 4 parents at once, each with its own peer connection on the shared
  tracks; a fifth gets `busy`. The same parent connecting again replaces its old connection.
- Video is encoded only for parents whose last `watching` (or the `hello` field; absent means
  true) is true: the video sender's encoding is set `active = false` otherwise. Sound always goes
  to every parent.
- The camera runs only while a parent watches or the baby's own preview is on screen.
- `status.parents` is the number of connected parents.
