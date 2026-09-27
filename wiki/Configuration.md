# Configuration

NeroLink registers its config with Neroland Core's config system, so it lives in
`config/nerolink.properties` and reloads with `/neroland config reload`. Most levers are
consulted **per request**, so a reload takes effect **live** without a bridge restart. The
exceptions are anything bound at server-start — the socket **`port`** and **`bindAddress`**,
**`directEnabled`**, **`tlsEnabled`**, **`singleplayerLanAccess`**, and the master
**`enabled`** switch — which need a **server restart** (or, in single-player, reopening the
world).

## Every key

| Key | Default | When it applies | What it does |
| --- | --- | --- | --- |
| `enabled` | `true` | restart | Master switch. When `false` the bridge binds no socket and serves nothing. |
| `port` | `25580` | restart | TCP port the direct-mode HTTPS + WebSocket listener binds (range `1024`–`65535`). |
| `bindAddress` | `0.0.0.0` | restart | Interface the direct listener binds on a **dedicated** server. `0.0.0.0` = all interfaces; `127.0.0.1` = this machine only (use the relay for phones). Single-player worlds always bind `127.0.0.1` unless `singleplayerLanAccess=true`. |
| `directEnabled` | `true` | restart | Run the direct-mode listener. `false` = relay only (the relay tunnel is outbound and needs no listener). |
| `tlsEnabled` | `true` | restart | Encrypt the direct listener with the bridge's self-signed certificate (`config/nerolink/bridge-tls.p12`), which the app pins at pairing. The NeroLink app's direct mode requires it; `false` is for local tooling only, and plain HTTP is refused unless `bindAddress` is `127.0.0.1`. |
| `singleplayerLanAccess` | `false` | restart | Let phones on your network reach the bridge while you play single-player. Off by default, so a single-player world never opens a network port on its own. |
| `rateLimitPerMinute` | `60` | live | Per-token REST budget per rolling minute (`1`–`6000`). On breach: `429` + `retryAfterMs`. |
| `maxClients` | `64` | live | Global cap on concurrent live-update (WebSocket) connections, direct + relay (`1`–`4096`). Extra connections are closed with `1013` (direct) / `4503` (relay) "server busy". |
| `maxDevicesPerPlayer` | `5` | live | How many devices one player may pair at once (`1`–`64`). Pairing past the cap returns `429` "device limit reached"; revoke one with `/nerolink revoke`. |
| `tokenExpiryDays` | `90` | live | Device tokens expire after this many days of inactivity (`1`–`3650`). Expired devices are deleted by a sweep at bridge start and every 6 hours; every token also has an absolute lifetime of 365 days. |
| `readOnly` | `false` | live | Read-only bridge: every `POST /actions/...` is refused with `ACTION_DISABLED`; snapshots are still served. |
| `allowOfflineActions` | `true` | live | When `false`, every action requires the player to be online, ignoring each action's own `allowOffline` flag. |
| `actionsDisabled` | *(empty)* | live | Comma-separated `module/action` ids to disable globally, e.g. `nerologistics/craft_order,core/ack_alert`. |
| `snapshotCadenceHotMs` | `5000` | — | **Reserved** for snapshot caching (not used yet). WS deltas already batch to at most one per second. |
| `snapshotCadenceColdMs` | `30000` | — | **Reserved** for snapshot caching of cold sections (not used yet). |
| `relayOrigin` | `https://nerorelay.neroserver.xyz` | live | Relay base origin used by `/nerolink setup` to register this server. `setup` posts to `<origin>/register`. |
| `relayUrl` | *(empty)* | restart | **Advanced manual override** — relay tunnel URL, e.g. `wss://nerorelay.neroserver.xyz/tunnel/<serverId>`. Blank = use the `/nerolink setup` registration. |
| `relayKey` | *(empty)* | restart | **Advanced manual override** — server key paired with `relayUrl`. **Keep secret; never logged.** Blank = use the `/nerolink setup` registration. |
| `privacyNoticeText` | *(a data-processing notice)* | live | Text returned by `GET /privacy/notice` and shown at first pairing. The default lists everything stored — hashed device token, the device name you enter, pairing and last-connected times, notification preferences — says devices you stop using are deleted automatically, that export/delete is in the app, and that relay traffic passes through without its content being stored. |
| `telemetryEnabled` | `true` | live | Anonymous crash reporting (Sentry, EU ingest). Client-local opt-out — never synced. Set `false` to opt out. See [Privacy](Privacy.md). |

## Credential precedence: config override vs `/nerolink setup`

There are two ways to give the bridge its relay credentials, and they don't both apply at
once:

- **`/nerolink setup` (recommended).** The operator runs the command once; the bridge
  registers with the relay and stores `serverId`, `serverKey`, `tunnelUrl` and `baseUrl`
  **per-world** in saved data. You never edit `relayUrl`/`relayKey` by hand. This activates
  immediately, with no restart. Leave `relayUrl` and `relayKey` **blank** for this path.
- **Manual override (advanced).** If you set **both** `relayUrl` **and** `relayKey` in the
  config, they **take precedence over** the stored `/nerolink setup` registration, and
  activate on the next server start. Use this only if you want to paste credentials
  yourself (for example a local `wrangler dev` relay, where `ws://` is accepted).

In short: **both `relayUrl` and `relayKey` set → the config override wins; otherwise the
`/nerolink setup` registration is used.** `relayOrigin` only feeds `/nerolink setup`; it is
not the tunnel URL.

## Notes

- Values read lazily, so a `/neroland config reload` is picked up per-request for anything
  consulted live (rate limits, read-only, disabled actions, device caps). Port, bind,
  direct and TLS changes bind the socket at server-start, so they need a restart.
- The TLS certificate lives at `config/nerolink/bridge-tls.p12` (owner-only permissions
  where supported) and is reused across worlds and restarts. Delete it to rotate; every
  direct-mode device must then re-pair. A corrupt file is moved aside to
  `bridge-tls.p12.broken` and a new one generated.
- `telemetryEnabled` is deliberately **not** server-authoritative — each install decides
  for itself and the value is never synced to clients.

## See also

- [Relay](Relay.md)
- [Commands](Commands.md)
- [Privacy](Privacy.md)
- [Home](Home.md)
