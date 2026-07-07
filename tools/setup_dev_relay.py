#!/usr/bin/env python3
"""
Enable the NeroLink relay in the LOCAL DEV run configs (runClient/runServer).

Registers this dev instance with a relay (default: a local `wrangler dev` on
http://localhost:8787), stores the credentials in .dev-relay.json at the repo
root (gitignored - the key is a secret), and writes relayUrl/relayKey into
config/nerolink.properties of every loader x MC-version run directory so the
next F5 / `ML: Run ...` launch tunnels automatically.

Usage:
  python tools/setup_dev_relay.py                 # local wrangler dev relay
  python tools/setup_dev_relay.py --relay https://nerorelay.neroserver.xyz
  python tools/setup_dev_relay.py --new           # force a fresh registration
  python tools/setup_dev_relay.py --off           # blank relayUrl/relayKey again

Credentials are reused across runs (per relay origin) until --new. Registration
state lives in the relay's Durable Object: a restarted `wrangler dev` usually
keeps it (.wrangler/state), but if the bridge logs auth failures, re-run with
--new. DEV TOOL ONLY - never commit .dev-relay.json.
"""
import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATE_FILE = ROOT / ".dev-relay.json"
LOADERS = ("fabric", "forge", "neoforge")
MC_VERSIONS = ("26.1.2", "26.2")
# Loom uses <node>/run; ForgeGradle/ModDevGradle use <node>/runs/{client,server}.
RUN_SUBDIRS = ("runs/client", "runs/server", "run")


def register(relay: str, name: str) -> dict:
    request = urllib.request.Request(
        f"{relay}/register",
        method="POST",
        # Cloudflare bot protection 403s python-urllib's default UA; identify honestly.
        headers={"content-type": "application/json", "user-agent": "nerolink-setup-dev-relay/1.0"},
        data=json.dumps({"serverName": name}).encode(),
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = json.load(response)
    except urllib.error.URLError as e:
        sys.exit(
            f"Could not reach the relay at {relay} ({e.reason}).\n"
            "Is it running? For a local relay start it first:\n"
            "  cd ../nerolink-relay && npx wrangler dev --ip 0.0.0.0\n"
            "(VS Code task: 'Relay: start local relay'), or pass a deployed relay\n"
            "with --relay https://nerorelay.neroserver.xyz"
        )
    except json.JSONDecodeError:
        sys.exit(f"{relay}/register did not return JSON - is that URL really the NeroLink relay?")
    if not body.get("ok"):
        sys.exit(f"registration failed: {body}")
    return body["data"]


def load_state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {}


def run_config_paths() -> list[Path]:
    paths = []
    for loader in LOADERS:
        for mc in MC_VERSIONS:
            for sub in RUN_SUBDIRS:
                paths.append(ROOT / loader / "versions" / mc / sub / "config" / "nerolink.properties")
    return paths


def write_properties(path: Path, relay_url: str, relay_key: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    if path.exists():
        lines = path.read_text(encoding="utf-8").splitlines()
    # Drop existing relay entries, keep everything else (comments included).
    lines = [l for l in lines if not re.match(r"^relay(Url|Key)=", l)]
    lines.append(f"relayUrl={relay_url}")
    lines.append(f"relayKey={relay_key}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay", default="http://localhost:8787",
                        help="relay origin (default: local wrangler dev)")
    parser.add_argument("--name", default="NeroLink dev instance")
    parser.add_argument("--new", action="store_true", help="force a fresh registration")
    parser.add_argument("--off", action="store_true", help="disable the relay in all dev run configs")
    args = parser.parse_args()

    if args.off:
        for path in run_config_paths():
            if path.exists():
                write_properties(path, "", "")
                print(f"relay OFF   {path.relative_to(ROOT)}")
        print("Relay disabled in all existing dev run configs.")
        return

    relay = args.relay.rstrip("/")
    state = load_state()
    entry = state.get(relay)
    if entry is None or args.new:
        print(f"registering with {relay} ...")
        entry = register(relay, args.name)
        state[relay] = entry
        STATE_FILE.write_text(json.dumps(state, indent=2) + "\n")
        print(f"stored credentials in {STATE_FILE.name} (gitignored - keep it that way)")
    else:
        print(f"reusing registration for {relay} (use --new to re-register)")

    for path in run_config_paths():
        write_properties(path, entry["tunnelUrl"], entry["serverKey"])
        print(f"relay ON    {path.relative_to(ROOT)}")

    print()
    print(f"serverId : {entry['serverId']}")
    print(f"app URL  : {entry['baseUrl']}")
    print("Launch a client/server run, then check '/nerolink status' says Relay: connected.")
    print("Phone tip: for a LOCAL relay start it with `npx wrangler dev --ip 0.0.0.0` and")
    print("use http://<your-LAN-IP>:8787/s/<serverId> in the app (Direct mode).")


if __name__ == "__main__":
    main()
