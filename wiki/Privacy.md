# Privacy

NeroLink is built to know as little about players as possible. This page summarises how
the **bridge mod** handles data; the repository's [`PRIVACY.md`](../PRIVACY.md) is the
authoritative document (written to satisfy POPIA in South Africa and the GDPR in the EU).

## What the bridge stores

Per player, keyed to the Minecraft account UUID:

- **Device tokens — hashed only** (SHA-256). The plaintext token is shown once at pairing
  and never stored or logged; it lives on the player's own device. Tokens expire after
  `tokenExpiryDays` of inactivity.
- **Notification preferences** — per-category booleans, **opt-in** (default off).
- **Pending pairing codes** — transient, in-memory, single-use, 5-minute expiry.

Per server (no player data): the relay registration from `/nerolink setup` — Server ID,
relay key, relay URL — in the world's saved data. The relay key is a secret and is never
logged or shown in chat.

That is the complete list. Game data shown in companion clients (energy, drones, quests,
stock) is read **live** from the owning mods and is never copied into NeroLink's storage.

## Own-data-only

Every API response is scoped to the authenticated player: a paired device can see and act
on **its own player's data only**. There is no surface for browsing other players, and
actions are re-validated server-side against the same permission, progression and
ownership rules as in-game play.

## Erasure & export

NeroLink registers with Neroland Core's shared data-erasure hook
(`data.PlayerDataErasure`): one erasure request purges a player's tokens, preferences and
pending codes **together with every other Neroland mod's data**, and sends a tombstone that
drops any relay-held push tokens. Players can trigger this — and a JSON export of their
bridge-held data — from a companion client via the privacy endpoints (see [API](API.md)):

- `POST /api/v1/privacy/erase`
- `GET /api/v1/privacy/export`
- `GET /api/v1/privacy/notice`
- `DELETE /api/v1/session` (revoke this device's token)

## The relay

If the server uses a relay, it forwards traffic between phones and the server; it stores
only the server registration (display name + **hashed** key) and, when push ships, device
push tokens. REST bodies, WebSocket frames and bearer tokens are forwarded **verbatim,
never persisted, never logged**. See [Relay](Relay.md).

## Logging & telemetry

Tokens, pairing codes, relay keys and player identifiers are **never logged at INFO**, so
they never reach a log line — and therefore never reach crash reporting.

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

Full detail and contact address are in the root [`PRIVACY.md`](../PRIVACY.md).

## See also

- [Configuration](Configuration.md)
- [API](API.md)
- [Relay](Relay.md)
- [Home](Home.md)
