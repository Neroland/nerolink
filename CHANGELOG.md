# Changelog

All notable changes to NeroLink. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- **In-app per-mod wiki** — new authed reads `GET /api/v1/wiki`, `GET /api/v1/wiki/{module}`
  and `GET /api/v1/wiki/{module}/{slug}` let the companion app browse each installed mod's
  documentation while playing. Fully mod-agnostic: any module that advertises a `wiki` data
  section through Core's link registry is automatically browsable (WIKI CONTRACT v1). The
  bridge serves its own wiki (and Core's) under the built-in `core` module, bundling
  `wiki/*.md` into resources at build time with a generated `index.json`. All content is
  public; the routes stay inside the existing authed + rate-limited pipeline. See
  [API](wiki/API.md#in-app-wiki).

## [0.0.1-alpha.2] — 2026-07-07

First alpha of the Neroland companion bridge.

### Added
- **HTTP + WebSocket bridge** (embedded Netty, default port 25580): versioned `/api/v1`
  with pairing, capability discovery, per-module snapshots, safe actions with
  request-id idempotency, live topic deltas, rate limiting, and privacy
  (export/erase/notice) endpoints.
- **Pairing** — `/nerolink pair` whispers a single-use code (+ Server ID when the
  relay is active); tokens are stored hashed, revocable via `/nerolink devices` /
  `/nerolink revoke`, and expire on inactivity.
- **Relay support** — `/nerolink setup` registers with a NeroLink relay in-game,
  stores the credentials per-world, and opens the outbound tunnel with no server
  restart (no port forwarding needed). Manual `relayUrl`/`relayKey` config override
  remains available.
- **Core module** — gates, alerts (ack/snooze action), energy/storage placeholders,
  and an installed-mods section (`core/mods`) with loader + MC version for
  companion-client update checks.
- **Notifications plumbing** — per-player, per-category opt-in preferences; push
  `notify` frames to the relay for players who are offline/not watching.
- **POPIA/GDPR** — own-data-only responses, hashed tokens, `PlayerDataErasure`
  integration (including relay push-token tombstones), no telemetry.
- Cross-loader: Fabric, Forge, NeoForge on Minecraft 26.1.2 and 26.2. Requires
  Neroland Core 2.0.0+ and nothing else.
- **Crash telemetry (opt-out)** — anonymous error reporting via Sentry (EU ingest),
  matching the rest of the Neroland family. Sends only NeroLink-touching stack traces
  plus mod/MC/loader/OS/Java versions; never tokens, pairing codes, relay keys,
  notification preferences, player identifiers, IPs, or world data. Per-session
  de-dup and a 10-event cap. Opt out with `telemetryEnabled = false` in
  `config/nerolink.properties` (client-local, not synced). See PRIVACY.md.