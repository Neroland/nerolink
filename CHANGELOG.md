# Changelog

All notable changes to NeroLink. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [1.0.0] - 2026-10-01

The first production release. Security, privacy and robustness
hardening of the bridge; the companion app must be updated for direct-mode pairing.
Requires **Neroland Core 1.13.0 or newer, below 2.0**.

### Security

- **Direct mode is now TLS.** The local listener (default port `25580`) serves TLS with a
  long-lived self-signed ECDSA P-256 certificate generated on first start and stored at
  `config/nerolink/bridge-tls.p12` (owner-only permissions where the filesystem supports
  them). The same certificate is reused across worlds and restarts; delete the file to rotate
  it, after which every direct-mode device must re-pair. A corrupt file is moved aside to
  `bridge-tls.p12.broken` and a new certificate is generated. The companion app pins the
  certificate's SHA-256 fingerprint.
- **Channel-bound direct pairing.** On the direct TLS listener the app sends
  `codeProof = hex(PBKDF2-HMAC-SHA256(password = code uppercased with dashes removed, salt =
  "nerolink-pair-v1:" + lowercase hex certificate fingerprint, 100000 iterations, 32 bytes))`
  instead of the code, so the code never crosses the wire and a pairing through a different
  certificate fails. PBKDF2 makes brute-forcing a captured proof take months rather than the
  code's 5-minute life; the bridge derives each code's proof once, in the background, when
  `/nerolink pair` issues it, so failed attempts cost the server nothing. A plain
  `code` on the direct listener is rejected with `400 VALIDATION` ("codeProof required on
  direct connections (update the NeroLink app)"). Over the relay the plain `code` is still sent
  (TLS to the relay's public certificate).
- **Pairing brute-force protection.** Failed redemptions are budgeted: 5 per source per
  minute (the client IP on direct connections; on relay connections a salted, daily-rotating
  hash of the client IP forwarded by the relay), with a 120-per-minute global backstop.
  Attempts are refused with `429 RATE_LIMITED` (`retryAfterMs: 60000`) only while a budget is
  exceeded; pending codes are never voided, so a flood cannot lock players out once it stops.
- **Hardening.** HTTP bodies are capped at 16 KiB; 16 concurrent TCP connections per address;
  connections idle for 90 s are closed; untrusted JSON nested deeper than 32 levels is rejected
  before parsing; WebSocket control frames are charged to the per-device rate limit; at most 32
  topic subscriptions per connection, and topic ids must look like `module.section`; every
  response carries `Cache-Control: no-store`; client-supplied names are sanitised before
  logging; a request waiting on the server thread times out after 10 s with `503`.
- Single-player and integrated servers bind `127.0.0.1` by default, so a single-player world
  never opens a network port on its own (see `singleplayerLanAccess`).

### Added

- `/nerolink pair` now whispers a **Direct address** (only when direct mode is reachable from
  the network) and a **Security code** — the first 16 hex digits of the certificate
  fingerprint as `XXXX-XXXX-XXXX-XXXX`. The app shows the same code; the player checks they
  match.
- Config keys `directEnabled` (default `true`; `false` = relay only), `tlsEnabled` (default
  `true`; plain HTTP is refused unless `bindAddress` is `127.0.0.1`, and is for local tooling
  only: the app's direct mode requires TLS), `singleplayerLanAccess` (default `false`) and
  `maxDevicesPerPlayer` (default `5`, range `1`–`64`).
- `GET /api/v1/session` (authenticated) returns `{playerUuid, deviceId, deviceName}` for the
  calling token; the relay uses it to bind push registrations to the real player.
- `POST /api/v1/pair` responses now include `deviceId`.
- Relay tunnel: `req` frames may carry `ip` (a salted hash, never an IP); new bridge→relay
  frame `push_unbind {playerUuid, deviceId}`.
- Tests: `PairingServiceTest` (10), `BridgeTlsTest` (4, including a pairing-proof vector shared
  with the app), `JsonTest` (4) and `RateLimiterTest` (1). 38 tests pass on `:neoforge:26.2`.

### Changed

- **Default relay moved to `https://relay.nerolandmc.net`** (was `https://nerorelay.neroserver.xyz`).
  This changes the `relayOrigin` default used by `/nerolink setup`. Existing servers keep the
  origin already written to `config/nerolink.properties` and their stored registration: set
  `relayOrigin` to the new host and run `/nerolink setup` again, then re-pair devices (the app URL
  changes). Dev tooling (`tools/setup_dev_relay.py`, VS Code tasks), README and wiki updated.
- Modrinth page description links the NeroLink app beta and the ecosystem pages on
  `nerolandmc.net`.
- `maxClients` (default `64`) is now really the global cap on concurrent live-update
  (WebSocket) connections, direct and relay combined. Extra connections are closed with `1013`
  (direct) / `4503` (relay) "server busy". The per-player device cap moved to
  `maxDevicesPerPlayer`.
- **`POST /api/v1/privacy/erase` is now bridge-scoped.** It erases NeroLink's own data (device
  tokens, preferences, pending pairing code, live sockets, relay push registrations) but no
  longer triggers Core's erasure across every Neroland mod — a bearer token on a phone should
  not be able to wipe game progress. The response carries `scope: "bridge"` and a note pointing
  at `/neroland data eraseme` for full erasure. Core's `PlayerDataErasure` hook still purges
  NeroLink data when `/neroland data eraseme` runs.
- **Retention.** Expired devices (inactive longer than `tokenExpiryDays`, default 90) are
  deleted by a sweep at bridge start and every 6 hours, and every token has an absolute
  lifetime of 365 days.
- Relay `erase` tombstones and `push_unbind` frames are queued persistently in the world's
  relay settings (capped at 4096 entries) while the tunnel is down, and delivered when it
  reconnects. Only queued when a relay is configured.
- The default `privacyNoticeText` now lists everything stored: hashed device token, the device
  name the player enters, pairing and last-connected times and notification preferences; that
  devices you stop using are deleted automatically; export/delete from the app; and the relay transit
  note.
- Revoking a device (`/nerolink revoke`, `DELETE /session`, expiry) now also closes its live
  WebSocket immediately and unbinds its relay push registration.
- Discovery `bridgeVersion` and `coreVersion` report the real installed versions from loader
  metadata.
- `snapshotCadenceHotMs` / `snapshotCadenceColdMs` are documented as reserved (not used yet).
- Metadata: a real mod description in all three loader manifests; `displayURL` / homepage
  `https://neroland.co.za`; Fabric's Core dependency range is now `>=<pin> <2.0.0` like the
  other loaders. Build pin Core `1.13.0`.

### Fixed

- `/nerolink setup` while the relay tunnel was already up could leave the bridge reconnecting
  in a loop (the old connection's close tore down the new one). Connections now carry a
  generation and stale callbacks are ignored.
- Live-update deltas published while a batch was being sent could be lost; batching is now
  atomic per topic.
- Queued relay erasures are removed from the queue only after they were actually written to
  the tunnel.
- `DELETE /session` now revokes on the server thread, so a revoke can never be lost to a
  concurrent world save; devices found expired at login are left for the retention sweep, which
  also closes their sockets and unbinds their relay push.
- The device-token store is thread-safe (it was read and written from network threads while
  the game thread saved it).
- A reconnecting device no longer loses its live subscription when its old socket finishes
  closing.
- Stop/restart: the listener waits for its socket and threads to shut down (`SO_REUSEADDR`), so
  reopening a single-player world quickly no longer fails with "address in use".
- Discovery no longer reports the hard-coded `bridgeVersion` "0.0.1-alpha.1" / `coreVersion`
  "2.0.0".

### Known limitations

- The relay is not end-to-end encrypted: it terminates TLS and can read traffic in memory to
  forward it (it never stores it).
- Crash telemetry uses the global Sentry client shared with other Neroland mods.
- The `serverId` in pair/discovery responses is still derived from the world name.

## [0.2.0-alpha.1] - 2026-09-24

EMI compatibility. No gameplay, id, tag or config change.

### Added

- **EMI support.** NeroLink adds no recipes of its own, so nothing needed a plugin. The build now
  compiles against the community EMI Unofficial Port (Unstable), the only EMI build for Minecraft 26.x,
  and dev clients load it with `-PwithEmi` (default runs stay JEI-only). EMI stays optional.

## [0.1.0-alpha.1] - 2026-09-20

Minecraft **26.3** support, plus the changes previously listed under *Unreleased*.

### Added
- **In-app per-mod wiki** — new authed reads `GET /api/v1/wiki`, `GET /api/v1/wiki/{module}`
  and `GET /api/v1/wiki/{module}/{slug}` let the companion app browse each installed mod's
  documentation while playing. Fully mod-agnostic: any module that advertises a `wiki` data
  section through Core's link registry is automatically browsable (WIKI CONTRACT v1). The
  bridge serves its own wiki (and Core's) under the built-in `core` module, bundling
  `wiki/*.md` into resources at build time with a generated `index.json`. All content is
  public; the routes stay inside the existing authed + rate-limited pipeline. See
  [API](wiki/API.md#in-app-wiki).

### Minecraft 26.3

- **Minecraft 26.3** as a new Stonecutter node on every loader — NeoForge `26.3.0.7-beta`,
  Forge `26.3-66.0.2` and Fabric (fabric-api `0.161.0+26.3`, NeoForm `26.3-1`) — built alongside
  26.1.2 and 26.2, so every release now ships **nine** loader × version jars.
- VS Code run/debug configurations (`.vscode/launch.json`, `.vscode/tasks.json`) gain the three
  26.3 cells; the "Build all" task now builds all nine.
- CI (`multiloader.yml`, `publish.yml`) builds, attaches and publishes the 26.3 jars.
- Requires **Neroland Core 1.13.0** (was `1.4.0`) — the first Core release with a 26.3
  build. The loader range still derives from the pin (`[${nerolandcore_version},2.0)`).
- JEI pins moved to the newest published builds on each Minecraft version: `29.40.0.101` (26.1.2), `30.35.0.223` (26.2) and `31.3.0.18` (26.3). Compile-time API only — JEI remains a soft dependency and the shipped jar gains no hard requirement.

### 26.3 port notes

- Build: the shared `common/` Java source is now preprocessed by Stonecutter for every non-active node (`stonecutterProcessCommon`), so common code can carry `//? if >=26.3 {` blocks, and `common/src/main/resources-<mc>` overlay folders are merged over the shared resources for matching nodes (`mergeCommonResources`). The active node still compiles the raw `common/` folder.
- Build plugins aligned with Neroland Core: ModDevGradle `2.0.147` (the older 2.0.141 cannot set up NeoForge 26.3), ForgeGradle `7.0.40`, Stonecutter `0.9.8`.
- NeoForge metadata: the deprecated `logoFile` property is replaced by `iconFile` on 26.2+ (the logo is a square 256x256 PNG) while 26.1.2, whose FML only understands the old key, still gets `logoFile` — the key is chosen per cell when the manifest is expanded. This clears NeoForge 26.2+'s dev-only "uses the deprecated `logoFile` property" warning screen. The Forge manifest is unchanged: `logoFile` is still the only key Forge supports.

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
  integration (including relay push-token tombstones); the only telemetry is
  opt-out crash reporting that carries no personal data (see below).
- Cross-loader: Fabric, Forge, NeoForge on Minecraft 26.1.2 and 26.2. Requires
  Neroland Core 1.4.0 or later and nothing else.
- **Crash telemetry (opt-out)** — anonymous error reporting via Sentry (EU ingest),
  matching the rest of the Neroland family. Sends only NeroLink-touching stack traces
  plus mod/MC/loader/OS/Java versions; never tokens, pairing codes, relay keys,
  notification preferences, player identifiers, IPs, or world data. Per-session
  de-dup and a 10-event cap. Opt out with `telemetryEnabled = false` in
  `config/nerolink.properties` (client-local, not synced). See PRIVACY.md.