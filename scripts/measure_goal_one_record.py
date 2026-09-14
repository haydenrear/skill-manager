#!/usr/bin/env python3
"""GOAL-one-record: the census names nothing the disk does not hold, and an
installed record agrees with its checkout.

Promoted from the kickoff baseline's O2 and O7 observations (OHV-0, #356).
Clause (1) re-implemented at OHV-8 (#358) for the schedule_revision-3 definition.

  (1) AMENDED at schedule_revision 3: census rows whose owner is gone AND whose
      outputs are gone (independent lexists), plus ledger-only rows produced by
      a removal after OHV-3, on root and this project home AFTER A PRUNE --
      excluding PATH-served declared shims (DEF-OHV-130) and pre-OHV-3
      projection rows (DEF-OHV-131), which are counted separately.

      "After a prune" is PROJECTED, never performed: this harness runs
      `artifacts prune --dry-run --json` and removes the rows that plan would
      prune from `artifacts list --json`. It writes nothing. The projection is
      exact only if the real prune applies every PRUNE step (OHV-3's apply
      re-records the ledger without the pruned ids).

      Pre-OHV-3 is decided by the kickoff record, the only dated evidence: a
      ledger-only row is DEF-OHV-131 when its id is in the kickoff baseline's
      ledger_only_rows for that home (release_regression.json, 2026-09-13,
      before OHV-3 existed). The ledger carries no per-row timestamps, so any
      OTHER ledger-only row cannot be proven older than OHV-3 and is COUNTED.

      The kickoff definition (every missing output + every ledger-only row,
      before a prune) is still reported, as `kickoff_definition`.
  (2) rows a prune drops that the re-record restores -- NOT measured here: it
      needs a real prune. The `artifact-dag` graph and ArtifactPruneTest own it.
  (3) installed records whose `version` disagrees with the checkout's manifest
      while the `gitHash` agrees (DEF-OHV-004).

Kickoff (v0.27.2, 71c51464): root 15 missing + 14 ledger-only, versions 5;
project 7 + 0, versions 1. Targets: (1) 0 on both homes under the revision-3
definition, DEF-OHV-130/131 reported separately; (3) 0.

The build: SKILL_MANAGER_MEASURE_CLI when set, else the home's pin
(scripts/home_observations.py cli_for). Read-only: `artifacts list`,
`artifacts prune --dry-run`, `git rev-parse`; no --fix/--record/--ack/sync.
Contract: one JSON object on stdout; exit 0 met, 1 not met, 2 could not measure.

  python3 scripts/measure_goal_one_record.py [<store> ...]
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import home_observations as obs  # noqa: E402

GOAL = "GOAL-one-record"
KICKOFF = (obs.REPO / "results/epic-one-home-one-verdict/baseline/2026-09-13-v0.27.2"
           / "release_regression.json")


def kickoff_ledger_only(home: Path) -> set:
    """Ledger-only row ids the kickoff recorded for this home (pre-OHV-3 evidence)."""
    try:
        doc = json.loads(KICKOFF.read_text())
    except (OSError, json.JSONDecodeError):
        return set()
    out = set()
    real = os.path.realpath(home)
    for r in doc.get("results", []):
        if r.get("check") == "census matches disk" and os.path.realpath(r.get("home", "")) == real:
            out.update(r.get("ledger_only_rows") or [])
    return out


def installed_units(home: Path) -> set:
    return {p.name[:-len(".json")] for p in (home / "installed").glob("*.json")
            if not p.name.endswith(".projections.json")}


def output_absent(home: Path, o: dict) -> bool:
    p = o.get("path") or ""
    target = Path(p) if os.path.isabs(p) else home / p
    return not os.path.lexists(target)


def census(home: Path) -> dict:
    cli = obs.cli_for(home)
    if cli is None:
        return {"measured": False, "why": "no skill-manager CLI"}
    # No --home flag: `artifacts list` does not take one (DEF-121). SKILL_MANAGER_HOME binds it.
    rc, out, err = obs.run([cli, "artifacts", "list", "--json"], env_home=home)
    doc = obs.json_from_stdout(out)
    if rc != 0 or doc is None:
        return {"measured": False, "why": f"artifacts list exited {rc}; stderr: {err.strip()[-300:]}"}
    prc, pout, perr = obs.run([cli, "artifacts", "prune", "--dry-run", "--json"], env_home=home)
    plan = obs.json_from_stdout(pout)
    if prc != 0 or plan is None or plan.get("dryRun") is not True:
        return {"measured": False, "why": f"artifacts prune --dry-run exited {prc}; stderr: {perr.strip()[-300:]}"}

    rows = doc.get("artifacts", [])
    # -- kickoff definition, before any prune (kept for the trajectory)
    missing, unknown, ledger_only = [], [], []
    for a in rows:
        if a.get("origin") == "ledger" and not a.get("outputs"):
            ledger_only.append(a.get("id"))
        for o in a.get("outputs", []):
            if o.get("scope") != "home" or not output_absent(home, o):
                continue
            (unknown if o.get("presence") == "unknown" else missing).append(f'{a.get("id")} -> {o["path"]}')

    # -- revision-3 definition, on the dry-run projection of a prune
    would_prune = {r["id"] for r in plan.get("pruned", [])}
    kept_verdicts = {r["id"]: {"verdict": r.get("verdict"), "reason": r.get("reason")}
                     for r in plan.get("kept", [])}
    after = [a for a in rows if a.get("id") not in would_prune]
    installed = installed_units(home)
    pre_ohv3 = kickoff_ledger_only(home)
    counted_owner_and_outputs_gone, counted_ledger_only_not_provably_pre_ohv3 = [], []
    def130_path_served, def131_pre_ohv3_projection = [], []
    residue_owner_installed_output_missing = []
    for a in after:
        aid, owner, kind = a.get("id"), a.get("owner"), a.get("kind")
        owner_gone = bool(owner) and owner not in installed
        outs = a.get("outputs") or []
        if a.get("origin") == "ledger" and not outs:
            entry = {"id": aid, "owner": owner, "owner_installed": not owner_gone,
                     "prune_plan": kept_verdicts.get(aid)}
            if kind == "projection" and aid in pre_ohv3:
                def131_pre_ohv3_projection.append(entry)
            else:
                counted_ledger_only_not_provably_pre_ohv3.append(entry)
            continue
        if not outs or not all(output_absent(home, o) for o in outs):
            continue
        entry = {"id": aid, "owner": owner, "kind": kind,
                 "outputs": [o.get("path") for o in outs], "prune_plan": kept_verdicts.get(aid)}
        if owner_gone:
            counted_owner_and_outputs_gone.append(entry)
            continue
        tool = aid.rsplit("/", 1)[-1] if kind == "cli-shim" else None
        on_path = shutil.which(tool) if tool else None
        if on_path:
            entry["served_from_path"] = on_path
            def130_path_served.append(entry)
        else:
            residue_owner_installed_output_missing.append(entry)

    return {"measured": True, "cli": cli, "build": doc.get("build"),
            "declared": len(rows),
            "kickoff_definition": {"missing_outputs": missing,
                                   "stat_missing_but_census_says_unknown": unknown,
                                   "ledger_only_rows": ledger_only},
            "prune_dry_run": {"ledger_present": plan.get("ledgerPresent"),
                              "would_prune": sorted(would_prune),
                              "would_keep_refused": [k for k, v in kept_verdicts.items()
                                                     if v["verdict"] == "refused"]},
            "after_prune_projection": {
                "rows": len(after),
                "counted_owner_gone_and_outputs_gone": counted_owner_and_outputs_gone,
                "counted_ledger_only_not_provably_pre_ohv3": counted_ledger_only_not_provably_pre_ohv3,
                "DEF_OHV_130_path_served_declared_shims": def130_path_served,
                "DEF_OHV_131_pre_ohv3_projection_rows": def131_pre_ohv3_projection,
                "not_in_clause_owner_installed_output_missing_not_on_path":
                    residue_owner_installed_output_missing,
                "pre_ohv3_evidence": f"{KICKOFF.relative_to(obs.REPO)} ledger_only_rows for this home "
                                     f"({len(pre_ohv3)} ids)"}}


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
               "metric": "(1) rev-3: after a (dry-run projected) prune, rows whose owner and outputs are "
                         "gone + ledger-only rows not provably pre-OHV-3, DEF-OHV-130/131 counted apart; "
                         "(3) installed records whose version disagrees with the checkout while the hash "
                         "agrees; on root and this project home",
               "target": "(1) 0 on both homes, DEF-OHV-130/131 reported separately; (3) 0",
               "clause_2": "not measured here (needs a real prune): artifact-dag graph, ArtifactPruneTest"}
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
        ap = c["after_prune_projection"]
        n1 = len(ap["counted_owner_gone_and_outputs_gone"]) + len(ap["counted_ledger_only_not_provably_pre_ohv3"])
        total += n1 + nver
        kd = c["kickoff_definition"]
        parts.append(f"{label} (1) {n1} [DEF-OHV-130 {len(ap['DEF_OHV_130_path_served_declared_shims'])}, "
                     f"DEF-OHV-131 {len(ap['DEF_OHV_131_pre_ohv3_projection_rows'])}; kickoff def. "
                     f"{len(kd['missing_outputs'])} missing + {len(kd['ledger_only_rows'])} ledger-only "
                     f"before prune], (3) versions {nver}")
    payload["value"] = "; ".join(parts)
    payload["met"] = total == 0
    payload["homes"] = per_home
    return obs.emit(payload, unmeasured=unmeasured)


if __name__ == "__main__":
    sys.exit(main())
