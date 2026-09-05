#!/usr/bin/env python3
"""Run every GOAL harness for an epic and print one ledger.

WHY THIS EXISTS. Each goal had its own shape: goal 1 a walker printing a JSON
blob with counts, goal 2 a test_graph node, goals 3 and 4 sentences in the plan
and nothing to run. "How far are we" therefore meant reading four things in
four formats and doing the comparison by hand -- which is how goal 3 sat at its
0-of-3 BASELINE for six days after the code that fixes it had already merged.
A goal nobody can run is a goal nobody re-measures.

THE CONTRACT every harness here obeys: print ONE JSON object to stdout with at
least {goal, metric, value, target, met}, and exit 0 when met, 1 when not, 2
when it could not measure. `met` is the harness's own verdict; this script only
tabulates. "Could not measure" is deliberately distinct from "not met" -- three
of the four measurements in this epic first came back failing because the
FIXTURE was wrong, and a ledger that scores those as red teaches you to ignore
it.

  python3 scripts/measure_goals.py                     # the current epic
  python3 scripts/measure_goals.py --epic home-boundary-resolution
  python3 scripts/measure_goals.py --with-graphs       # include live graph nodes

EPIC-KEYED, not hardcoded. This script named one epic in a module constant and
listed that epic's harnesses at module scope, so the next epic's answer to
"how far are we" was to rewrite the file -- and the previous epic's ledger
stopped being runnable the moment it was. Both stay runnable now.
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# The interpreter question is not cosmetic: skt imports tomllib, so a harness
# driving it needs >= 3.11, and `python3` on this machine is a shell alias whose
# resolved executable is older. Measured: the goal-4 harness of the previous
# epic first reported "absent" because of that, which is a fixture failure
# wearing a result's clothes.
PY311 = next((p for p in ("python3.13", "python3.12", "python3.11")
              if shutil.which(p)), sys.executable)

EPICS = {
    "one-unit-one-name": {
        "harnesses": [
            {"goal": "GOAL-one-name-one-copy",
             "cmd": [PY311, "scripts/measure_goal_one_name_one_copy.py"]},
            {"goal": "GOAL-a-contained-skill-is-addressable",
             "cmd": [PY311, "scripts/measure_goal_contained_skill_is_addressable.py"],
             # Installs fixtures into a scratch CLONE of this home, so it is
             # slower than the read-only walkers and is skipped by --fast.
             "slow": True},
            {"goal": "GOAL-migration-lands-on-one-skt",
             "cmd": [PY311, "scripts/measure_goal_migration_lands_on_one_skt.py"]},
            {"goal": "GOAL-who-imports-this",
             "cmd": [PY311, "scripts/measure_goal_who_imports_this.py"]},
        ],
        "graphs": [],
    },
    "home-boundary-resolution": {
        "harnesses": [
            {"goal": "GOAL-a-home-runs-its-own-copy",
             "cmd": [sys.executable, "scripts/measure_goal_home_runs_its_own_copy.py"],
             # This one predates the contract and reports counts, not a verdict.
             "adapt": lambda d: {
                 "value": f"{d['unsanctioned_pairs']} unsanctioned pairs over {d['scanned_homes']} homes",
                 "target": "0", "met": d["unsanctioned_pairs"] == 0},
             },
            {"goal": "GOAL-a-failure-names-its-cause",
             "cmd": [sys.executable, "scripts/measure_goal_a_failure_names_its_cause.py"]},
            {"goal": "GOAL-the-real-error-survives",
             "cmd": [PY311, "scripts/measure_goal_the_real_error_survives.py"]},
        ],
        "graphs": [
            {"goal": "GOAL-an-agent-in-its-own-home-can-work",
             "cmd": [sys.executable, "skills/test_graph/scripts/run.py", "checkout-home"],
             "metric": "does the shim launch in a fresh worktree home return a result",
             "target": "succeeds"},
        ],
    },
}

DEFAULT_EPIC = "one-unit-one-name"


def run_json(entry) -> dict:
    proc = subprocess.run(entry["cmd"], cwd=REPO, capture_output=True,
                          text=True, timeout=3600)
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {"goal": entry["goal"], "value": "could not measure",
                "target": "?", "met": None,
                "why": (proc.stdout + proc.stderr).strip()[-400:]}
    # The goal's identity comes from the LEDGER, never from the harness: goal
    # 1's walker predates the contract and reports counts under its own keys.
    data["goal"] = entry["goal"]
    if "adapt" in entry:
        data.update(entry["adapt"](data))
    if proc.returncode == 2:
        data["met"] = None
    return data


def run_graph(entry) -> dict:
    proc = subprocess.run(entry["cmd"], cwd=REPO, capture_output=True,
                          text=True, timeout=3600)
    ok = proc.returncode == 0
    return {"goal": entry["goal"], "metric": entry.get("metric"),
            "value": entry["target"] if ok else "fails",
            "target": entry["target"], "met": ok,
            "why": None if ok else (proc.stdout + proc.stderr).strip()[-400:]}


def main() -> int:
    argv = sys.argv[1:]
    epic = DEFAULT_EPIC
    if "--epic" in argv:
        epic = argv[argv.index("--epic") + 1]
    if epic not in EPICS:
        print(f"unknown epic {epic!r}; known: {', '.join(sorted(EPICS))}",
              file=sys.stderr)
        return 2
    spec = EPICS[epic]

    harnesses = spec["harnesses"]
    if "--fast" in argv:
        harnesses = [h for h in harnesses if not h.get("slow")]
    rows = [run_json(h) for h in harnesses]
    if "--with-graphs" in argv:
        rows += [run_graph(g) for g in spec["graphs"]]

    width = max(len(r["goal"]) for r in rows)
    print(f"epic/{epic} — goal ledger\n")
    for r in rows:
        mark = {True: "MET", False: "NOT MET", None: "UNMEASURED"}[r.get("met")]
        print(f"  {mark:<11} {r['goal']:<{width}}  {r.get('value')}  (target {r.get('target')})")
        if r.get("why"):
            print(f"              why: {r['why'].splitlines()[-1][:120]}")
    met = sum(1 for r in rows if r.get("met") is True)
    unmeasured = sum(1 for r in rows if r.get("met") is None)
    print(f"\n  {met} of {len(rows)} met"
          + (f", {unmeasured} unmeasured" if unmeasured else ""))
    if "--json" in argv:
        print(json.dumps({"epic": epic, "goals": rows}, indent=1))
    # UNMEASURED is not success. Exit 2 so a caller cannot read "no failures"
    # off a run that could not answer.
    return 0 if met == len(rows) else (2 if unmeasured else 1)


if __name__ == "__main__":
    sys.exit(main())
