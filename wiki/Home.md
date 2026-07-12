# NeroLink Wiki

Player-, admin- and developer-facing documentation for **NeroLink**, part of the
[Neroland ecosystem](../../neroland-mc-ecosystem/README.md). Built on **Neroland Core**.

**NeroLink is a server-side bridge mod.** It embeds a small HTTP + WebSocket server so
companion clients can check on a Neroland server while you are away — progression and
energy at a glance, alerts, and a small set of safe, server-validated actions. It is
*a window, not a controller*: it never edits the world, never moves your player, and
does nothing you couldn't do standing at the relevant block in-game. NeroLink adds no
blocks or items.

> **Status:** alpha (version `0.0.1-alpha.1`), built on **Neroland Core 2.0.0+** across
> the six cross-loader cells (Fabric, Forge, NeoForge on Minecraft 26.1.2 and 26.2). The
> v1 bridge is implemented: pairing and device tokens, capability discovery, per-module
> snapshots, safe actions, live WebSocket deltas, privacy endpoints, and outbound relay
> access for servers behind NAT. Every other Nero mod is a progressive enhancement,
> discovered at connect time — a Core-only server is already useful.

Companion clients are the official Neroland companion app (coming soon) and anything else
that speaks the NeroLink API. NeroLink itself ships no HTTP server behaviour beyond this
bridge.

The bridge also serves an **in-app wiki**: the app can browse each installed mod's
documentation (and NeroLink's and Core's own) while you play. It is mod-agnostic — any mod
that exposes a `wiki` data section through Core's link registry is automatically browsable.
See the [API](API.md#in-app-wiki) for the routes and the WIKI CONTRACT.

## Contents

- [Getting Started](Getting-Started.md) — install, LAN/direct quickstart on port
  `25580`, and one-command remote access via the relay.
- [Commands](Commands.md) — the full `/nerolink` tree: `pair`, `devices`,
  `revoke`, `status`, and `setup`.
- [Configuration](Configuration.md) — every key in `nerolink.properties`, with
  defaults and whether a change needs a restart.
- [Relay](Relay.md) — how the outbound tunnel reaches phones with no port
  forwarding, Server IDs, and self-hosting the relay Worker.
- [API](API.md) — for client and tool developers: base URLs, the envelope, auth,
  routes, the built-in `core` module, actions, the WebSocket protocol, and errors.
- [Privacy](Privacy.md) — what the bridge stores, own-data-only scoping, erasure/export,
  and telemetry.

## See also

- [Build & contributor context](../AGENTS.md)
- [Ecosystem overview](../../neroland-mc-ecosystem/README.md)
- [This mod's planning docs](../../neroland-mc-ecosystem/nerolink/)
