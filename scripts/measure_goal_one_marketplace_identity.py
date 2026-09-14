#!/usr/bin/env python3
"""GOAL-one-marketplace-identity: a home's plugin marketplace has one identity,
and every agent registration agrees with it.

Promoted from the kickoff baseline's O5 observation and the fleet script's G4
(OHV-0, #356). #352's four shapes, read from the files each agent loads:

  1  Claude registers this home's marketplace PATH under a different name
     (~/.claude/plugins/known_marketplaces.json)
  2  the generated name is a substring of another registered skill-manager name
     -- EXPOSURE, reported, not counted as a present shape
  3  the generated name equals another home's generated name (a copied identity)
  4  Codex or Claude registers or enables another home's marketplace: a Codex
     `skill-manager*` marketplace whose source is not this home's, a Codex plugin
     or Claude enabledPlugins entry `@skill-manager*` under another name
     (DEF-OHV-005 is the Claude half)

  (1) the graph clause (planted, reported, cleared) -- PENDING OHV-6: home-verdicts
      plants none of the four yet.
  (2) shapes 1, 3, 4 present on root and this project home.
  (3) fleet homes with shape 1, 3 or 4 -- context, with --fleet.

Kickoff (v0.27.2, 71c51464): root shape 4 (Codex skill-manager-919db26e; Claude
enables skt@ and cdc-agent-substrate-plugin@skill-manager-919db26e), shape 2
exposed; project none. Fleet 1/3/4 = 0/15/11, any 24 of 61.

Shape 3 needs every home's generated name, so the fleet's marketplace.json files
are always read (file reads only; no CLI). Read-only throughout.
Contract: one JSON object on stdout; exit 0 met, 1 not met, 2 could not measure.

  python3 scripts/measure_goal_one_marketplace_identity.py [--fleet]
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import home_observations as obs  # noqa: E402

GOAL = "GOAL-one-marketplace-identity"


def shapes(home: Path, checkout: Path, names: dict, known: dict) -> dict:
    name = obs.generated_marketplace_name(home)
    mp_paths = {os.path.join(s, "plugin-marketplace") for s in obs.spellings(home)}
    mp_real = {os.path.realpath(p) for p in mp_paths}
    km_sm = {k: (v.get("source") or {}).get("path") for k, v in known.items()
             if k.startswith("skill-manager") and isinstance(v, dict)}
    s1 = sorted(k for k, v in km_sm.items() if v in mp_paths and k != name)
    s2 = bool(name) and any(k != name and name in k for k in km_sm)
    s3 = sorted(h for h, n in names.items()
                if os.path.realpath(h) != os.path.realpath(home) and n and n == name)
    s4 = []
    codex_cfg = (obs.HOME / ".codex/config.toml") if checkout == obs.HOME else checkout / ".codex/config.toml"
    mks, plugins = obs.codex_marketplaces(codex_cfg)
    for mn, src in mks.items():
        if mn.startswith("skill-manager") and src and os.path.realpath(src) not in mp_real:
            s4.append(f"codex marketplace {mn} -> {src}")
    for pl in plugins:
        if "@skill-manager" in pl and pl.split("@", 1)[1] != name:
            s4.append(f"codex plugin {pl}")
    settings = (obs.HOME / ".claude/settings.json") if checkout == obs.HOME else checkout / ".claude/settings.json"
    try:
        enabled = json.loads(settings.read_text()).get("enabledPlugins") or {}
    except (OSError, json.JSONDecodeError):
        enabled = {}
    for pl in enabled:
        if "@skill-manager" in pl and pl.split("@", 1)[1] != name:
            s4.append(f"claude enabled {pl}")
    return {"generated_name": name, "shape1": s1, "shape2_exposed": s2, "shape3": s3, "shape4": s4}


def present(s: dict) -> list:
    return [n for n in ("shape1", "shape3", "shape4") if s[n]]


def main() -> int:
    argv = sys.argv[1:]
    payload = {"goal": GOAL,
               "metric": "(2) #352 shapes 1, 3, 4 present on root and this project home",
               "target": "(2) 0 on both homes",
               "clause_1": "pending OHV-6: home-verdicts plants none of #352's four shapes yet"}
    homes = obs.default_homes()
    if not homes:
        payload.update(value="no home to measure", could_not_measure="no Skill Manager home found")
        return obs.emit(payload, unmeasured=True)
    try:
        known = json.loads((obs.HOME / ".claude/plugins/known_marketplaces.json").read_text())
    except (OSError, json.JSONDecodeError):
        known = {}
    fleet = obs.discover_fleet()
    for _, h, c in homes:
        if all(os.path.realpath(h) != os.path.realpath(f) for f, _ in fleet):
            fleet.append((h, c))
    names = {str(h): obs.generated_marketplace_name(h) for h, _ in fleet}

    per_home, parts, total = {}, [], 0
    for label, home, checkout in homes:
        s = shapes(home, checkout, names, known)
        p = present(s)
        total += len(p)
        per_home[label] = dict(s, home=str(home))
        desc = ", ".join(f"shape {n[-1]}" for n in p) or "none"
        if s["shape2_exposed"]:
            desc += "; shape 2 exposed"
        parts.append(f"{label}: {desc}")
    payload["value"] = "; ".join(parts)
    payload["met"] = total == 0
    payload["homes"] = per_home
    if "--fleet" in argv:
        rows = [shapes(h, c, names, known) for h, c in fleet]
        payload["fleet"] = {
            "homes": len(rows),
            "shape1": sum(1 for r in rows if r["shape1"]),
            "shape3": sum(1 for r in rows if r["shape3"]),
            "shape4": sum(1 for r in rows if r["shape4"]),
            "any_1_3_4": sum(1 for r in rows if present(r)),
            "note": "context, no threshold"}
    return obs.emit(payload, unmeasured=False)


if __name__ == "__main__":
    sys.exit(main())
