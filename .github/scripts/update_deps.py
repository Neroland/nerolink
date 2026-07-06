#!/usr/bin/env python3
"""
NeroLink dependency updater.

Resolves the latest Minecraft-modding dependency versions from the public
mavens and rewrites the version pins in this repo's root gradle.properties
(and, for a new Minecraft line, the stonecutter settings + CI matrix).

The multiloader build IS the repo root (post_port.md Phase 2 flattened it), so
the pins live in the root gradle.properties / settings.gradle. The retired
standalone build under legacy/ is frozen and intentionally NOT touched.

It does TWO independent things, selected with --mode:

  --mode within-line   Bump the loader / API versions for the Minecraft
versions ALREADY tracked (NeoForge build, Forge build,
                       NeoForm, Fabric loader + API, JEI). Safe, mergeable PRs.

  --mode mc-jump       Detect a NEWER Minecraft line than anything tracked
                       and, if all required deps for it are published, wire
                       it in as a new stonecutter node (gradle.properties
                       keys + mc_versions + settings.gradle versions() +
                       multiloader.yml matrix). These PRs are EXPECTED to
                       need manual cross-version source fixes.

Stdlib only (urllib + xml.etree) so it runs on a bare ubuntu-latest runner
with no pip install. Network is only ever used to read public maven-metadata
XML; NO personal data is fetched, stored or logged (POPIA / GDPR clean) —
the only data handled is version strings.

Outputs:
  * rewrites files in place (unless --dry-run)
  * prints a human summary to stdout
  * appends `changed=true|false` and `summary<<EOF...EOF` to $GITHUB_OUTPUT
    when running inside Actions
  * writes a markdown summary to the path given by --summary-out (optional)
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

# --------------------------------------------------------------------------
# Maven coordinates. Per-MC artifacts use {mc} / {loader} placeholders.
# --------------------------------------------------------------------------
NEOFORGE_META = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
NEOFORM_META = "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"
FORGE_META = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
FABRIC_LOADER_META = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml"
FABRIC_API_META = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
JEI_META = "https://maven.blamejared.com/mezz/jei/jei-{mc}-{loader}/maven-metadata.xml"

USER_AGENT = "nerolink-auto-deps/1.0 (+https://github.com/Neroland/nerolink)"

# Set by --dry-run; when True, _write() reports instead of touching files.
_DRY = False


def _write(path: Path, text: str) -> None:
    if _DRY:
        print(f"  (dry-run) would write {path}")
        return
    path.write_text(text, encoding="utf-8")


# --------------------------------------------------------------------------
# Version helpers
# --------------------------------------------------------------------------
def num_tuple(version: str) -> tuple[int, ...]:
    """Leading dotted-number run as an int tuple, ignoring any suffix.

    '26.2.0.7-beta' -> (26, 2, 0, 7); '0.151.0+26.1.2' -> (0, 151, 0)
    """
    m = re.match(r"\d+(?:\.\d+)*", version)
    if not m:
        return ()
    return tuple(int(p) for p in m.group(0).split("."))


def mc_tuple(mc: str) -> tuple[int, int, int]:
    """Normalise a Minecraft version to a (major, minor, patch) tuple.

    '26.2' -> (26, 2, 0); '26.1.2' -> (26, 1, 2)
    """
    parts = [int(p) for p in mc.split(".")]
    while len(parts) < 3:
        parts.append(0)
    return parts[0], parts[1], parts[2]


def mc_label(t: tuple[int, int, int]) -> str:
    """Inverse of mc_tuple: drop a trailing .0 the way Mojang labels do.

    (26, 2, 0) -> '26.2'; (26, 1, 2) -> '26.1.2'
    """
    major, minor, patch = t
    return f"{major}.{minor}" if patch == 0 else f"{major}.{minor}.{patch}"


def fetch_versions(url: str) -> list[str]:
    """Return <version> entries from a maven-metadata.xml, or [] on failure."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=30) as resp:
            root = ET.fromstring(resp.read())
    except Exception as exc:  # noqa: BLE001 - network/parse best-effort
        print(f"  ! could not fetch {url}: {exc}", file=sys.stderr)
        return []
    return [e.text.strip() for e in root.iter("version") if e.text]


# ---- "latest matching" pickers -------------------------------------------
def latest_neoforge(versions: list[str], mc: str) -> str | None:
    prefix = ".".join(str(n) for n in mc_tuple(mc)) + "."
    cands = [v for v in versions if v.startswith(prefix)]
    return max(cands, key=num_tuple) if cands else None


def latest_forge(versions: list[str], mc: str) -> str | None:
    prefix = mc + "-"
    cands = [v for v in versions if v.startswith(prefix)]
    return max(cands, key=lambda v: num_tuple(v.replace("-", "."))) if cands else None


def latest_neoform(versions: list[str], mc: str) -> str | None:
    # NeoForm is '<mc>-<rev>' (e.g. 26.1.2-1); fold the '-' into the number
    # tuple so the trailing revision actually participates in the ordering.
    prefix = mc + "-"
    cands = [v for v in versions if v.startswith(prefix)]
    return max(cands, key=lambda v: num_tuple(v.replace("-", "."))) if cands else None


def latest_fabric_api(versions: list[str], mc: str) -> str | None:
    suffix = "+" + mc
    cands = [v for v in versions if v.endswith(suffix)]
    return max(cands, key=lambda v: num_tuple(v.split("+", 1)[0])) if cands else None


def latest_fabric_loader(versions: list[str]) -> str | None:
    cands = [v for v in versions if re.fullmatch(r"\d+\.\d+\.\d+", v)]
    return max(cands, key=num_tuple) if cands else None


def latest_jei(mc: str, loader: str) -> str | None:
    versions = fetch_versions(JEI_META.format(mc=mc, loader=loader))
    return max(versions, key=num_tuple) if versions else None


# --------------------------------------------------------------------------
# gradle.properties editing (line-based, format-preserving)
# --------------------------------------------------------------------------
def set_property(text: str, key: str, value: str, changes: list[str]) -> str:
    """Replace `key=...` if present and different. Records a change line."""
    pattern = re.compile(rf"^(?P<k>{re.escape(key)})=(?P<v>.*)$", re.MULTILINE)
    m = pattern.search(text)
    if not m:
        return text
    old = m.group("v").strip()
    if old == value:
        return text
    changes.append(f"{key}: {old} -> {value}")
    return pattern.sub(lambda _: f"{key}={value}", text, count=1)


def get_property(text: str, key: str) -> str | None:
    m = re.search(rf"^{re.escape(key)}=(.*)$", text, re.MULTILINE)
    return m.group(1).strip() if m else None


# --------------------------------------------------------------------------
# Modes
# --------------------------------------------------------------------------
def do_within_line(repo: Path, changes: list[str]) -> None:
    """Bump in-place pins for already-tracked Minecraft versions.

    Operates on the flattened repo-root gradle.properties (the multiloader build
    IS the repo root since post_port.md Phase 2). The retired standalone build
    under legacy/ is frozen and intentionally NOT bumped.
    """
    nf = fetch_versions(NEOFORGE_META)
    nfm = fetch_versions(NEOFORM_META)
    fg = fetch_versions(FORGE_META)
    fl = fetch_versions(FABRIC_LOADER_META)
    fa = fetch_versions(FABRIC_API_META)

    gp = repo / "gradle.properties"
    if not gp.exists():
        return
    txt = gp.read_text(encoding="utf-8")
    local: list[str] = []
    mcs = [m.strip() for m in (get_property(txt, "mc_versions") or "").split(",") if m.strip()]
    if (v := latest_fabric_loader(fl)):
        txt = set_property(txt, "fabric_loader_version", v, local)
    for mc in mcs:
        if (v := latest_neoform(nfm, mc)):
            txt = set_property(txt, f"neo_form_version_{mc}", v, local)
        if (v := latest_neoforge(nf, mc)):
            txt = set_property(txt, f"neo_version_{mc}", v, local)
        if (v := latest_forge(fg, mc)):
            txt = set_property(txt, f"forge_version_{mc}", v, local)
        if (v := latest_fabric_api(fa, mc)):
            txt = set_property(txt, f"fabric_api_version_{mc}", v, local)
        if (v := latest_jei(mc, "neoforge")):
            txt = set_property(txt, f"jei_version_{mc}", v, local)
    if local:
        _write(gp, txt)
        changes += [f"gradle.properties {c}" for c in local]


def discover_new_mc(tracked: list[str], nf: list[str]) -> str | None:
    """The newest Minecraft line above everything tracked, or None."""
    if not tracked:
        return None
    ceiling = max(mc_tuple(m) for m in tracked)
    candidates: set[tuple[int, int, int]] = set()
    for v in nf:
        t = num_tuple(v)
        if len(t) >= 3:  # neoforge mirrors MC in its first three segments
            candidates.add((t[0], t[1], t[2]))
    above = [c for c in candidates if c > ceiling]
    return mc_label(max(above)) if above else None


def do_mc_jump(repo: Path, changes: list[str]) -> str | None:
    """Wire a new Minecraft line as a stonecutter node. Returns the MC or None."""
    ml_gp = repo / "gradle.properties"
    if not ml_gp.exists():
        return None
    txt = ml_gp.read_text(encoding="utf-8")
    tracked = [m.strip() for m in (get_property(txt, "mc_versions") or "").split(",") if m.strip()]

    nf = fetch_versions(NEOFORGE_META)
    new_mc = discover_new_mc(tracked, nf)
    if not new_mc:
        print("No Minecraft line newer than tracked versions.")
        return None

    # Resolve every dependency the new node needs. If anything required is
    # missing, defer the jump rather than wire a node that cannot configure.
    nfm = fetch_versions(NEOFORM_META)
    fa = fetch_versions(FABRIC_API_META)
    fg = fetch_versions(FORGE_META)
    resolved = {
        "neo_form": latest_neoform(nfm, new_mc),
        "neo_version": latest_neoforge(nf, new_mc),
        "forge_version": latest_forge(fg, new_mc),
        "fabric_api": latest_fabric_api(fa, new_mc),
        "jei_nf": latest_jei(new_mc, "neoforge"),
        "jei_fab": latest_jei(new_mc, "fabric"),
    }
    missing = [k for k, v in resolved.items() if not v]
    if missing:
        print(f"Minecraft {new_mc} is out but not buildable yet "
              f"(missing: {', '.join(missing)}). Deferring.")
        return None

    # 1) gradle.properties: add per-MC keys + extend mc_versions
    txt = re.sub(r"^(mc_versions=.*)$",
                 lambda m: m.group(1) + "," + new_mc, txt, count=1, flags=re.MULTILINE)
    addition = (
        f"\n## Auto-added by update_deps.py for Minecraft {new_mc} "
        f"(verify the JEI fabric pin matches the loader)\n"
        f"neo_form_version_{new_mc}={resolved['neo_form']}\n"
        f"neo_version_{new_mc}={resolved['neo_version']}\n"
        f"forge_version_{new_mc}={resolved['forge_version']}\n"
        f"jei_version_{new_mc}={resolved['jei_nf']}\n"
        f"fabric_api_version_{new_mc}={resolved['fabric_api']}\n"
    )
    if not txt.endswith("\n"):
        txt += "\n"
    txt += addition
    _write(ml_gp, txt)
    changes.append(f"gradle.properties: added Minecraft {new_mc} pins")

    # 2) settings.gradle: append the new version to both stonecutter branches
    settings = repo / "settings.gradle"
    s = settings.read_text(encoding="utf-8")
    s2 = re.sub(r"versions\(([^)]*)\)",
                lambda m: f"versions({m.group(1)}, '{new_mc}')", s)
    if s2 != s:
        _write(settings, s2)
        changes.append(f"settings.gradle: stonecutter node {new_mc}")

    # 3) multiloader.yml: add neoforge + forge + fabric matrix entries
    wf = repo / ".github" / "workflows" / "multiloader.yml"
    w = wf.read_text(encoding="utf-8")
    entries = (f'          - loader: neoforge\n            mc: "{new_mc}"\n'
               f'          - loader: forge\n            mc: "{new_mc}"\n'
               f'          - loader: fabric\n            mc: "{new_mc}"\n')
    w2 = re.sub(r"(include:\n)", lambda m: m.group(1) + entries, w, count=1)
    if w2 != w:
        _write(wf, w2)
        changes.append(f".github/workflows/multiloader.yml: matrix cells for {new_mc}")

    return new_mc


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--mode", choices=["within-line", "mc-jump"], required=True)
    ap.add_argument("--repo-root", default=".")
    ap.add_argument("--summary-out", help="write a markdown summary here")
    ap.add_argument("--dry-run", action="store_true",
                    help="resolve + report but do not modify files")
    args = ap.parse_args()

    # Unbuffered-ish logs so progress shows live in CI.
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:  # noqa: BLE001
        pass

    repo = Path(args.repo_root).resolve()
    changes: list[str] = []
    new_mc: str | None = None

    if args.dry_run:
        global _DRY
        _DRY = True

    if args.mode == "within-line":
        do_within_line(repo, changes)
    else:
        new_mc = do_mc_jump(repo, changes)

    # ---- report ----------------------------------------------------------
    changed = bool(changes)
    if changed:
        print(f"\n{len(changes)} change(s):")
        for c in changes:
            print(f"  - {c}")
    else:
        print("\nNo changes — everything is already up to date.")

    md_lines = []
    title = ("Minecraft version jump" if args.mode == "mc-jump" else
             "Within-line dependency bumps")
    md_lines.append(f"### {title}\n")
    if changed:
        if new_mc:
            md_lines.append(
                f"Wired Minecraft **{new_mc}** as a new stonecutter node. "
                f"This is expected to need manual cross-version source fixes "
                f"(see CLAUDE.md on 26.x API divergence).\n")
        md_lines.append("| Change |")
        md_lines.append("| --- |")
        md_lines += [f"| `{c}` |" for c in changes]
    else:
        md_lines.append("_No changes — everything is already up to date._")
    md = "\n".join(md_lines) + "\n"

    if args.summary_out:
        Path(args.summary_out).write_text(md, encoding="utf-8")

    # Stonecutter build tasks for every tracked cell, so the workflow can
    # validate exactly what exists (PRs opened by GITHUB_TOKEN don't trigger
    # multiloader.yml, so this run must do its own build check).
    ml_gp = repo / "gradle.properties"
    build_tasks = ""
    if ml_gp.exists():
        mcs = [m.strip() for m in
               (get_property(ml_gp.read_text(encoding="utf-8"), "mc_versions") or "").split(",")
               if m.strip()]
        tasks = [f":{loader}:{mc}:build" for mc in mcs for loader in ("neoforge", "forge", "fabric")]
        build_tasks = " ".join(tasks)

    gh_out = os.environ.get("GITHUB_OUTPUT")
    if gh_out:
        with open(gh_out, "a", encoding="utf-8") as fh:
            fh.write(f"changed={'true' if changed else 'false'}\n")
            if new_mc:
                fh.write(f"new_mc={new_mc}\n")
            fh.write(f"build_tasks={build_tasks}\n")
            fh.write("summary<<__EOF__\n" + md + "__EOF__\n")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
