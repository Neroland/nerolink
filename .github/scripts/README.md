# Automated dependency updates

Two complementary robots keep dependencies current:

| Robot | Config | Covers | Notes |
| --- | --- | --- | --- |
| **Dependabot** | `.github/dependabot.yml` | GitHub Actions versions; any deps declared as literal coordinates in `build.gradle` | **Cannot** see the loader/MC pins — they live as variables in `gradle.properties`, which Dependabot's Gradle ecosystem does not parse. |
| **auto-deps** | `.github/workflows/auto-deps.yml` + `.github/scripts/update_deps.py` | NeoForge, Forge, NeoForm, Fabric loader + API, JEI — the `gradle.properties` version variables, plus a coordinated Minecraft-line jump | Fills exactly the gap Dependabot leaves. |

## What auto-deps does

Runs daily (06:00 UTC / 08:00 SAST) and on manual dispatch. It reads the public
mavens (`maven.neoforged.net`, `maven.minecraftforge.net`, `maven.fabricmc.net`, `maven.blamejared.com`),
resolves the newest matching versions, and opens **two independent PRs** against
`main`:

1. **`auto-deps/within-line`** — bumps the loader/API/JEI pins for the Minecraft
   versions already tracked (`mc_versions`). Build-validated, intended to be
   mergeable.
2. **`auto-deps/mc-<version>`** — only when a *newer* Minecraft line is published
   **and** every required dependency for it exists. It wires the new line in as a
   stonecutter node (`gradle.properties` keys + `settings.gradle` `versions()` +
   the `multiloader.yml` matrix). **These PRs are expected to need manual
   cross-version source fixes** (see `CLAUDE.md` on 26.x API divergence) — the
   attached build log is the starting worklist, not a sign the change is broken.

Because PRs raised by `GITHUB_TOKEN` don't trigger other workflows, each job runs
its own `multiloader` build check and reports the outcome in the PR body.

## Required one-time setup

GitHub blocks Actions from opening PRs until you enable it:

> **Settings → Actions → General → Workflow permissions →**
> ☑ *Allow GitHub Actions to create and approve pull requests*

`permissions: { contents: write, pull-requests: write }` is already set in the
workflow.

## Manual run / tuning

- **Actions → Auto-update dependencies → Run workflow.** Inputs:
  - `include_mc_jumps` (default `true`) — set `false` to only do within-line bumps.
  - `base_branch` (default `main`) — the branch PRs target.
- **Local dry-run** (no writes, prints what would change):

  ```bash
  python3 .github/scripts/update_deps.py --mode within-line --dry-run
  python3 .github/scripts/update_deps.py --mode mc-jump     --dry-run
  ```

  Stdlib only — no `pip install`.

## Heads-up: Dependabot's target branch

`.github/dependabot.yml` targets `target-branch: dev`, but there is no `dev`
branch in this repo — Dependabot will silently no-op until that branch exists or
the target is changed to `main`. auto-deps already targets `main`.

## Privacy

Only public version strings are fetched, processed and logged — no personal data
(POPIA / GDPR clean).
