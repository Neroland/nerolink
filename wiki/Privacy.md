# Privacy

NeroLink is built to know as little about players as possible. This page summarises how
the **bridge mod** handles data; the repository's [`PRIVACY.md`](../PRIVACY.md) is the
authoritative document (written to satisfy POPIA in South Africa and the GDPR in the EU).

## What the bridge stores

Per player, keyed to the Minecraft account UUID:

- **Device records** — the token **hashed only** (SHA-256; the plaintext token is shown
  once at pairing and never stored or logged, it lives on the player's own device), the
  device name you enter, and when you paired and last connected.
- **Notification preferences** — per-category booleans, **opt-in** (default off).
- **Pending pairing codes** — transient, in-memory, single-use, 5-minute expiry.

Per server (no player data): the relay registration from `/nerolink setup` — Server ID,
relay key, relay URL — in the world's saved data, and the bridge's TLS certificate
(`config/nerolink/bridge-tls.p12`). The relay key is a secret and is never logged or shown
in chat. While the relay tunnel is down, pending `erase` / `push_unbind` messages (player
UUID and device id only, at most 4096) wait in the same saved data until delivered.

Pairing rate limits count failed attempts per source for one minute — the client IP on
direct connections, or a salted, daily-rotating hash of it via the relay — in memory only.

That is the complete list. Game data shown in companion clients (energy, drones, quests,
stock) is read **live** from the owning mods and is never copied into NeroLink's storage.

## Retention

- Devices unused for longer than `tokenExpiryDays` (default **90 days**) are deleted by a
  sweep at bridge start and every 6 hours.
- Every token has an absolute lifetime of **365 days**.
- Revoking a device (`/nerolink revoke`, `DELETE /api/v1/session`, or expiry) deletes it,
  closes its live connection and removes its relay push registration.

## Own-data-only

Every API response is scoped to the authenticated player: a paired device can see and act
on **its own player's data only**. There is no surface for browsing other players, and
actions are re-validated server-side against the same permission, progression and
ownership rules as in-game play.

## Erasure & export

There are two levels of erasure:

- **Everything, everywhere — in-game.** `/neroland data eraseme` runs Neroland Core's
  shared data-erasure hook (`data.PlayerDataErasure`), which purges your NeroLink tokens,
  preferences and pending codes **together with every other Neroland mod's data**, and
  sends a tombstone that drops any relay-held push tokens.
- **NeroLink only — from the app.** "Delete my NeroLink data" (`POST /api/v1/privacy/erase`)
  erases NeroLink's own data: device tokens, preferences, pending pairing code, live
  connections and relay push registrations. It deliberately does **not** touch other mods,
  so a token on a phone can never wipe your game progress.

If the relay tunnel is down, the relay part is queued and delivered on reconnect.

Privacy endpoints (see [API](API.md)):

- `POST /api/v1/privacy/erase` (bridge-scoped)
- `GET /api/v1/privacy/export`
- `GET /api/v1/privacy/notice`
- `DELETE /api/v1/session` (revoke this device's token)

## The relay

Direct connections are TLS-encrypted end to end with the bridge's own certificate, which
the app pins at pairing.

If the server uses a relay, it forwards traffic between phones and the server; it stores
only the server registration (display name + **hashed** key) and, when push ships, device
push tokens. REST bodies, WebSocket frames and bearer tokens are forwarded **verbatim,
never persisted, never logged**. The relay is **not end-to-end encrypted**: it terminates
TLS and reads traffic in memory to forward it. See [Relay](Relay.md).

## Logging & telemetry

Tokens, pairing codes, relay keys and player identifiers are **never logged at INFO**, so
they never reach a log line — and therefore never reach crash reporting. Client-supplied
names are sanitised before logging.

The bridge includes optional, anonymous **crash reporting** via Sentry (EU servers,
`de.sentry.io`), matching Neroland Core's convention:

- **Opt-out, on by default.** Disable it any time with `telemetryEnabled = false` in
  `config/nerolink.properties`. The setting is **client-local — never synced**.
- **NeroLink errors only.** An event is sent only if its stack trace touches NeroLink code
  (`za.co.neroland.nerolink`); anything else is dropped before sending.
- **No personal data.** No IP, hostname, username or UUID; OS-account names are scrubbed
  from file paths. The payload is the stack trace plus the mod, Minecraft, loader, OS and
  Java versions — never tokens, pairing codes, relay keys, notification preferences or
  world data.
- **Bounded volume.** Per-session de-duplication and a hard cap of 10 events per session.
- **Known limitation:** NeroLink reports through the global Sentry client shared with the
  other Neroland mods.

Full detail and contact address are in the root [`PRIVACY.md`](../PRIVACY.md).

## See also

- [Configuration](Configuration.md)
- [API](API.md)
- [Relay](Relay.md)
- [Home](Home.md)
