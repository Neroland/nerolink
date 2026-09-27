# NeroLink — Privacy

NeroLink is built to know as little about players as possible. This document covers
the **bridge mod** (what a server running NeroLink stores and serves). Written to
satisfy POPIA (South Africa) and the GDPR (EU).

## What the bridge stores

Per player, keyed to the Minecraft account UUID:

- **Device records** — the token **hashed only** (SHA-256; the plaintext token is shown
  once at pairing and never stored or logged, it lives on the player's own device), the
  device name the player enters, and the pairing and last-connected times.
- **Notification preferences** (per-category booleans, all off by default).
- **Pending pairing codes** (in memory, single-use, 5-minute expiry).

Per server (no player data): the relay registration (`/nerolink setup`) — server id,
relay key, relay URL — in the world's saved data, and the bridge's TLS certificate and
private key (`config/nerolink/bridge-tls.p12`, owner-only permissions where supported).
While a relay tunnel is down, pending relay `erase` and `push_unbind` messages (player
UUID and device id only) are queued in the same saved data, capped at 4096, until they
are delivered.

Pairing brute-force protection counts failures per source for one minute: the client IP
on direct connections, or a salted, daily-rotating hash of it forwarded by the relay.
These counters are held in memory only.

That is the complete list. Game data shown in companion clients (energy, drones,
quests, stock) is read live from the owning mods and never copied into NeroLink's
storage.

## Retention

- Devices unused for longer than `tokenExpiryDays` (default 90 days) are deleted by a
  sweep at bridge start and every 6 hours.
- Every device token has an absolute lifetime of 365 days, however often it is used.
- Revoking a device (`/nerolink revoke`, `DELETE /api/v1/session`, or expiry) deletes it, closes
  its live connection and removes its relay push registration.

## Scope of every response

Every API response is scoped to the authenticated player: a paired device can see and
act on **its own player's data only**. There is no surface for browsing other
players. Actions are re-validated server-side against the same permission,
progression and ownership rules as in-game play.

## The relay

A relay (if the server uses one) forwards traffic between phones and the server; it
stores only the server registration (hashed key) and — when push notifications ship —
device push tokens. Traffic is encrypted between the phone and the relay and between the
relay and the bridge, but the relay is **not end-to-end encrypted**: it terminates TLS and
reads traffic in memory to forward it. Frame contents are never persisted.

Direct connections (no relay) are TLS-encrypted end to end with the bridge's own
certificate, which the companion app pins at pairing.

## Erasure & export

NeroLink registers with Neroland Core's shared data-erasure hook
(`data.PlayerDataErasure`): `/neroland data eraseme` (in-game) purges a player's
tokens, preferences, and pending codes together with every other Neroland mod's data,
and sends a tombstone that drops any relay-held push tokens.

From a companion client, a player can export their bridge-held data as JSON and use
"Delete my NeroLink data". That deletion is **bridge-scoped**: it erases NeroLink's own
data (device tokens, preferences, pending pairing code, live connections, relay push
registrations) but not other mods' data, so a token on a phone cannot wipe game
progress. Full erasure stays an in-game action.

If the relay tunnel is down when an erasure or revocation happens, the relay message is
queued and delivered when the tunnel reconnects.

## Logging & telemetry

Tokens, pairing codes, relay keys, and player identifiers are never logged at INFO,
so they never reach a log line — and therefore never reach crash reporting.

Client-supplied names (such as device names) are sanitised before they are logged.

The bridge includes optional, anonymous **crash reporting** via Sentry on EU servers
(`de.sentry.io`), following Neroland Core's convention:

- **Opt-out, on by default.** Disable it any time by setting `telemetryEnabled = false`
  in `config/nerolink.properties`. The setting is client-local — it is never synced.
- **NeroLink errors only.** An event is sent only if its stack trace touches NeroLink
  code (`za.co.neroland.nerolink`); anything else is dropped before sending.
- **No personal data.** No IP, no hostname, no username or UUID. OS-account names are
  scrubbed from any file paths. The payload is the stack trace plus the mod, Minecraft,
  loader, OS, and Java versions — never tokens, pairing codes, relay keys, notification
  preferences, or world data.
- **Bounded volume.** Per-session de-duplication and a hard cap of 10 events per session.
- **Known limitation:** NeroLink reports through the global Sentry client shared with the
  other Neroland mods.

Questions: **dario@neroland.co.za**.
