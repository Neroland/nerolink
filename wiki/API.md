# API

This page is for **client and tool developers**. Everything here is drawn from the bridge
implementation; where the umbrella API spec and the code disagree, **the code is
authoritative**. The current API revision is **`1`** and the bridge version is
`0.0.1-alpha.1`.

Every authenticated response is scoped to the token's player — a paired device sees and
acts on **its own player's data only**. Game state is only ever touched on the server
thread; I/O threads marshal work across via `server.execute(...)`.

## Base URLs

All routes live under a versioned prefix. There are two ways to reach them:

- **Direct / LAN:** `http://<host>:25580/api/v1/...` and WebSocket
  `ws://<host>:25580/ws/v1`. The port is `port` from the config (`25580` by default).
- **Relay:** `https://<relay>/s/<serverId>/api/v1/...` and WebSocket
  `wss://<relay>/s/<serverId>/ws/v1`. The relay forwards frames **verbatim**, so the API
  is byte-for-byte identical; only the base URL differs. Here `<serverId>` is the relay's
  short Server ID (case-insensitive) from `/nerolink setup`. See [Relay](Relay.md).

> **Relay transport note.** Over the relay, REST calls are multiplexed on one tunnel with
> request ids and WebSocket frames pass straight through (wire protocol in the
> `nerolink-relay` repo's `src/protocol.ts`). Two error statuses are produced by the
> **relay itself**, not the bridge: `503 BRIDGE_OFFLINE` when the tunnel is down, and
> `504 BRIDGE_TIMEOUT` if the bridge doesn't answer within 30 s.

## Envelope

Every REST response is JSON with a top-level `ok` flag:

```json
{ "ok": true, "data": { ... } }
```

```json
{ "ok": false, "error": { "code": "RATE_LIMITED", "message": "rate limit exceeded", "retryAfterMs": 1200 } }
```

`retryAfterMs` is present only on `429` responses. The HTTP status code carries the same
signal as the `code` (see [Errors](#errors)).

## Authentication

- **Public (no token):** `POST /api/v1/pair` and `GET /api/v1/privacy/notice`.
- **Everything else** requires `Authorization: Bearer <token>` and is charged against that
  token's rate-limit bucket.

You obtain a token by **pairing**:

1. A player runs `/nerolink pair` in-game and reads their one-time code (`XXXX-XXXX`,
   5-minute TTL, single-use).
2. The client posts it:
   ```
   POST /api/v1/pair
   { "code": "AB12-CD34", "deviceName": "Pixel 8" }
   ```
   On success:
   ```json
   { "ok": true, "data": {
       "token": "<long-lived bearer token>",
       "playerUuid": "…",
       "playerName": "Steve",
       "serverId": "…",
       "serverName": "Neroland SMP"
   } }
   ```
   The plaintext `token` is returned **once** and never stored server-side (only a SHA-256
   hash is kept). Send it as the Bearer token on every subsequent call. An invalid or
   expired code returns `401 UNAUTHORIZED`; exceeding the device cap returns
   `429 RATE_LIMITED` ("device limit reached").

Tokens expire after `tokenExpiryDays` of inactivity (checked lazily). A client can revoke
its own token with `DELETE /api/v1/session`.

## Routes

All paths are relative to `/api/v1`. **A** = requires auth.

| Method | Path | A | Purpose |
| --- | --- | :-: | --- |
| `POST` | `/pair` | — | Redeem a pairing code for a device token. |
| `GET` | `/privacy/notice` | — | The server's data-processing notice. |
| `DELETE` | `/session` | ✓ | Revoke the calling device's token. |
| `GET` | `/discovery` | ✓ | API revision, versions, server identity, capability map. |
| `GET` | `/privacy/export` | ✓ | Everything the bridge holds for you (device metadata + prefs). |
| `POST` | `/privacy/erase` | ✓ | Fire Core's shared erasure for your data across all mods. |
| `GET` | `/prefs/notifications` | ✓ | Your notification category flags. |
| `PUT` | `/prefs/notifications` | ✓ | Replace your notification category flags. |
| `GET` | `/wiki` | ✓ | Aggregate in-app wiki index across every module that ships one. |
| `GET` | `/wiki/{module}` | ✓ | One module's wiki index (page list). |
| `GET` | `/wiki/{module}/{slug}` | ✓ | One wiki page's raw markdown. |
| `GET` | `/{module}/{section}` | ✓ | A module snapshot (player-scoped). |
| `POST` | `/actions/{module}/{action}` | ✓ | Invoke a safe, server-validated action. |

An unknown authed route returns `404 NOT_FOUND`; an unsupported method on
`/prefs/notifications` returns `405 VALIDATION`.

## Discovery

`GET /api/v1/discovery` is how a client builds its UI from exactly what the server
supports:

```json
{ "ok": true, "data": {
  "apiRevision": 1,
  "bridgeVersion": "0.0.1-alpha.1",
  "coreVersion": "2.0.0",
  "server": { "id": "1a2b3c", "name": "Neroland SMP", "online": true, "players": 3 },
  "modules": [
    { "id": "core", "version": "2.0.0", "schema": 1,
      "data": ["gates", "alerts", "energy", "storage", "mods", "wiki"], "actions": ["ack_alert"] },
    { "id": "nerospace", "version": null, "schema": 0, "data": [], "actions": [], "absent": true }
  ]
} }
```

Every module the app knows about is emitted: present modules carry their `version`,
`schema`, `data` sections and `actions`; modules that aren't installed are emitted with
`"absent": true` (and `version: null`, `schema: 0`). The `server.id` here is a stable
per-world hash of the world name — distinct from the relay Server ID used in the base URL.

## Core module sections

The bridge itself provides the built-in **`core`** module (schema `1`), so a Core-only
server is fully functional. Snapshots come from `GET /api/v1/core/{section}`.

### `gates`

The four Core progression gates with per-player unlocked state (falls back to server-scope
openness when the player is offline):

```json
{ "asOf": 1751880000000, "gates": [
  { "id": "industrial_power", "unlocked": true },
  { "id": "reached_orbit", "unlocked": false },
  { "id": "first_colony", "unlocked": false },
  { "id": "deep_space", "unlocked": false }
] }
```

### `alerts`

The player's own active alerts, from Core's alert service:

```json
{ "asOf": 1751880000000, "alerts": [
  { "id": "…", "module": "nerologistics", "severity": "WARNING",
    "text": "Drone bay offline", "at": 1751879000000, "acked": false, "snoozed": false }
] }
```

### `energy` / `storage`

Well-formed but **empty in v1**, each with a `note`. Core exposes no cheap per-player
index of energy/storage blocks, and the bridge never scans loaded chunks. The schema is
additive, so a future Core index lights these up without a client change:

```json
{ "asOf": 1751880000000, "energy": [],
  "note": "Per-player energy index not available in Core v2; chunk scanning is disallowed. …" }
```

### `mods`

A **server-wide** snapshot (not player-scoped — a mods list is public metadata) of the
installed Neroland mods plus the running loader and MC version, so a client can render a
mods overview and drive update checks:

```json
{ "ok": true, "data": {
  "asOf": 1751880000000,
  "loader": "neoforge",
  "mcVersion": "26.2",
  "mods": [
    { "id": "nerolandcore", "name": "Neroland Core", "version": "2.0.0" },
    { "id": "nerolink", "name": "NeroLink", "version": "0.0.1-alpha.1" },
    { "id": "nerologistics", "name": "NeroLogistics", "version": "0.0.1-alpha.1" }
  ]
} }
```

The list is collected once per loader at init and sorted by `id` for stable ordering.

### `wiki`

NeroLink's own wiki (these pages), served under the built-in `core` module with the title
"NeroLink". Follows the [WIKI CONTRACT v1](#wiki-contract-v1-for-mod-authors): no `page`
param returns the index, `page=<slug>` returns the raw markdown. Prefer the dedicated
[`/wiki` routes](#in-app-wiki) — they aggregate this alongside every other mod's wiki.

## Actions

`POST /api/v1/actions/{module}/{action}` invokes a safe action. The bridge re-validates
**server-side** before running anything:

1. **Config gates first** — if `readOnly` is on, or the `module/action` id is in
   `actionsDisabled`, the request is refused with `403 ACTION_DISABLED`.
2. **Module/action presence** — unknown module or action → `404 MODULE_ABSENT`.
3. **Idempotency** — if the body carries a `requestId`, a repeated call replays the cached
   response (dedup window is per player).
4. **Offline gating** — unless the action declares `allowOffline` (and
   `allowOfflineActions` isn't forcing online-only), an offline player gets
   `409 PLAYER_OFFLINE_REQUIRED`.
5. The owning mod executes on the server thread and returns a result mapped to the
   envelope.

### Built-in action: `core/ack_alert`

Acknowledge — and optionally snooze — one of **your own** alerts:

```
POST /api/v1/actions/core/ack_alert
{ "alertId": "…", "snoozeMs": 3600000, "requestId": "optional-idempotency-key" }
```

- `alertId` is **required**; omitting it returns `400 VALIDATION`.
- With `snoozeMs`, the alert is snoozed until now + `snoozeMs`; without it, the alert is
  acknowledged.
- If the alert isn't one of the player's, the result is `403 NOT_OWNER`.
- `ack_alert` is `allowOffline` — it works while the player is offline.

Success:

```json
{ "ok": true, "data": { "alertId": "…", "acked": true } }
```

Other mods register their own actions via Core's link registry; the framework (config
gates, ownership/gate re-validation, idempotency, offline handling, error mapping) is the
same for all of them.

## In-app wiki

The bridge exposes an **in-app, per-mod wiki** so a companion client can browse each
installed mod's documentation while playing. It is **fully mod-agnostic**: any module that
advertises a `wiki` data section (see [discovery](#discovery)) is automatically browsable —
no bridge change per mod. NeroLink's own wiki (these pages) and Core's are served by the
built-in `core` module under the title **"NeroLink"**.

Wiki content is **public** (no personal data), but the routes still run inside the same
authenticated, rate-limited pipeline as every other read.

### Routes

- `GET /api/v1/wiki` — the aggregate index across **every** present module that exposes a
  `wiki` section. `core` and `nerolink` are pinned first (when present), then the rest by
  id. Modules that error or return nothing are skipped:
  ```json
  { "ok": true, "data": {
    "mods": [
      { "mod": "core", "title": "NeroLink",
        "pages": [ { "slug": "Home", "title": "NeroLink Wiki" }, { "slug": "API", "title": "API" } ] },
      { "mod": "nerologistics", "title": "NeroLogistics",
        "pages": [ { "slug": "Home", "title": "Home" } ] }
    ],
    "asOf": 1751880000000
  } }
  ```
- `GET /api/v1/wiki/{module}` — one module's index (page list). `404 MODULE_ABSENT` if the
  module isn't present **or** doesn't expose a `wiki` section:
  ```json
  { "ok": true, "data": {
    "mod": "core", "title": "NeroLink",
    "pages": [ { "slug": "Home", "title": "NeroLink Wiki" }, … ],
    "asOf": 1751880000000
  } }
  ```
- `GET /api/v1/wiki/{module}/{slug}` — one page's raw markdown. `404 NOT_FOUND` for an
  unknown slug; `404 MODULE_ABSENT` if the module has no wiki:
  ```json
  { "ok": true, "data": {
    "mod": "core", "slug": "Home", "title": "NeroLink Wiki",
    "format": "markdown", "content": "# NeroLink Wiki\n\n…",
    "asOf": 1751880000000
  } }
  ```

The client renders `content` as markdown. Page lists and content are safe to cache; `asOf`
lets a client bust its cache.

### WIKI CONTRACT v1 (for mod authors)

A mod opts a wiki into the app with **no NeroLink dependency** — it only depends on Core's
link registry. Two steps:

1. Include `"wiki"` in the module's `LinkModuleInfo.dataSections`.
2. Answer `LinkSnapshotProvider.snapshot(player, "wiki", params)` (the `player` is ignored —
   the content is public):
   - **No `page` param → INDEX:**
     ```json
     { "mod": "<id>", "title": "<Name>",
       "pages": [ { "slug": "Home", "title": "Home" }, … ], "asOf": <millis> }
     ```
   - **`page=<slug>` → PAGE:**
     ```json
     { "mod": "<id>", "slug": "<slug>", "title": "<title>", "format": "markdown",
       "content": "<raw markdown>", "asOf": <millis> }
     ```
   - **Unknown slug →** an object carrying `"error": "unknown page"` (the bridge maps this to
     `404 NOT_FOUND`).

The bridge reuses a small `WikiPages` helper for its own built-in wiki (loads a generated
`index.json` + bundled markdown from the classpath); mods are free to source their pages
however they like as long as they answer in this shape.

## WebSocket protocol

Connect to `GET /ws/v1` (direct) or `.../s/<serverId>/ws/v1` (relay). **The upgrade is
Bearer-authenticated**: send `Authorization: Bearer <token>` on the upgrade request — an
invalid token is rejected with `401 UNAUTHORIZED` before the handshake. One socket is kept
per device (a new socket for the same token replaces the old one).

**Client → server control frames** (JSON text):

```json
{ "op": "sub",   "topics": ["core.alerts", "core.energy", "nerologistics.drones"] }
{ "op": "unsub", "topics": ["core.energy"] }
{ "op": "ping" }
```

A topic is `moduleId.section` — the same sections as the snapshot endpoints.

**Server → client frames:**

- On subscribe, an immediate **consistent-start snapshot** for each newly-subscribed
  topic:
  ```json
  { "topic": "core.alerts", "t": 1751880000000, "snapshot": true, "data": { … } }
  ```
- **Deltas**, coalesced per `(connection, topic)` and flushed **at most once per second**:
  ```json
  { "topic": "core.alerts", "t": 1751880000000, "delta": [ { … }, { … } ] }
  ```
- A reply to your `ping`: `{ "op": "pong" }`, and a server heartbeat every 30 s:
  `{ "op": "ping", "t": 1751880000000 }`. Standard WebSocket ping/pong control frames are
  also honoured.

Player-scoped events are delivered only to that player's sockets; broadcast events go to
everyone. Unknown `op` values are ignored for forward-compatibility.

## Privacy endpoints

- `GET /api/v1/privacy/notice` *(public)* — `{ "notice": "…" }`, the configured
  `privacyNoticeText`.
- `GET /api/v1/privacy/export` — device metadata (`deviceId`, `deviceName`, `createdAt`,
  `lastSeenAt`, `thisDevice`) and your notification prefs. Token hashes are never included.
- `POST /api/v1/privacy/erase` — fires Core's shared `PlayerDataErasure` (fanning out
  across every mod), purges the bridge's own token/prefs/pending-code state, and drops your
  live sockets. Returns `{ "erased": true, "scope": "bridge" }`.
- `GET`/`PUT /api/v1/prefs/notifications` — read/replace your per-category notification
  flags (`{ "notifications": { "nerologistics": true, … } }`). Categories are **opt-in**
  (default off). See [Privacy](Privacy.md).

## Errors

Shared error codes and their HTTP statuses:

| Code | HTTP | Meaning |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | Missing/invalid bearer token, or bad pairing code. |
| `TOKEN_REVOKED` | 401 | Token no longer valid (revoked). |
| `RATE_LIMITED` | 429 | Rate limit or device cap hit; carries `retryAfterMs`. |
| `VALIDATION` | 400 (405 on bad method) | Malformed request / missing field. |
| `NOT_OWNER` | 403 | The target isn't the caller's own data. |
| `GATE_LOCKED` | 403 | A required progression gate isn't unlocked. |
| `ACTION_DISABLED` | 403 | `readOnly` on, or the action is in `actionsDisabled`. |
| `PLAYER_OFFLINE_REQUIRED` | 409 | The action needs the player online. |
| `MODULE_ABSENT` | 404 | Module or action not present. |
| `NOT_FOUND` | 404 | No such route/path. |
| `INTERNAL` | 500 | Unexpected server error. |

Relay-only: `BRIDGE_OFFLINE` (503) and `BRIDGE_TIMEOUT` (504), described above.

## Rate limits

- **Per-token REST budget:** `rateLimitPerMinute` (default `60`) requests per rolling
  minute, token-bucket. On breach: `429 RATE_LIMITED` with `retryAfterMs`.
- **Client cap:** `maxClients` (default `64`) — enforced at pairing as a per-player device
  limit; a new pairing past the cap returns `429 RATE_LIMITED` ("device limit reached").

## See also

- [Relay](Relay.md)
- [Configuration](Configuration.md)
- [Privacy](Privacy.md)
- [Home](Home.md)
