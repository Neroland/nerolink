# API

This page is for **client and tool developers**. Everything here is drawn from the bridge
implementation, which is **authoritative** wherever any other API description disagrees with
it. The current API revision is **`1`**; this page describes the bridge from **1.0.0**
(discovery reports the exact installed `bridgeVersion`).

Every authenticated response is scoped to the token's player — a paired device sees and
acts on **its own player's data only**. Game state is only ever touched on the server
thread; I/O threads marshal work across via `server.execute(...)`.

## Base URLs

All routes live under a versioned prefix. There are two ways to reach them:

- **Direct / LAN:** `https://<host>:25580/api/v1/...` and WebSocket
  `wss://<host>:25580/ws/v1`. The port is `port` from the config (`25580` by default).
  The bridge serves TLS with its own self-signed certificate (see
  [Direct-mode TLS](#direct-mode-tls)).
- **Relay:** `https://<relay>/s/<serverId>/api/v1/...` and WebSocket
  `wss://<relay>/s/<serverId>/ws/v1`. The relay forwards frames **verbatim**, so the API
  is byte-for-byte identical; only the base URL differs. Here `<serverId>` is the relay's
  short Server ID (case-insensitive) from `/nerolink setup`. See [Relay](Relay.md).

> **Security note.** The direct listener is TLS-only by default. Its certificate is
> self-signed, so clients must **pin** it rather than rely on a CA — see
> [Direct-mode TLS](#direct-mode-tls). The relay is **not end-to-end encrypted**: TLS
> terminates at the relay, which reads traffic in memory to forward it (never storing
> it). The bridge also accepts `ws://`/`http://` relay URLs, but that is only for a local
> `wrangler dev` relay — never for a remote one.

> **Relay transport note.** Over the relay, REST calls are multiplexed on one tunnel with
> request ids and WebSocket frames pass straight through (wire protocol in the
> `nerolink-relay` repo's `src/protocol.ts`). Two error statuses are produced by the
> **relay itself**, not the bridge: `503 BRIDGE_OFFLINE` when the tunnel is down, and
> `504 BRIDGE_TIMEOUT` if the bridge doesn't answer within 30 s. Tunnel `req` frames may
> carry an `ip` field — a salted hash used for pairing rate limits, never an IP — and the
> bridge sends `push_unbind {playerUuid, deviceId}` when a device is revoked or expires.

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
   5-minute TTL, single-use). When direct mode is reachable from the network the whisper
   also shows the **Direct address** and a **Security code**.
2. The client posts it. **Over the relay** it sends the plain code (protected by TLS to
   the relay's public certificate):
   ```
   POST /api/v1/pair
   { "code": "AB12-CD34", "deviceName": "Pixel 8" }
   ```
   **On a direct connection** it sends a certificate-bound proof instead of the code:
   ```
   POST /api/v1/pair
   { "codeProof": "<64 hex chars>", "deviceName": "Pixel 8" }
   ```
   A plain `code` on the direct TLS listener is rejected with `400 VALIDATION`
   ("codeProof required on direct connections (update the NeroLink app)").
3. On success:
   ```json
   { "ok": true, "data": {
       "token": "<long-lived bearer token>",
       "deviceId": "…",
       "playerUuid": "…",
       "playerName": "Steve",
       "serverId": "…",
       "serverName": "Neroland SMP"
   } }
   ```
   The plaintext `token` is returned **once** and never stored server-side (only a SHA-256
   hash is kept). Send it as the Bearer token on every subsequent call. An invalid or
   expired code returns `401 UNAUTHORIZED`; pairing past `maxDevicesPerPlayer` (default
   `5`) returns `409 VALIDATION` ("device limit reached; revoke one with /nerolink devices and /nerolink revoke"); a pairing lockout returns
   `429 RATE_LIMITED` with `retryAfterMs: 60000` (see [Rate limits](#rate-limits)).

Tokens expire after `tokenExpiryDays` (default `90`) of inactivity and after an absolute
lifetime of 365 days; expired devices are deleted by a sweep at bridge start and every
6 hours. A client can revoke its own token with `DELETE /api/v1/session`, which also closes
its live WebSocket and unbinds its relay push registration.

### Direct-mode TLS

On first start the bridge generates a long-lived self-signed **ECDSA P-256** certificate
and stores it at `config/nerolink/bridge-tls.p12`; it is reused across worlds and restarts.
Deleting the file rotates it, after which every direct-mode device must re-pair. The client:

1. Connects over TLS and computes the certificate's **SHA-256 fingerprint** (lowercase hex).
2. Shows the **Security code** — the first 16 hex digits of the fingerprint, formatted
   `XXXX-XXXX-XXXX-XXXX` — so the player can check it matches the one in chat.
3. Sends `codeProof = hex(PBKDF2-HMAC-SHA256(password, salt, 100000 iterations, 32 bytes))`
   where `password` is the pairing code uppercased with dashes removed (UTF-8) and `salt` is
   `"nerolink-pair-v1:"` followed by the lowercase hex fingerprint.
4. Pins the fingerprint and refuses any other certificate on later connections.

Because the proof is bound to the certificate the client actually saw, a code relayed
through an intercepting certificate does not redeem, and PBKDF2 makes cracking a captured
proof offline take months rather than the code's 5-minute life. The bridge derives each
code's expected proof once, in the background, when the code is issued. Test vector (shared with the app's
tests): code `abcd-efgh`, fingerprint
`ad88909ec2ab31ab99c7980d12d90077c64b434dd55502757adc67155b5a0212` →
`codeProof` `75dab7f42db2e321281d777d8a5f9a12e734ffade783762763531f3628b1a6c1`.

`tlsEnabled=false` serves plain HTTP, and only when `bindAddress` is `127.0.0.1` — for local
tooling only; the NeroLink app's direct mode always requires TLS.

## Routes

All paths are relative to `/api/v1`. **A** = requires auth.

| Method | Path | A | Purpose |
| --- | --- | :-: | --- |
| `POST` | `/pair` | — | Redeem a pairing code for a device token. |
| `GET` | `/privacy/notice` | — | The server's data-processing notice. |
| `GET` | `/session` | ✓ | Who the calling token belongs to: `{playerUuid, deviceId, deviceName}`. |
| `DELETE` | `/session` | ✓ | Revoke the calling device's token. |
| `GET` | `/discovery` | ✓ | API revision, versions, server identity, capability map. |
| `GET` | `/privacy/export` | ✓ | Everything the bridge holds for you (device metadata + prefs). |
| `POST` | `/privacy/erase` | ✓ | Erase your NeroLink data (bridge-scoped). |
| `GET` | `/prefs/notifications` | ✓ | Your notification category flags. |
| `PUT` | `/prefs/notifications` | ✓ | Replace your notification category flags. |
| `GET` | `/wiki` | ✓ | Aggregate in-app wiki index across every module that ships one. |
| `GET` | `/wiki/{module}` | ✓ | One module's wiki index (page list). |
| `GET` | `/wiki/{module}/{slug}` | ✓ | One wiki page's raw markdown. |
| `GET` | `/{module}/{section}` | ✓ | A module snapshot (player-scoped). |
| `POST` | `/actions/{module}/{action}` | ✓ | Invoke a safe, server-validated action. |

An unknown authed route returns `404 NOT_FOUND`; an unsupported method on
`/prefs/notifications` returns `405 VALIDATION`. Every response carries
`Cache-Control: no-store`.

`GET /api/v1/session` returns:

```json
{ "ok": true, "data": { "playerUuid": "…", "deviceId": "…", "deviceName": "Pixel 8" } }
```

The relay calls it with the device's own token to bind push registrations to the real
player.

## Discovery

`GET /api/v1/discovery` is how a client builds its UI from exactly what the server
supports:

```json
{ "ok": true, "data": {
  "apiRevision": 1,
  "bridgeVersion": "1.0.0",
  "coreVersion": "1.14.0",
  "server": { "id": "1a2b3c", "name": "Neroland SMP", "online": true, "players": 3 },
  "modules": [
    { "id": "core", "version": "1.14.0", "schema": 1,
      "data": ["gates", "alerts", "energy", "storage", "mods", "wiki"], "actions": ["ack_alert"] },
    { "id": "nerospace", "version": null, "schema": 0, "data": [], "actions": [], "absent": true }
  ]
} }
```

Every module the app knows about is emitted: present modules carry their `version`,
`schema`, `data` sections and `actions`; modules that aren't installed are emitted with
`"absent": true` (and `version: null`, `schema: 0`). The `server.id` here is a stable
per-world hash of the world name — distinct from the relay Server ID used in the base URL.
`bridgeVersion` and `coreVersion` are read from the loader's metadata for the installed
jars.

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
    { "id": "nerolandcore", "name": "Neroland Core", "version": "1.14.0" },
    { "id": "nerolink", "name": "NeroLink", "version": "1.0.0" },
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

## Other module sections

Every other module's sections, actions and event topics are defined by the mod that
registers it, not by the bridge. The dispatcher forwards any
`GET /api/v1/{module}/{section}`, `POST /api/v1/actions/{module}/{action}` and
`module.topic` subscription to that module and keeps no list of its own, so a mod can add
sections in a new schema version without a bridge release. [Discovery](#discovery) is the
source of truth for what an installed module offers, and each mod documents its payloads in
its own wiki.

One thing is true of every module's JSON because the bridge serialises it: a member whose
value is null is left out. Clients must treat a missing key and `null` alike.

As an example of a multi-section module, **`nerocolonies`** (schema `2`) advertises
`colonies`, `colonists`, `jobs`, `research` and `exports` (unchanged from schema `1`), plus
`summary`, `needs`, `buildings`, `professions`, `roles` and `cache`, and the actions
`toggle_export`, `acknowledge_alert`, `prioritise_need` and `toggle_cache_sharing`. Its
`summary` section is the one a generic client can render without knowing the module: it
carries a `headline` and a list of `sections` with labelled items.

The other large module, **`neroeconomy`**, is summarised in the next section.

## NeroEconomy module (schema 2)

**`neroeconomy`** is the largest third-party module. Its payloads are defined by NeroEconomy
(its own wiki is the reference for game rules); this section is the wire summary a client
needs. Every section and action is scoped to the token's player, and a reply never names
another player: where one is involved (an offer's sender, a bounty's target) it shows up as
`"A player"`.
Amounts are whole credits unless a key ends in `Cents` (hundredths of a credit) or `Bps`
(basis points). Times are epoch milliseconds (`asOf`, `at`) or a remaining duration in
milliseconds (`expiresIn`, `endsIn`, `maturesIn`, `sellableIn`).

Discovery entry (no `wiki` section; topics are not listed in discovery):

```json
{ "id": "neroeconomy", "version": "…", "schema": 2,
  "data": ["balance", "transactions", "market", "summary", "orders", "vault", "stocks",
           "portfolio", "offers", "jobs", "auctions"],
  "actions": ["trade", "order_place", "order_cancel", "rule_set", "rule_delete", "rules_pause",
              "stock_buy", "stock_sell", "bond_buy", "pay", "offer_send", "offer_accept",
              "offer_decline", "claim_collect", "vault_deposit", "vault_withdraw", "offer_cancel"] }
```

### NeroEconomy sections

`GET /api/v1/neroeconomy/{section}`. `balance`, `transactions` and `market` are unchanged
from schema `1`; the rest are new in schema `2`.

| Section | Query params | What it holds |
| --- | --- | --- |
| `balance` | — | `balance`, `currency`, `symbol`, `history` and `recentTrend` (your last few ledger rows: `at`, `amount`, `reason`). |
| `transactions` | `cursor` (0+), `limit` (1–50, default 25) | One page of your own ledger rows. |
| `market` | `q` (item search, 64 chars max; spaces match `_`), `page`; or `view=book` with `item` | One page of sell listings and buy offers; with `view=book`, one item's order book as price levels (see below). |
| `summary` | — | One-glance counts: balance, waiting credits, vault fill, orders, rules, offers, portfolio totals, `remote`, `marketOpen`, `frozen`. |
| `orders` | — | Your `sell` and `buy` orders, `shops` listings and vault-backed `rules`, with rule caps. |
| `vault` | — | Your vault lines (`index`, `item`, `qty`, `payload`), `used`, `cap`, `full`, `inGame`, `liveActions`. |
| `stocks` | — | The exchange: `exchangeOpen`, `feeBps`, `holdMinutes`, each stock's prices and price history, bond terms (`days`, `rateBps`). |
| `portfolio` | — | Your holdings, bonds and limits; the same again under `practice` for play credits. |
| `offers` | — | Your `inbox` and `outbox` of direct trade offers, `muted`, `maxOffers`. |
| `jobs` | `view`: `open` (default), `contracts`, `deliveries`, `bounties`, `mine` | Contracts, deliveries and bounties. |
| `auctions` | `view`: `all` (default) or `mine` | Running auctions with `highestBid`, `nextBid`, `leading`. |

An unknown section answers `200` with an empty object. Each read also takes one token from
the player's in-game query budget (a burst of 10, then 5 a second); over it, the last
snapshot served for the same query comes back with `"throttled": true` (or an empty section
of the right shape if there is none). Lists are capped (50 rows; 128 vault lines; 64
stocks), so the largest section stays far below the transport limits.

`summary` (abridged):

```json
{ "ok": true, "data": {
  "balance": 1380, "symbol": "cr", "currency": "nerolandcore:credits", "waitingCredits": 64,
  "vault": { "stacks": 12, "cap": 54, "full": false },
  "orders": { "sell": 2, "buy": 1 }, "rules": { "count": 3, "max": 8 },
  "offers": { "incoming": 1, "outgoing": 2 },
  "portfolio": { "value": 2150, "cost": 2000, "difference": 150, "bonds": 1 },
  "remote": { "enabled": true, "spentToday": 1200, "dailyLimit": 5000, "leftToday": 3800 },
  "marketOpen": true, "frozen": false, "asOf": 1791000000000
} }
```

`orders` (one row of each list):

```json
{ "ok": true, "data": {
  "sell": [ { "orderId": "…", "item": "minecraft:oak_log", "displayName": "Oak Log",
              "qty": 64, "modified": false, "unitPrice": 6, "expiresIn": 82800000 } ],
  "buy": [ { "orderId": "…", "item": "minecraft:iron_ingot", "displayName": "Iron Ingot",
             "qty": 32, "modified": false, "unitPrice": 5, "escrow": 160, "expiresIn": 43200000 } ],
  "shops": [ { "listingId": "…", "item": "minecraft:bread", "displayName": "Bread",
               "qty": 40, "modified": false, "unitPrice": 3 } ],
  "rules": [ { "ruleId": "…", "side": "buy", "target": "minecraft:wheat", "tag": false,
               "displayName": "Wheat", "limitPrice": 4, "perDay": 64, "filledToday": 12,
               "total": 1000, "done": 48, "budget": 952, "paused": false, "vault": true,
               "status": "running", "statusText": "Running." } ],
  "rulesMax": 8, "ruleValueToday": 300, "ruleValueCap": 10000, "rulesPausedAll": false,
  "asOf": 1791000000000
} }
```

`offers` (an inbox row; the sender is never named):

```json
{ "offerId": "…", "from": "A player",
  "goods": [ { "item": "minecraft:emerald", "displayName": "Emerald", "qty": 8, "modified": false } ],
  "credits": 0,
  "ask": { "item": "minecraft:wheat", "displayName": "Wheat", "qty": 64, "modified": false },
  "askCredits": 50, "expiresIn": 86400000 }
```

`market` with `view=book&item=minecraft:wheat` (added in schema `2`; an app that never sends
`view` gets the listing page as before): the open buy orders (`bids`, highest first) and sell
orders (`asks`, cheapest first) for one item, folded into price levels, at most `depth` (10)
levels a side. The same order book the in-game Economy Hub reads its best bid and ask from, so
the app and the game agree. Counts only: no order id, owner or account. `bestBid` / `bestAsk`
are `0` when that side is empty. `item` is an item id (no namespace means `minecraft:`); one
that is not an item id gives an empty book with `"item": ""`.

```json
{ "ok": true, "data": {
  "view": "book", "item": "minecraft:wheat", "displayName": "Wheat",
  "bids": [ { "unitPrice": 5, "qty": 96, "orders": 2 }, { "unitPrice": 4, "qty": 128, "orders": 1 } ],
  "asks": [ { "unitPrice": 6, "qty": 40, "orders": 1 }, { "unitPrice": 7, "qty": 200, "orders": 1 } ],
  "bestBid": 5, "bestAsk": 6, "depth": 10, "asOf": 1791000000000
} }
```

`stocks` also carries the exchange's trading terms at the top level: `feeBps`, the fee on every
share and index purchase and sale in basis points (NeroEconomy's `exchangeFeePercent` × 100;
rounded up, at least 1 credit; bonds carry no fee), and `holdMinutes`, the economy minutes before
units just bought can be sold (`shareHoldMinutes`; `0` means none). Each bond term carries its
`days` and its whole-term interest `rateBps`.

`vault` also carries `inGame` (the player is in the game now) and `liveActions` (`vault_deposit`
and `vault_withdraw` can run now: remote actions on, economy not frozen, the player in game,
alive and not spectating). Both are plain booleans; nothing about where the player is.

### NeroEconomy live topics

Subscribe with `neroeconomy.<topic>`:

| Topic | Delta payload |
| --- | --- |
| `neroeconomy.balance` | The full `balance` section. |
| `neroeconomy.orders` | The full `orders` section. |
| `neroeconomy.offers` | The full `offers` section. |
| `neroeconomy.portfolio` | The full `portfolio` section. |
| `neroeconomy.alerts` | One alert: `{ "alertId", "kind", "text", "asOf" }`. |

Events are player-scoped, published at most once a second per player and topic, and only
while the player's app has made a request in the last two minutes, so keep a cheap read
(such as `summary`) going while the app is open. Each `delta` item of the first four topics
is a complete section, so a client keeps the last item of the batch. `alerts` is a topic
only, not a section: its subscribe snapshot is `{}`. Alert `kind`s: `order_filled`,
`contract_fulfilled`, `outbid`, `auction_won`, `shop_sold_out`, `offer_received`,
`offer_accepted`, `offer_expired`, `rule_paused`, `rule_filled`, `bond_matured`,
`dividend_paid`, `stock_delisted`, `vault_full`; `alertId` is `neroeconomy:<kind>`.

```json
{ "topic": "neroeconomy.alerts", "t": 1791000000000, "delta": [
  { "alertId": "neroeconomy:order_filled", "kind": "order_filled",
    "text": "One of your market orders was filled.", "asOf": 1791000000000 } ] }
```

Push notifications for these topics use the category **`neroeconomy`** (one category for
all five topics); they carry no amounts or item names.

### NeroEconomy actions

`POST /api/v1/actions/neroeconomy/{action}`. Every action **requires** a `requestId` (a
UUID; a body without one is refused with `400 VALIDATION`), and NeroEconomy records it
too, so a replay that gets past the bridge's cache acts only once.

| Action | Body (besides `requestId`) | Offline | Spends |
| --- | --- | :-: | :-: |
| `trade` | `op` `buy`: `listingId`, `qty`, `quotedUnitPrice`. `op` `sell`: `qty`, `unitPrice` to list the main hand (in game only), or `listingId` to fill a buy offer. | ✓ | buy |
| `order_place` | `side` (`buy`/`sell`), `item`, `qty`, `unitPrice`; a sell also takes `payload` (default `0`). Goods come from and go to the vault. | ✓ | ✓ |
| `order_cancel` | `orderId` | ✓ | — |
| `rule_set` | `side`, `target` (item id or `#tag`), `limitPrice`, `perDay`, `total` | ✓ | ✓ |
| `rule_delete` | `ruleId` | ✓ | — |
| `rules_pause` | `paused` (default `true`); `ruleId` for one rule, none for all | ✓ | — |
| `stock_buy` | `stockId`; `credits`, or `units` with `maxSpend`; `practice` | ✓ | ✓ |
| `stock_sell` | `stockId`; `units` (absent or `0`: all that may be sold); `acceptedGain`; `practice`; `structuredRefusal` (see below) | ✓ | — |
| `bond_buy` | `term` (`1d`, `3d`, `7d`), `principal`, `practice` | ✓ | ✓ |
| `pay` | `recipient` (a name or UUID), `amount` | ✓ | ✓ |
| `offer_send` | `recipient`; `goods` (up to 9 `{item, qty, payload}` lines from the vault); `credits`; `askItem`, `askQty`, `askCredits` | ✓ | ✓ |
| `offer_accept` | `offerId` | ✓ | ✓ |
| `offer_decline` | `offerId` | ✓ | — |
| `offer_cancel` | `offerId` (an offer you sent) | ✓ | — |
| `claim_collect` | — | ✓ | — |
| `vault_deposit` | `qty` (default: the whole main-hand stack) | — | — |
| `vault_withdraw` | none (as much as fits), or `index`, `item`, `qty` for one vault line | — | — |

**Offline** is the action's `allowOffline`: only the two vault moves need the player in
game (`409 PLAYER_OFFLINE_REQUIRED`). When `allowOfflineActions` is `false` in the bridge
config, every action needs the player online.

A success is the `balance` object plus `action`, a plain-language `message`, the action's
own result keys and, for an action that spends, the `remote` block:

```http
POST /api/v1/actions/neroeconomy/order_place
{ "requestId": "0f8e…", "side": "buy", "item": "minecraft:wheat", "qty": 64, "unitPrice": 4 }
```

```json
{ "ok": true, "data": {
  "currency": "nerolandcore:credits", "balance": 1380, "symbol": "cr", "asOf": 1791000000000,
  "action": "order_place", "message": "Buy order placed.",
  "orderId": "…", "side": "buy", "item": "minecraft:wheat", "qty": 64, "unitPrice": 4,
  "filledNow": 16, "resting": 48, "listingFee": 1, "escrowed": 256, "delivery": "vault",
  "remote": { "enabled": true, "spentToday": 1200, "dailyLimit": 5000, "leftToday": 3800 }
} }
```

```json
{ "ok": true, "data": { "…": "balance keys", "action": "pay", "message": "Payment sent.",
  "paid": 150, "remote": { "enabled": true, "spentToday": 1200, "dailyLimit": 5000, "leftToday": 3800 } } }
```

`pay` never echoes the recipient; the result key is `paid` so it never shadows the balance
keys.

`offer_cancel` takes back an offer you sent, through the same path as cancelling it in game:
its items go back to your vault and its credits to your balance (or, if the ledger cannot pay
them out just now, to your waiting credits, `refundedToClaim: true`). It is a refund, so it
works with the market closed, while you are away, and spends nothing; the other side is not
told. Result keys: `offerId`, `refunded` (credits returned), `items`, `refundedToClaim`. An
offer that is not yours, or already gone, is refused (`400 VALIDATION`).

**Reduced gain on `stock_sell`.** When the Reserve can pay only part of a sale's gain, the
sale is refused until the player accepts the smaller gain. By default this is the usual
`400 VALIDATION` with a sentence. Send `"structuredRefusal": true` to get it as a result that
sold nothing instead, message kept:

```json
{ "ok": true, "data": { "…": "balance keys", "action": "stock_sell",
  "message": "The exchange can pay only 40 of your 100 gain today. …",
  "sold": false, "refusal": "gain_reduced", "stockId": "neroeconomy:redstone_works", "units": 10,
  "gain": 100, "payable": 40, "gainForfeited": 60, "acceptParam": "acceptedGain", "acceptValue": 40 } }
```

`refusal` is `gain_reduced` (no acceptance sent) or `gain_changed` (the `acceptedGain` sent no
longer matches what can be paid; the new figures are in the same keys). To sell anyway, send
the same sale again with `acceptParam` set to `acceptValue` and a **new** `requestId`. A sale
that went through carries `"sold": true`.

### NeroEconomy remote spend block

`remote` is what the player may still spend through the app today, against the server's
`remoteDailySpendLimit` (NeroEconomy config, default `5000` credits per economy-day):

```json
{ "enabled": true, "spentToday": 1200, "dailyLimit": 5000, "leftToday": 3800 }
```

`enabled` mirrors NeroEconomy's `remoteTradeEnabled`. It appears in `summary` and in the
result of every action marked **Spends** above. An action states the most it could spend
before it runs and is refused (`400 VALIDATION`, with the limit and what is left in the
message) when that would pass the limit; afterwards only what it really spent is counted.
Purchases, buy-order escrow, rule budgets, shares, bonds, payments and the credits of an
offer all count.

### NeroEconomy errors

NeroEconomy uses the shared [error codes](#errors); `message` is always a plain sentence
for the player.

| Code | HTTP | When |
| --- | --- | --- |
| `ACTION_DISABLED` | 403 | `remoteTradeEnabled` is off, the economy is frozen, or (for trading actions) the operator closed the market. |
| `PLAYER_OFFLINE_REQUIRED` | 409 | `vault_deposit` / `vault_withdraw` while the player is away. |
| `VALIDATION` | 400 | Unknown action, missing `requestId`, a request id already used, over the in-game action budget (a burst of 6, then 2 a second) or one action's own limit (6, then one every 5 s), a bad parameter, over the daily remote limit, or a rule of the game (funds, stock, ownership, and so on). |
| `INTERNAL` | 500 | An unexpected failure. Read the affected section before retrying. |

A refusal moved nothing, so retry it with a **new** `requestId`: the bridge replays the
cached response for a repeated id for 10 minutes, refusals included. Reuse the same id only
when no response arrived (a dropped connection, `503`, `504`).

## Actions

`POST /api/v1/actions/{module}/{action}` invokes a safe action. The bridge re-validates
**server-side** before running anything:

1. **Config gates first** — if `readOnly` is on, or the `module/action` id is in
   `actionsDisabled`, the request is refused with `403 ACTION_DISABLED`.
2. **Module/action presence** — unknown module or action → `404 MODULE_ABSENT`.
3. **Idempotency** — if the body carries a `requestId`, a repeated call replays the cached
   response (dedup window is per player, 10 minutes; error responses are cached too, so retry
   a refusal with a new `requestId`).
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
per device (a new socket for the same token replaces the old one; the old
socket finishing its close no longer drops the new one's subscriptions). `maxClients` (default `64`) caps live connections across direct and relay
together; past the cap a new socket is closed with `1013` (direct) or `4503` (relay)
"server busy". Revoking or expiring the device closes its socket immediately.

**Client → server control frames** (JSON text):

```json
{ "op": "sub",   "topics": ["core.alerts", "core.energy", "nerologistics.drones"] }
{ "op": "unsub", "topics": ["core.energy"] }
{ "op": "ping" }
```

A topic is `moduleId.section` — the same sections as the snapshot endpoints. Topic ids
that don't look like `module.section` are rejected, and a connection may hold at most
**32** subscriptions. Control frames (including `ping`) are charged to the device's
rate limit.

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
- `POST /api/v1/privacy/erase` — the app's "Delete my NeroLink data". **Bridge-scoped:**
  erases NeroLink's own data (device tokens, prefs, pending pairing code, live sockets,
  relay push registrations) and does **not** fan out to other mods, so a bearer token on a
  phone cannot wipe game progress. Returns
  `{ "erased": true, "scope": "bridge", "note": "…" }`, where the note points at
  `/neroland data eraseme` (in-game), which runs Core's `PlayerDataErasure` across every
  Neroland mod, NeroLink included.
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
| `VALIDATION` | 400 (405 on bad method) | Malformed request / missing field, or a plain `code` on a direct connection. |
| `NOT_OWNER` | 403 | The target isn't the caller's own data. |
| `GATE_LOCKED` | 403 | A required progression gate isn't unlocked. |
| `ACTION_DISABLED` | 403 | `readOnly` on, or the action is in `actionsDisabled`. |
| `PLAYER_OFFLINE_REQUIRED` | 409 | The action needs the player online. |
| `MODULE_ABSENT` | 404 | Module or action not present. |
| `NOT_FOUND` | 404 | No such route/path. |
| `INTERNAL` | 500 (503 on timeout) | Unexpected server error; `503` "server busy; try again" when the server thread doesn't take the request within 10 s. |

Relay-only: `BRIDGE_OFFLINE` (503) and `BRIDGE_TIMEOUT` (504), described above.

## Rate limits

- **Per-token REST budget:** `rateLimitPerMinute` (default `60`) requests per rolling
  minute, token-bucket. On breach: `429 RATE_LIMITED` with `retryAfterMs`. WebSocket
  control frames count against the same budget.
- **Devices per player:** `maxDevicesPerPlayer` (default `5`); a new pairing past the cap
  returns `409 VALIDATION` ("device limit reached; revoke one with /nerolink devices and /nerolink revoke").
- **Live connections:** `maxClients` (default `64`) concurrent WebSockets, direct + relay.
- **Pairing brute force:** failed redemptions are budgeted at **5 per source per minute**
  (the client IP on direct connections; on relay connections a salted, daily-rotating hash
  of the client IP forwarded by the relay), with a **120 per minute global** backstop.
  Attempts are refused with `429 RATE_LIMITED` (`retryAfterMs: 60000`) only while a budget
  is exceeded; pending codes are never voided.
- **Transport limits (direct):** request bodies are capped at 16 KiB; 16 concurrent TCP
  connections per address; connections idle for 90 s are closed; JSON nested deeper than
  32 levels is rejected before parsing.

## See also

- [Relay](Relay.md)
- [Configuration](Configuration.md)
- [Privacy](Privacy.md)
- [Home](Home.md)
