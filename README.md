# NeroLink

> Part of the [Neroland](../neroland-mc-ecosystem) sci-fi Minecraft mod
> ecosystem, built on **Neroland Core**.

**NeroLink is a server-side bridge mod.** It embeds a small HTTP + WebSocket
server that lets companion clients check on a Neroland server while away —
energy and progression at a glance, alerts, and a small set of safe,
server-validated actions. It is *a window, not a controller*: it never edits
the world, never moves the player, and does nothing a player couldn't do
standing at the relevant block in-game.

Only **Neroland Core 2.0.0+** is required. Every other Nero mod is a
progressive enhancement, discovered at connect time — a Core-only server is
already useful (progression gates, alerts, server status). Companion clients
speak the NeroLink API described in the ecosystem docs.

**Status:** `0.0.1-alpha.1` — v1 bridge implemented (pairing, discovery,
snapshots, actions, WebSocket deltas, privacy endpoints). No gameplay content;
this mod adds no blocks or items.

## Build targets

- **Minecraft:** 26.1.2 and 26.2
- **Loaders:** NeoForge, MinecraftForge/Forge, Fabric (the "6 cells")
- **Java:** 25
- Mod id: `nerolink` · package `za.co.neroland.nerolink`
- **Requires:** Neroland Core `[2.0, 3.0)` (loads before NeroLink)

## What the bridge does

- **Pairing & tokens.** A player runs `/nerolink pair` in-game; the bridge
  whispers them a single-use `XXXX-XXXX` code (5-minute TTL, bound to their
  UUID). A client redeems it once via `POST /api/v1/pair` for a long-lived,
  revocable **device token**. No passwords, no email — the in-game session
  *is* the identity proof.
- **Discovery.** `GET /api/v1/discovery` reports the API revision, bridge/Core
  versions, server identity and the capability map — which Nero modules are
  present (and which are `absent`), so a client builds its UI from exactly what
  the server supports.
- **Snapshots.** `GET /api/v1/{module}/{section}` serves already-player-scoped,
  cached data from each mod's registered provider. The built-in `core` module
  supplies progression **gates** and **alerts** directly from Core;
  `energy`/`storage` are well-formed but empty in v1 (Core exposes no cheap
  global index and the bridge never scans loaded chunks — see the config
  `note`). `mods` is a server-wide snapshot of the installed Neroland mods
  (id/name/version) plus the running `loader` and `mcVersion`, so a client can
  render a mods overview and run update checks.
- **Actions.** `POST /api/v1/actions/{module}/{action}` re-validates
  server-side (ownership, gates, config, online/offline), deduplicates by
  `requestId` for 10 minutes, and executes on the server thread through the
  owning mod. The built-in `core/ack_alert` action acknowledges/snoozes your
  own alerts.
- **Live updates.** `GET /ws/v1` (Bearer-authenticated) streams per-topic
  deltas, batched at most once per second, with a consistent-start `snapshot`
  frame on subscribe and a 30-second heartbeat.
- **Rate limits.** Per-token token-bucket (default 60 req/min, `429` +
  `retryAfterMs` on breach) and a global concurrent-client cap.

Everything a client sees is scoped to the authenticated player. Game state is
only ever touched on the server thread — the Netty I/O threads marshal work
across via `server.execute(...)`.

## Pairing quickstart

1. In-game: `/nerolink pair` → note the whispered `XXXX-XXXX` code.
2. In your companion client: enter the server address (`host:25580` by
   default) and the code.
3. The client stores the returned token securely and uses
   `Authorization: Bearer <token>` on every call.
4. Manage devices in-game with `/nerolink devices` and
   `/nerolink revoke <device-id>`; ops can check `/nerolink status`.

## Configuration

Config lives in Core's config system as `nerolink.properties` (reloadable with
`/neroland config reload`). Key levers:

| Key | Default | Purpose |
| --- | --- | --- |
| `enabled` | `true` | Master switch; when false no socket is bound |
| `port` | `25580` | HTTP + WebSocket port (change needs a restart) |
| `bindAddress` | `0.0.0.0` | Interface to bind (`127.0.0.1` = local only) |
| `rateLimitPerMinute` | `60` | Per-token REST budget per minute |
| `maxClients` | `64` | Global concurrent-client cap |
| `tokenExpiryDays` | `90` | Inactivity token expiry (checked lazily) |
| `readOnly` | `false` | Refuse all actions (snapshots still served) |
| `allowOfflineActions` | `true` | When false, all actions need online player |
| `actionsDisabled` | *(empty)* | Comma-separated `module/action` ids to block |
| `snapshotCadenceHotMs` | `5000` | Hot-section cache cadence |
| `snapshotCadenceColdMs` | `30000` | Cold-section cache cadence |
| `relayOrigin` | `https://nerorelay.neroserver.xyz` | Relay used by `/nerolink setup` |
| `relayUrl` | *(empty)* | **Advanced** manual-override tunnel URL (see below) |
| `relayKey` | *(empty)* | **Advanced** manual-override server key — **keep secret** |
| `privacyNoticeText` | *(a notice)* | Text from `GET /privacy/notice` |

## Remote access via the relay (behind NAT)

A home or NAT'd server with no port forwarding can still serve companion
clients through the **[NeroLink relay](../nerolink-relay)** — a small Cloudflare
Worker. The bridge dials *out* and holds one WebSocket tunnel to the relay;
phones connect to the relay; the relay marries the two and forwards traffic
verbatim. The local HTTP/WS listener and the relay tunnel are independent —
either, both, or neither may run at once.

**Setup (in-game, recommended):**

1. Point `relayOrigin` at your relay if it isn't the default
   `https://nerorelay.neroserver.xyz` (deploy your own from
   [`../nerolink-relay/README.md`](../nerolink-relay), or use a shared one).
2. An op runs it **once**:
   ```
   /nerolink setup
   ```
   The bridge registers with the relay off-thread, stores the returned
   credentials per-world (never in a file you edit by hand), and brings the
   tunnel up immediately — **no server restart, no curl, no scripts**. The op
   sees the **Server ID** (bold/gold), the app URL, and a
   "tunnel connecting — check `/nerolink status`" hint. The `serverKey` is
   never shown in chat and never logged.
   - Register against a one-off relay with `/nerolink setup <https-origin>`.
   - Re-register (discard the stored credentials and get a fresh id) with
     `/nerolink setup force` (optionally `/nerolink setup force <origin>`).
     Running plain `/nerolink setup` again when already registered just
     re-dials the existing tunnel.
3. Players run `/nerolink pair` — the whisper now shows the **Server ID**
   prominently. **That id plus the one-time pairing code is all the app needs**
   (no address to type). The whole API (pairing, discovery, snapshots, actions,
   live WebSocket deltas) works exactly as on the LAN, just through the relay.

> If the relay has `REGISTRATION_OPEN=false`, `/nerolink setup` reports
> "registration is closed on this relay" and does nothing until the operator
> reopens it.

**Advanced / manual override (no in-game setup):** if you'd rather paste
credentials yourself, register with
`curl -X POST https://<relay>/register -d '{"serverName":"Neroland SMP"}'`
(returns `serverId`, a once-shown `serverKey`, a `tunnelUrl` and a `baseUrl`)
and set **both** `relayUrl` = the `tunnelUrl` and `relayKey` = the `serverKey`
in `nerolink.properties`. When both are set they **take precedence** over any
`/nerolink setup` registration and activate on the next server start (`ws://`
is accepted for a local `wrangler dev` relay). Otherwise leave both blank and
use `/nerolink setup`.

`/nerolink status` reports the tunnel as `Relay: connected` / `connecting` /
`disabled` and includes the active **Server ID**. The relay key lives only in
per-world storage (or config, for the override) and is never logged; the
bridge logs the relay **host** only. When enabled, the bridge also emits push
`notify` frames for opted-in notification categories to players who are not
currently watching live, and an `erase` tombstone (dropping a player's push
tokens on the relay) whenever a POPIA/GDPR erasure runs.

### Testing the relay from the dev launchers

`tools/setup_dev_relay.py` wires the relay into every `runClient`/`runServer`
run directory in one step: it registers this dev instance with a relay, keeps
the credentials in `.dev-relay.json` (gitignored — the key is a secret) and
writes `relayUrl`/`relayKey` into each run's `config/nerolink.properties`.

```sh
# terminal 1 — local relay (from ../nerolink-relay); --ip 0.0.0.0 lets your phone reach it
npx wrangler dev --ip 0.0.0.0

# terminal 2 — register + enable in all dev run configs
python tools/setup_dev_relay.py                    # local wrangler dev relay
python tools/setup_dev_relay.py --relay https://nerorelay.neroserver.xyz   # deployed relay
python tools/setup_dev_relay.py --off              # switch the relay back off
```

Matching VS Code tasks exist (`Relay: start local relay`, `Relay: register +
enable in dev runs`, `Relay: disable in dev runs`). Launch any client/server
run afterwards and `/nerolink status` should report `Relay: connected`; the
script prints the `https://<relay>/s/<serverId>` address to use in the app
(with a local relay, substitute your machine's LAN IP for `localhost`).

## Privacy (POPIA / GDPR)

The bridge stores the **lawful minimum**, all keyed to the Minecraft account
the server already knows:

- **Device tokens** — stored as a SHA-256 **hash** only (the plaintext token
  is returned to the client once and never persisted or logged). Expire after
  configurable inactivity.
- **Notification preferences** — per-player category booleans, opt-in.
- **Pending pairing codes** — transient, in-memory, single-use, 5-minute TTL.

No emails, no location, no chat, no names beyond the Minecraft username the
server already has. The bridge keeps **no shadow copies** of mod data —
anything a client shows is read live from the owning mods. Tokens, UUIDs and
player names are never logged at INFO.

- `GET /api/v1/privacy/export` returns everything the bridge holds for you
  (device metadata + prefs) as JSON.
- `POST /api/v1/privacy/erase` fires Core's shared `PlayerDataErasure` hook —
  purging your bridge data (tokens, prefs, pending codes) alongside every other
  mod's data — and drops your live sockets.
- `GET /api/v1/privacy/notice` returns the server's data-processing notice.
- `DELETE /api/v1/session` revokes the calling device's token.

## Layout

The build is the repo root, with a flattened cross-loader structure driven by
Stonecutter:

- `common/` — shared, loader-agnostic source spliced into every loader node
  (the whole bridge lives here; each loader only wires init + command +
  lifecycle glue)
- `fabric/` — Fabric Loom
- `forge/` — ForgeGradle
- `neoforge/` — ModDevGradle
- `stonecutter.gradle` — the real root build script; `build.gradle` is
  intentionally inert

## Building

```sh
./gradlew :fabric:26.2:build          # one cell
./gradlew :neoforge:26.1.2:build :neoforge:26.2:build \
          :forge:26.1.2:build :forge:26.2:build \
          :fabric:26.1.2:build :fabric:26.2:build   # all six
```

Core 2.0.0 is resolved from `mavenLocal()` (run `./gradlew publishToMavenLocal`
in `../neroland-core`) or from GitHub Packages on CI. See
[`AGENTS.md`](AGENTS.md) / [`CLAUDE.md`](CLAUDE.md) for contributor context.

## Docs

Design, API and dependency docs for this mod live in the umbrella repo under
[`../neroland-mc-ecosystem/nerolink`](../neroland-mc-ecosystem/nerolink),
including the full **API specification** the companion clients target.
