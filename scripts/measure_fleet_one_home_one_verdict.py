#!/usr/bin/env python3
"""one-home-one-verdict fleet pass, judged by ONE named build (OHV-8, #358).

Read-only. For every home on this machine (root + every <dir>/.skill-manager
under ~/IdeaProjects to depth 2, minus the excluded worktrees), with the build
named by SKILL_MANAGER_MEASURE_CLI (default: this checkout's ./skill-manager):

  GOAL-one-verdict (3)          `home verify --home` rc, `home repair --home --json`
                                rc + findings; a home counts when verify exits 0
                                while repair reports damage.
  GOAL-no-own-home-path (3)     bin/cli entries spelling the home's own path
                                (scripts/home_observations.py), split into
                                running-line / shebang-only / comment-only.
  GOAL-one-marketplace-identity (3), corrected for DEF-OHV-160: shapes counted
                                from `home repair --json` MARKETPLACE_* kinds, which
                                read each home's OWN agent files; the old file-read
                                harness's shapes are reported beside them.

Never --fix, --record, --ack or sync. Kickoff's measure_fleet.py used the
released CLI and merged stdout+stderr; this keeps them apart and names its build.

  SKILL_MANAGER_MEASURE_CLI=<cli> python3.12 scripts/measure_fleet_one_home_one_verdict.py <out.json>
"""
from __future__ import annotations

import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import home_observations as obs  # noqa: E402
import measure_goal_no_own_home_path as nohp  # noqa: E402
import measure_goal_one_marketplace_identity as mpi  # noqa: E402

EXCLUDE = ("/wt-ohv-", "/wt-epic-one-home-one-verdict/")
SHAPE_OF_KIND = {
    "MARKETPLACE_REGISTERED_UNDER_ANOTHER_NAME": 1,
    "MARKETPLACE_IDENTITY_UNREGISTERED": 2,
    "MARKETPLACE_IDENTITY_COPIED": 3,
    "FOREIGN_MARKETPLACE_REGISTRATION": 4,
}


def cli() -> str:
    return os.environ.get("SKILL_MANAGER_MEASURE_CLI") or str(obs.REPO / "skill-manager")


def judge(home: Path, checkout: Path, names: dict, known: dict) -> dict:
    c = cli()
    row = {"home": str(home), "checkout": str(checkout)}
    rc, out, err = obs.run([c, "home", "verify", "--home", str(home)], env_home=home, timeout=900)
    row["verify_rc"] = rc
    row["verify_tail"] = (out.strip().splitlines() or [""])[-3:]
    rc, out, err = obs.run([c, "home", "repair", "--home", str(home), "--json"], env_home=home, timeout=900)
    doc = obs.json_from_stdout(out)
    row["repair_rc"] = rc
    row["repair_build"] = doc.get("build") if isinstance(doc, dict) else None
    if isinstance(doc, dict):
        fs = doc.get("findings", [])
        row["repair_findings"] = len(fs)
        kinds = {}
        for f in fs:
            kinds[f.get("kind")] = kinds.get(f.get("kind"), 0) + 1
        row["repair_kinds"] = kinds
        row["marketplace_findings"] = [{"kind": f.get("kind"), "subject": f.get("subject")}
                                       for f in fs if f.get("kind") in SHAPE_OF_KIND]
    else:
        row["repair_findings"] = None
        row["repair_kinds"] = None
        row["repair_unreadable"] = (out + "\n" + err).strip()[-400:]
    entries = obs.bin_cli_own_path_entries(home)
    sp = obs.spellings(home)
    for e in entries:
        where = nohp._where_spelled(home / "bin" / "cli" / e["entry"], sp)
        e["where"] = where or "running-line"
    row["own_path_bin_cli"] = entries
    old = mpi.shapes(home, checkout, names, known)
    row["old_harness_shapes"] = old
    corrected = {n: 0 for n in (1, 2, 3, 4)}
    for f in row.get("marketplace_findings") or []:
        corrected[SHAPE_OF_KIND[f["kind"]]] += 1
    row["repair_marketplace_shapes"] = corrected if row["repair_findings"] is not None else None
    return row


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    out_path = Path(sys.argv[1])
    all_homes = obs.discover_fleet()
    homes = [(h, c) for h, c in all_homes if not any(x in os.path.realpath(h) + "/" for x in EXCLUDE)]
    excluded = [str(h) for h, c in all_homes if (h, c) not in homes]
    try:
        known = json.loads((obs.HOME / ".claude/plugins/known_marketplaces.json").read_text())
    except (OSError, json.JSONDecodeError):
        known = {}
    names = {str(h): obs.generated_marketplace_name(h) for h, _ in all_homes}
    rc, ver, _ = obs.run([cli(), "--version"])
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(lambda hc: judge(hc[0], hc[1], names, known), homes))

    def count(f):
        return sum(1 for r in rows if f(r))

    damaged = lambda r: r["repair_findings"] is None or r["repair_rc"] != 0 or (r["repair_findings"] or 0) > 0
    summary = {
        "build": ver.strip().splitlines()[:2],
        "homes": len(rows),
        "excluded": excluded,
        "verify_rc0": count(lambda r: r["verify_rc"] == 0),
        "repair_clean": count(lambda r: r["repair_rc"] == 0 and r["repair_findings"] == 0),
        "repair_unreadable": count(lambda r: r["repair_findings"] is None),
        "GOAL_one_verdict_3_verify0_while_repair_damaged": count(lambda r: r["verify_rc"] == 0 and damaged(r)),
        "verify_nonzero_while_repair_clean": count(lambda r: r["verify_rc"] != 0 and not damaged(r)),
        "GOAL_no_own_home_path_3_homes_with_any_own_path_entry": count(lambda r: r["own_path_bin_cli"]),
        "GOAL_no_own_home_path_3_homes_with_running_line_entry": count(
            lambda r: any(e["where"] == "running-line" for e in r["own_path_bin_cli"])),
        "GOAL_marketplace_3_repair_kinds": {
            "shape1": count(lambda r: (r["repair_marketplace_shapes"] or {}).get(1)),
            "shape2": count(lambda r: (r["repair_marketplace_shapes"] or {}).get(2)),
            "shape3": count(lambda r: (r["repair_marketplace_shapes"] or {}).get(3)),
            "shape4": count(lambda r: (r["repair_marketplace_shapes"] or {}).get(4)),
            "any_1_3_4": count(lambda r: any((r["repair_marketplace_shapes"] or {}).get(n) for n in (1, 3, 4))),
        },
        "GOAL_marketplace_3_old_harness": {
            "shape1": count(lambda r: r["old_harness_shapes"]["shape1"]),
            "shape3": count(lambda r: r["old_harness_shapes"]["shape3"]),
            "shape4": count(lambda r: r["old_harness_shapes"]["shape4"]),
            "any_1_3_4": count(lambda r: mpi.present(r["old_harness_shapes"])),
        },
        "repair_kind_homes": {},
    }
    for r in rows:
        for k in (r["repair_kinds"] or {}):
            summary["repair_kind_homes"][k] = summary["repair_kind_homes"].get(k, 0) + 1
    out_path.write_text(json.dumps({"summary": summary, "homes": rows}, indent=2))
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
