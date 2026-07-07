# Commands

NeroLink adds a single command tree, `/nerolink`, built with vanilla Brigadier so it
behaves identically on every loader. Player sub-commands work for anyone; operator
sub-commands require permission **level 2** (game-master / op).

| Sub-command | Who | What it's for |
| --- | --- | --- |
| `/nerolink pair` | any player | Mint a one-time pairing code for a new device. |
| `/nerolink devices` | any player | List your own paired devices. |
| `/nerolink revoke <device>` | any player | Revoke one of your own devices. |
| `/nerolink status` | op (level 2) | Bridge + relay health, device count, module list. |
| `/nerolink setup [origin]` | op (level 2) | Register with the relay and open the tunnel. |
| `/nerolink setup force [origin]` | op (level 2) | Re-register and get a fresh Server ID. |

## `/nerolink pair`

Mints a **single-use pairing code** and whispers it — and only it — to the running player.
Nothing is broadcast or logged. The whisper contains:

- **Pairing code** — format `XXXX-XXXX`, bold/gold. **Valid for 5 minutes, single-use.**
- **Server ID** — shown only when a relay registration is active; this is what players
  enter in the app alongside the code.
- **Bridge address** — `host:port` for LAN/direct mode (the first site-local IPv4 and the
  configured `port`), tagged `(LAN/direct mode — NOT the 'open to LAN' game port)`.

Only a player can run this (it needs an in-game identity); running it from console fails
with *"Only a player can pair a device."* If the bridge isn't running you'll see *"The
NeroLink bridge is not running on this server."*

The in-game session *is* the identity proof — there are no passwords or emails. A client
redeems the code once via the API to receive its device token.

## `/nerolink devices`

Lists your paired devices, one per line, as `deviceName (deviceId)`. Tokens are **never**
shown — only names and ids. Ends with a reminder to revoke with
`/nerolink revoke <device-id>`. If you have none, it prints *"No paired devices."* Scoped
to the calling player's own devices only.

## `/nerolink revoke <device>`

Revokes one of **your own** devices by its id (`<device>` is the device id from
`/nerolink devices`). The bridge deletes the stored token hash and forgets the device's
rate-limit bucket, so that device can no longer authenticate. If the id isn't one of yours
it fails with *"No such device of yours: &lt;id&gt;"* — you can never revoke another
player's device.

## `/nerolink status` (op)

Operator-only summary of the bridge:

```
[NeroLink] Bridge running. Paired devices: N. Relay: connected. Server ID: <id>. Modules: core, ...
```

- **Paired devices** — an aggregate count across online players (no personal data).
- **Relay** — `connected`, `connecting`, or `disabled`.
- **Server ID** — shown when a relay registration is active.
- **Modules** — the link modules currently registered (always at least `core`).

If the bridge isn't running it reports *"Bridge is not running."*

## `/nerolink setup [origin]` (op)

One-shot **relay onboarding**. Registers this server with a NeroLink relay
(`POST <origin>/register`) off the server thread, persists the returned credentials
per-world, and dials the tunnel immediately — **no server restart**.

- With no argument it uses the `relayOrigin` config value (default
  `https://nerorelay.neroserver.xyz`).
- `/nerolink setup <https-origin>` registers against a specific relay origin.
- If this server is **already registered** with that origin, plain `setup` doesn't
  re-register — it just re-dials the existing tunnel and reports the stored Server ID.

On success the operator sees *"Relay registered!"*, then the bold/gold **Server ID**
`(players enter this in the app)`, the **App URL**, and a
`Tunnel connecting — check /nerolink status` hint. The secret `serverKey` is never printed
or logged.

Failure messages are specific:

- **Unreachable** — *"Could not reach the relay at &lt;origin&gt;…"* (check the origin and
  the server's connection).
- **Registration closed** — *"Registration is closed on this relay
  (`REGISTRATION_OPEN=false`)…"* (ask the relay operator to reopen it).
- **Bad response** — *"The relay returned an unexpected response…"* (make sure the origin
  points at a NeroLink relay).

## `/nerolink setup force [origin]` (op)

Same as `setup`, but **discards the stored credentials and registers afresh**, producing a
new Server ID even if a registration already exists. Use this to move to a different relay
or rotate the registration. Optionally takes an `[origin]`
(`/nerolink setup force <https-origin>`).

## See also

- [Getting Started](Getting-Started.md)
- [Relay](Relay.md)
- [Configuration](Configuration.md)
- [Home](Home.md)
