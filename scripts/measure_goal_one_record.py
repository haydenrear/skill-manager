#!/usr/bin/env python3
"""GOAL-one-record: the census names nothing the disk does not hold, and an
installed record agrees with its checkout.

Promoted from the kickoff baseline's O2 and O7 observations (OHV-0, #356).

  (1) census outputs missing on disk, plus ledger-only rows, on root and this
      project home. `artifacts list --json` is the census; every home-scope
      output it names is `lexists`-ed independently. An output counts as missing
      when the stat finds nothing AND the census does not itself mark its
      presence `unknown` (those are reported separately). A ledger-only row is
      origin `ledger` with no outputs (#334's shape).
  (2) rows a prune drops that the re-record restores -- NOT measured here: it
      needs a prune, and this harness is read-only. `release_regression.py` and
      the `artifact-dag` graph own it.
  (3) installed records whose `version` disagrees with the checkout's manifest
      while the `gitHash` agrees (DEF-OHV-004).

Kickoff (v0.27.2, 71c51464): root 15 missing + 14 ledger-only, versions 5;
project 7 + 0, versions 1. Target: 0 everywhere.

Read-only: `artifacts list` and `git rev-parse`; no --fix/--record/--ack/sync.
Contract: one JSON object on stdout; exit 0 met, 1 not met, 2 could not measure.

  python3 scripts/measure_goal_one_record.py [<store> ...]
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import home_observations as obs  # noqa: E402

GOAL = "GOAL-one-record"


def census(home: Path) -> dict:
    cli = obs.cli_for(home)
    if cli is None:
        return {"measured": False, "why": "no skill-manager CLI"}
    # No --home flag: `artifacts list` does not take one (DEF-121). The pinned
    # shim binds its own home; SKILL_MANAGER_HOME covers the PATH fallback.
    rc, out, err = obs.run([cli, "artifacts", "list", "--json"], env_home=home)
    doc = obs.json_from_stdout(out)
    if rc != 0 or doc is None:
        return {"measured": False, "why": f"artifacts list exited {rc}; stderr: {err.strip()[-300:]}"}
    missing, unknown, presence_says_present, ledger_only = [], [], [], []
    for a in doc.get("artifacts", []):
        if a.get("origin") == "ledger" and not a.get("outputs"):
            ledger_only.append(a.get("id"))
        for o in a.get("outputs", []):
            if o.get("scope") != "home":
                continue
            if os.path.lexists(home / o["path"]):
                continue
            row = f'{a.get("id")} -> {o["path"]}'
            if o.get("presence") == "unknown":
                unknown.append(row)
                continue
            missing.append(row)
            if o.get("presence") == "present":
                presence_says_present.append(row)
    return {"measured": True, "cli": cli, "declared": len(doc.get("artifacts", [])),
            "missing_outputs": missing, "stat_missing_but_census_says_unknown": unknown,
            "stat_missing_but_census_says_present": presence_says_present,
            "ledger_only_rows": ledger_only}


def manifest_version(store: Path):
    pj = store / ".claude-plugin" / "plugin.json"
    if pj.is_file():
        try:
            v = json.loads(pj.read_text()).get("version")
            if v:
                return v
        except (OSError, json.JSONDecodeError):
            pass
    tm = store / "skill-manager.toml"
    if tm.is_file():
        m = re.search(r'^\s*version\s*=\s*"([^"]+)"', tm.read_text(errors="replace"), re.M)
        return m.group(1) if m else None
    return None


def records(home: Path) -> dict:
    stale, hash_disagrees = [], []
    for rec in sorted((home / "installed").glob("*.json")):
        if rec.name.endswith(".projections.json"):
            continue
        try:
            d = json.loads(rec.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        name = d.get("name", rec.stem)
        store = next((home / k / name for k in ("skills", "plugins", "docs", "harnesses")
                      if (home / k / name).is_dir()), None)
        if store is None:
            continue
        hash_agrees = True
        if (store / ".git").exists() and d.get("gitHash"):
            rc, out, _ = obs.run(["git", "-C", str(store), "rev-parse", "HEAD"], timeout=60)
            if rc == 0 and out.strip() and out.strip() != d["gitHash"]:
                hash_agrees = False
                hash_disagrees.append({"unit": name, "record": d["gitHash"][:8],
                                       "checkout": out.strip()[:8]})
        mver = manifest_version(store)
        if hash_agrees and mver and d.get("version") and mver != d["version"]:
            stale.append({"unit": name, "record_version": d["version"],
                          "checkout_manifest_version": mver})
    return {"stale_version_hash_agrees": stale, "hash_disagreements": hash_disagrees}


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    homes = [(Path(a).name, Path(a)) for a in args] or [(l, h) for l, h, _ in obs.default_homes()]
    payload = {"goal": GOAL,
               "metric": "(1) census outputs missing on disk + ledger-only rows; (3) installed "
                         "records whose version disagrees with the checkout while the hash agrees; "
                         "on root and this project home",
               "target": "(1) 0 and 0 on both homes; (3) 0",
               "clause_2": "not measured here (needs a prune): release_regression.py, artifact-dag"}
    if not homes:
        payload.update(value="no home to measure", could_not_measure="no Skill Manager home found")
        return obs.emit(payload, unmeasured=True)
    per_home, parts, unmeasured, total = {}, [], False, 0
    for label, home in homes:
        c = census(home)
        r = records(home)
        per_home[label] = {"home": str(home), "census": c, "records": r}
        nver = len(r["stale_version_hash_agrees"])
        if not c["measured"]:
            unmeasured = True
            parts.append(f"{label} census unmeasured ({c['why'][:80]}), versions {nver}")
            total += nver
            continue
        nm, nl = len(c["missing_outputs"]), len(c["ledger_only_rows"])
        total += nm + nl + nver
        parts.append(f"{label} {nm} missing + {nl} ledger-only, versions {nver}")
    payload["value"] = "; ".join(parts)
    payload["met"] = total == 0
    payload["homes"] = per_home
    return obs.emit(payload, unmeasured=unmeasured)


if __name__ == "__main__":
    sys.exit(main())
