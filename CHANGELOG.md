# Changelog

All notable changes to NeroLink. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

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