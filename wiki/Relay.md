# Relay

The relay lets companion clients reach a NeroLink bridge from **anywhere** — a home or
NAT'd server with **no port forwarding and no public IP**. It is entirely optional: the
local HTTP/WS listener and the relay tunnel are independent, so you can run either, both,
or neither.

## How it works

The bridge dials **out** and holds **one** persistent WebSocket tunnel to the relay
(`wss://<relay>/tunnel/<serverId>`, authenticated with a secret `serverKey`). Phones
connect to the relay instead of to your server; a per-server component on the relay
marries the two and forwards traffic **verbatim**:

- REST calls are multiplexed over the single tunnel with request ids.
- WebSocket topic frames pass straight through.
- The API surface is **identical** to talking to the bridge directly — clients just use a
  different base URL (see [API](API.md)).

Because the connection is outbound, no inbound firewall rule or port forward is needed. If
the tunnel drops, the bridge reconnects automatically with backoff.

## Setting it up: `/nerolink setup`

An operator runs it **once**:

```
/nerolink setup
```

The bridge:

1. Registers with the relay (`POST <origin>/register`) off the server thread, sending only
   the world/level name as a display name.
2. Receives `serverId`, a once-issued secret `serverKey`, a `tunnelUrl` and a `baseUrl`.
3. Persists them **per-world** in saved data (never a file you edit by hand).
4. Dials the tunnel immediately — **no server restart**.

The operator sees a bold **Server ID**, the **App URL**, and a
`Tunnel connecting — check /nerolink status` hint. The `serverKey` is never printed in chat
and never logged; only the relay **host** appears in lifecycle logs.

- Default relay origin: `https://nerorelay.neroserver.xyz`. Point `relayOrigin` (config) at
  your own relay, or pass one: `/nerolink setup <https-origin>`.
- Running plain `/nerolink setup` again when already registered just re-dials the existing
  tunnel.
- `/nerolink setup force [origin]` discards the stored credentials and registers afresh for
  a new Server ID.

Advanced operators can skip the command and set **both** `relayUrl` and `relayKey` in the
config instead — see [Configuration](Configuration.md) for the precedence rules.

## Server IDs

The relay assigns each registered server a **short, human-friendly Server ID**. Players
type this ID plus their one-time pairing code into the app — that's all a relay client
needs, with **no address to type**. Server IDs are matched **case-insensitively**, so
players don't have to worry about capitalisation.

`/nerolink pair` shows the active Server ID prominently (above the LAN address), and
`/nerolink status` reports it alongside the relay state.

> The relay's Server ID (used in the app URL `https://<relay>/s/<serverId>`) is distinct
> from the per-world `serverId` the bridge returns in its own `discovery`/`pair` responses,
> which is a hash of the world name. Clients use the relay Server ID for addressing and the
> discovery `serverId` for identity. See [API](API.md).

## `REGISTRATION_OPEN`

A relay operator can freeze new registrations by setting the relay's `REGISTRATION_OPEN`
var to `false` — recommended for a personal relay once your server is set up. If
registration is closed, `/nerolink setup` reports *"Registration is closed on this relay
(`REGISTRATION_OPEN=false`)"* and changes nothing until the operator reopens it.

## Self-hosting the relay

The relay is a small, open-source **Cloudflare Worker + Durable Object**, published as the
**`nerolink-relay`** repository. Highlights:

- **Zero provisioning** — no KV, no D1, no queues; all state lives in SQLite-backed
  Durable Objects, which work on Cloudflare's free plan.
- Deploy by importing the repo in the Cloudflare dashboard (**Workers & Pages → Create →
  Import a repository**) so every push to `main` auto-deploys, or with
  `npx wrangler deploy`.
- Your relay lives at `https://nerolink-relay.<subdomain>.workers.dev`; attach a custom
  domain whenever you like.
- Optional push notifications ride Firebase Cloud Messaging (iOS via FCM/APNs); disabled by
  default and the relay works fine without them.

See the `nerolink-relay` repository's README for full launch instructions and a smoke-test
script that proves the whole path without Minecraft.

## What the relay stores (privacy)

The relay stores, in total: **server registrations** (display name + a **hashed** server
key) and, when push is enabled, **device push tokens** keyed by `(playerUuid, deviceId)`.
REST bodies, WebSocket frames and bearer tokens are forwarded **verbatim, never persisted,
never logged**. When a player triggers erasure in-game, the bridge sends an `erase`
tombstone over the tunnel that drops that player's push tokens on the relay too — so one
erasure request purges everything, everywhere. See [Privacy](Privacy.md).

## See also

- [Getting Started](Getting-Started.md)
- [Commands](Commands.md)
- [Configuration](Configuration.md)
- [API](API.md)
- [Home](Home.md)
