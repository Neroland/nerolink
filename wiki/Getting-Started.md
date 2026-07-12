# Getting Started

NeroLink is a **server-side** mod. Install it on the server (or on the host of a
single-player world you open to LAN); companion clients never need the mod. Once it is
running, you pair a client once and it can reach the server on your LAN or, with the
relay, from anywhere.

## Install

1. Install **Neroland Core `2.0.0+`** — NeroLink hard-depends on it and will not load
   without it. Core loads first.
2. Drop the NeroLink jar for your loader and Minecraft version into the server's `mods`
   folder. NeroLink ships for **Fabric, Forge and NeoForge** on **Minecraft 26.1.2 and
   26.2**.
3. Start the server. NeroLink binds its bridge on server-start (a live world must exist),
   using the settings in `config/nerolink.properties` — see [Configuration](Configuration.md).

That's it — there is no gameplay content to unlock. On first start the bridge listens on
port **`25580`** on all interfaces (`0.0.0.0`).

## LAN / direct quickstart

Direct mode is the simplest path when your phone and the server are on the same network.

1. In-game, run:
   ```
   /nerolink pair
   ```
   The bridge **whispers only to you** a single-use pairing code (format `XXXX-XXXX`),
   the **bridge address** (`host:25580` by default), and — if a relay is active — a
   **Server ID**. The code is valid for **5 minutes** and can be used once.
2. In your companion client, choose direct/LAN mode and enter the **bridge address** and
   the **code**.
3. The client redeems the code once and stores a long-lived, revocable **device token**;
   from then on it authenticates every call with that token.

> ⚠️ **The bridge address is *not* the "Open to LAN" game port.** When you open a
> single-player world to LAN, Minecraft prints an ephemeral game port (e.g. `55001`) —
> that is the *game* server, not the bridge. NeroLink's port is `25580` by default (set by
> `port` in the config). The pairing whisper deliberately shows the bridge port so you
> don't copy the game port by mistake.

If your phone can't reach the LAN address (home server behind NAT, no port forwarding),
use the relay instead — no address to type at all.

## Remote access via the relay (one command)

A server behind NAT can still serve companion clients through the **NeroLink relay**: the
bridge dials *out* and holds one WebSocket tunnel; phones connect to the relay; the relay
marries the two. No port forwarding, no public IP.

An operator sets it up **once**:

```
/nerolink setup
```

The bridge registers with the relay off-thread, stores the returned credentials per-world
(never a file you edit by hand), and brings the tunnel up immediately — **no server
restart**. You'll see a bold **Server ID**, an app URL, and a
"tunnel connecting — check `/nerolink status`" hint. The secret `serverKey` is never shown
in chat and never logged.

By default this registers against `https://nerorelay.neroserver.xyz`. To use your own
relay, pass an origin — `/nerolink setup <https-origin>` — or set `relayOrigin` in the
config first. See [Relay](Relay.md) for the full flow and self-hosting.

## Pairing a companion client with a Server ID

When a relay is active, `/nerolink pair` shows the **Server ID** prominently, above the
LAN address. **That Server ID plus the one-time pairing code is all the app needs** — no
address to type. Pairing, discovery, snapshots, actions and live deltas all work exactly
as they do on the LAN, just through the relay.

## Browsing the in-app wiki

Once paired, the app can browse each installed mod's wiki without leaving the game — a
handy reference while you build. NeroLink's own pages and Core's are always available; any
other Nero mod that ships a wiki shows up automatically the moment it's installed on the
server. See the [API](API.md#in-app-wiki) if you're building a client.

## Managing your devices

- `/nerolink devices` — list the devices you've paired (names + ids, never tokens).
- `/nerolink revoke <device-id>` — revoke one of your own devices.
- Operators can check `/nerolink status` for bridge/relay health.

Full details on the [Commands](Commands.md) page.

## See also

- [Commands](Commands.md)
- [Configuration](Configuration.md)
- [Relay](Relay.md)
- [Home](Home.md)
