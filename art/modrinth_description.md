# NeroLink

**Your Neroland world in your pocket — check your drones, energy, quests and stock from anywhere, and nudge them while you're away.**

NeroLink is the **companion bridge** of the Neroland ecosystem: a small server-side mod that exposes a secure, versioned API so companion clients (like the upcoming official Neroland companion app) can stay connected to your world when you're not at your computer. It shows live dashboards — energy networks, drone tasks with ETAs, quest and advancement progress, resource stock — and allows a bounded set of **safe remote actions**: claim a quest reward, reassign a drone task, acknowledge an alert. It is deliberately *not* remote play — nothing can edit your world, and everything a client can do is something that player could already do in-game.

Built on **Neroland Core**, and *only* Core: every other Neroland mod is an optional data source that lights up automatically when installed. *(Early alpha — actively developed.)*

---

## How it works

1. **Install** NeroLink alongside Neroland Core (server side; works in single-player too).
2. **Go remote (optional).** An op runs **`/nerolink setup`** once — the server registers with the NeroLink relay and gets a short **Server ID** (like `7KM3PQ`). No port forwarding, no public IP: the server dials *out* and holds one WebSocket tunnel. On a LAN you can skip this entirely and connect directly to the bridge's local port.
3. **Pair.** Each player runs **`/nerolink pair`** — the chat whispers a single-use pairing code plus the Server ID. Enter both in a companion client and that Minecraft account is linked. No passwords, no email, no external accounts.
4. **Play on.** Dashboards update live over WebSocket; each Neroland mod on the server contributes its own sections via Core's link registry — drones from NeroLogistics, quests from NeroQuests, machines from NeroTech, and so on, discovered automatically.

## What the server admin controls

- ⚙️ **Everything is config.** Master switch, port, rate limits, read-only mode, per-action disable list, offline-action policy, token lifetime — all in `nerolink.properties`, all server-authoritative.
- 🔌 **`/nerolink status`** shows the bridge, tunnel, and paired-device state at a glance.
- 🔑 **Players own their devices** — `/nerolink devices` and `/nerolink revoke` manage pairings; tokens expire on inactivity.

## Why it fits the ecosystem

- 🧩 **Requires only [Neroland Core](https://modrinth.com/mod/nerolandcore)** — the provider API lives in Core, so mods never depend on NeroLink and NeroLink never depends on mods.
- 📡 **Capability discovery** — clients see exactly the sections your server supports; adding a Neroland mod lights up its dashboard automatically.
- 🧱 **Cross-loader** — NeoForge, Forge, and Fabric on Minecraft **26.1.2** and **26.2**.
- 🖥️ **Server-cheap** — snapshot caching, per-token rate limits, batched WebSocket deltas, and one hibernating relay tunnel. A popular bridge must never cost you TPS.

## Privacy (POPIA / GDPR)

NeroLink stores the **minimum it can**: hashed device tokens, notification preferences, and pending pairing codes — all keyed to your Minecraft account, none of it readable as plaintext, and **every response is scoped to the requesting player only**. There is no "browse other players" surface. Erasure rides Neroland Core's shared data-erasure hook, so one request purges your NeroLink data alongside every other Neroland mod — including any push tokens held by the relay. Pairing codes and tokens are never broadcast or logged.

NeroLink also includes optional, anonymous **crash telemetry** (stack trace + mod/MC/loader/OS/Java versions only — never tokens, pairing codes, relay keys, usernames, UUIDs, IPs, or world data) via Sentry on EU servers, so bugs can be fixed. Opt out any time with `telemetryEnabled = false` in `config/nerolink.properties`. Full details: **[PRIVACY.md](https://github.com/Neroland/nerolink/blob/main/PRIVACY.md)**.

## Requirements & compatibility

- **Requires [Neroland Core](https://modrinth.com/mod/nerolandcore) 2.0.0+** — install it alongside NeroLink (it loads first).
- Every other Neroland mod is optional and auto-discovered; no third-party mod is required or touched.
- **Modpacks are allowed and encouraged** — any platform, no need to ask. Use the official files and credit *NeroLink by Neroland* with links to the [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/nerolink) and the [GitHub repository](https://github.com/Neroland/nerolink). Full terms: [LICENSE](https://github.com/Neroland/nerolink/blob/main/LICENSE).

## Links

- 📖 **[Wiki](https://github.com/Neroland/nerolink/wiki)** — API spec, pairing flow, config reference.
- 💬 **[Discord](https://discord.gg/ArPXvYUzJG)** — chat, help, and sneak peeks.
- 🐞 **[Issues](https://github.com/Neroland/nerolink/issues)** — bug reports and feature requests.
- 🗒️ **[Changelog](https://github.com/Neroland/nerolink/blob/main/CHANGELOG.md)**
- 🔥 **[Also on CurseForge](https://www.curseforge.com/minecraft/mc-mods/nerolink)**

---

*Created by Neroland. The project logo was made with the help of AI image tools.*
