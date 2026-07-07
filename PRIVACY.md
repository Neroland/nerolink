# NeroLink — Privacy

NeroLink is built to know as little about players as possible. This document covers
the **bridge mod** (what a server running NeroLink stores and serves). Written to
satisfy POPIA (South Africa) and the GDPR (EU).

## What the bridge stores

Per player, keyed to the Minecraft account UUID:

- **Device tokens — hashed only** (SHA-256). The plaintext token is shown once at
  pairing and never stored or logged; it lives on the player's own device.
- **Notification preferences** (per-category booleans, all off by default).
- **Pending pairing codes** (single-use, 5-minute expiry).

Per server (no player data): the relay registration (`/nerolink setup`) — server id,
relay key, relay URL — in the world's saved data.

That is the complete list. Game data shown in companion clients (energy, drones,
quests, stock) is read live from the owning mods and never copied into NeroLink's
storage.

## Scope of every response

Every API response is scoped to the authenticated player: a paired device can see and
act on **its own player's data only**. There is no surface for browsing other
players. Actions are re-validated server-side against the same permission,
progression and ownership rules as in-game play.

## The relay

A relay (if the server uses one) forwards traffic between phones and the server; it
stores only the server registration (hashed key) and — when push notifications ship —
device push tokens. Frame contents are forwarded, never persisted.

## Erasure & export

NeroLink registers with Neroland Core's shared data-erasure hook
(`data.PlayerDataErasure`): one erasure request purges a player's tokens,
preferences, and pending codes together with every other Neroland mod's data, and
sends a tombstone that drops any relay-held push tokens. Players can also trigger
erasure and a JSON export of their bridge-held data from a companion client
(privacy endpoints in the API).

## Logging & telemetry

Tokens, pairing codes, relay keys, and player identifiers are never logged at INFO,
so they never reach a log line — and therefore never reach crash reporting.

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

Questions: **dario@neroland.co.za**.
