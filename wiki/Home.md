# NeroLink Wiki

Player-, admin- and developer-facing documentation for **NeroLink**, part of the
Neroland ecosystem. Built on **Neroland Core**.

**NeroLink is a server-side bridge mod.** It embeds a small HTTPS + WebSocket server so
companion clients can check on a Neroland server while you are away — progression and
energy at a glance, alerts, and a small set of safe, server-validated actions. It is
*a window, not a controller*: it never edits the world, never moves your player, and
does nothing you couldn't do standing at the relevant block in-game. NeroLink adds no
blocks or items.

> **Status:** heading for **1.0.0**, the first production release, built on **Neroland
> Core 1.13.0 or newer (below 2.0)** across the nine cross-loader cells (Fabric, Forge,
> NeoForge on Minecraft 26.1.2, 26.2 and 26.3). The v1 bridge is implemented: TLS pairing
> and device tokens, capability discovery, per-module snapshots, safe actions, live
> WebSocket deltas, privacy endpoints, and outbound relay access for servers behind NAT. Every other Nero mod is a progressive enhancement,
> discovered at connect time — a Core-only server is already useful.

Companion clients are the official Neroland companion app (coming soon) and anything else
that speaks the NeroLink API. NeroLink itself ships no HTTP server behaviour beyond this
bridge.

The bridge also serves an **in-app wiki**: the app can browse each installed mod's
documentation (and NeroLink's and Core's own) while you play. It is mod-agnostic — any mod
that exposes a `wiki` data section through Core's link registry is automatically browsable.
See the [API](API.md#in-app-wiki) for the routes and the WIKI CONTRACT.

## Contents

- [Getting Started](Getting-Started.md) — install, direct-mode quickstart on port
  `25580` (with the Security code check), and one-command remote access via the relay.
- [Commands](Commands.md) — the full `/nerolink` tree: `pair`, `devices`,
  `revoke`, `status`, and `setup`.
- [Configuration](Configuration.md) — every key in `nerolink.properties`, with
  defaults and whether a change needs a restart.
- [Relay](Relay.md) — how the outbound tunnel reaches phones with no port
  forwarding, Server IDs, and self-hosting the relay Worker.
- [API](API.md) — for client and tool developers: base URLs, the envelope, auth,
  direct-mode TLS and `codeProof` pairing, routes, the built-in `core` module, actions, the
  WebSocket protocol, errors and rate limits.
- [Privacy](Privacy.md) — what the bridge stores, retention, own-data-only scoping,
  erasure/export, and telemetry.

## See also

- [Build & contributor context](../AGENTS.md)
