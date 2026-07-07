# CurseForge listing — manual setup pack

CurseForge's API can't be driven like Modrinth's (see the modrinth-description workflow),
so the page is a one-time manual paste. Everything you need:

## Project settings

| Field | Value |
|-------|-------|
| Name | NeroLink |
| Summary (≤ 250 chars) | Your Neroland world in your pocket — a server-side bridge that lets companion apps watch drones, energy, quests and stock, and take safe remote actions. Requires only Neroland Core. |
| Categories | Server Utility · Utility & QoL · Map and Information |
| License | Custom → link to https://github.com/Neroland/nerolink/blob/main/LICENSE |
| Icon | `art/logo/nerolink_logo_400.png` |
| Environment | Server (client optional — single-player works) |
| Source | https://github.com/Neroland/nerolink |
| Issues | https://github.com/Neroland/nerolink/issues |
| Wiki | https://github.com/Neroland/nerolink/wiki |

## Description body

Paste `art/modrinth_description.md` (CurseForge's editor accepts the same Markdown;
double-check the emoji bullets render, strip them if not). Update the "Also on
CurseForge" link at the bottom to point at Modrinth instead:
`https://modrinth.com/mod/nerolink`.

## Relations

- **Required dependency:** Neroland Core (`nerolandcore`).
- Optional: list the other Neroland mods as "optional dependency" as they publish.

## Gallery

Reuse the Modrinth gallery set (see `.github/workflows/modrinth-gallery.yml` sources)
once screenshots exist — in-game `/nerolink pair` chat, `/nerolink status`, and a
companion-client dashboard shot make the strongest three.
