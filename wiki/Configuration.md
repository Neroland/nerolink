# Configuration

NeroLink registers its config with Neroland Core's config system, so it lives in
`config/nerolink.properties` and reloads with `/neroland config reload`. Most levers are
consulted **per request**, so a reload takes effect **live** without a bridge restart. The
exceptions are anything bound at server-start — the socket **`port`** and **`bindAddress`**,
and the master **`enabled`** switch — which need a **server restart**.

## Every key

| Key | Default | When it applies | What it does |
| --- | --- | --- | --- |
| `enabled` | `true` | restart | Master switch. When `false` the bridge binds no socket and serves nothing. |
| `port` | `25580` | restart | TCP port the HTTP + WebSocket bridge binds (range `1024`–`65535`). |
| `bindAddress` | `0.0.0.0` | restart | Interface to bind. `0.0.0.0` = all interfaces; `127.0.0.1` = local / relay-forward only. |
| `rateLimitPerMinute` | `60` | live | Per-token REST budget per rolling minute (`1`–`6000`). On breach: `429` + `retryAfterMs`. |
| `maxClients` | `64` | live | Concurrent-client cap (`1`–`4096`). Enforced at pairing as a per-player device limit — a new pairing past the cap returns `429` "device limit reached". |
| `tokenExpiryDays` | `90` | live | Device tokens expire after this many days of inactivity (`1`–`3650`), checked lazily on next use. |
| `readOnly` | `false` | live | Read-only bridge: every `POST /actions/...` is refused with `ACTION_DISABLED`; snapshots are still served. |
| `allowOfflineActions` | `true` | live | When `false`, every action requires the player to be online, ignoring each action's own `allowOffline` flag. |
| `actionsDisabled` | *(empty)* | live | Comma-separated `module/action` ids to disable globally, e.g. `nerologistics/craft_order,core/ack_alert`. |
| `snapshotCadenceHotMs` | `5000` | live | Cache cadence for hot sections (energy, drones), `500`–`600000` ms. WS deltas batch to at most one per second. |
| `snapshotCadenceColdMs` | `30000` | live | Cache cadence for cold sections (stock, storage), `500`–`600000` ms. |
| `relayOrigin` | `https://nerorelay.neroserver.xyz` | live | Relay base origin used by `/nerolink setup` to register this server. `setup` posts to `<origin>/register`. |
| `relayUrl` | *(empty)* | restart | **Advanced manual override** — relay tunnel URL, e.g. `wss://nerorelay.neroserver.xyz/tunnel/<serverId>`. Blank = use the `/nerolink setup` registration. |
| `relayKey` | *(empty)* | restart | **Advanced manual override** — server key paired with `relayUrl`. **Keep secret; never logged.** Blank = use the `/nerolink setup` registration. |
| `privacyNoticeText` | *(a data-processing notice)* | live | Text returned by `GET /privacy/notice` and shown at first pairing. |
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
  consulted live (rate limits, read-only, disabled actions, cadences). Port and bind
  changes bind the socket at server-start, so they need a restart.
- `telemetryEnabled` is deliberately **not** server-authoritative — each install decides
  for itself and the value is never synced to clients.

## See also

- [Relay](Relay.md)
- [Commands](Commands.md)
- [Privacy](Privacy.md)
- [Home](Home.md)
