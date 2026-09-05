#!/usr/bin/env python3
"""GOAL-who-imports-this -- baseline. READ ONLY.

Metric: can a SINGLE command answer "what in this home imports X", covering
`skill-imports` AND `skill_references`, transitively, without looping?

Two halves, and only the first is the goal:

  (1) IS THERE A COMMAND?  Probed against the real CLIs -- `skill-manager deps`
      and `skt` -- by asking them for their own help. Today `deps` walks
      FORWARD only ("the transitive dependency tree of an installed unit") and
      `skt` has no deps subcommand at all, so the answer is no. OUN-3 makes it
      yes; this probe is what flips.

  (2) WHAT IS THE ANSWER ANYWAY?  Computed here, from the unit trees, so the
      command OUN-3 ships has something to be checked against. The numbers in
      the epic's purpose -- 6 importers in the project home, 8 in root -- were
      produced by hand with recursive grep. A number produced by hand is not a
      baseline; it is an anecdote. This reproduces them mechanically.

The second half is NOT a shipped feature and must not become one: it lives in
a measurement script precisely so that nobody mistakes it for the product's
answer. The product's answer is what OUN-3 adds.
"""
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unit_graph as g  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
TARGET = "skill-manager"

# A reverse-edge flag would have to say so in its own help. Matching on help
# text is weak evidence in general; here it is the right strength, because the
# claim being measured is "no such command exists", and a command that existed
# would document itself.
REVERSE_HINTS = ("--who-imports", "who-imports", "reverse", "importers",
                 "--rdeps", "who imports")


def probe(cmd):
    try:
        p = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True,
                           timeout=300)
    except (OSError, subprocess.SubprocessError) as ex:
        return {"cmd": " ".join(cmd), "available": False, "reverse": False,
                "why": str(ex)}
    text = (p.stdout + p.stderr).lower()
    return {"cmd": " ".join(cmd),
            "available": p.returncode == 0 or bool(text.strip()),
            "reverse": any(h in text for h in REVERSE_HINTS)}


def main() -> int:
    probes = [probe([str(REPO / "skill-manager"), "deps", "--help"]),
              probe(["skt", "--help"]),
              probe(["skt", "deps", "--help"])]
    has_command = any(p["reverse"] for p in probes)

    homes = ([Path(a) for a in sys.argv[1:] if not a.startswith("-")]
             or g.default_homes())
    rows = []
    for home in homes:
        try:
            order, units = g.load_home(home)
        except OSError:
            continue
        if TARGET not in units:
            continue
        index = g.installed_index(home)
        by_name = g.importers(order, TARGET, ("imports",), index)
        by_coord = g.importers(order, TARGET, ("references",), index)
        both = g.importers(order, TARGET, index=index)
        trans = g.transitive_importers(order, TARGET, index=index)
        rows.append({
            "home": g.home_label(home),
            "home_path": str(home),
            "direct": len(both),
            "direct_units": sorted(both),
            "by_skill_imports": len(by_name),
            "by_skill_references": len(by_coord),
            "only_by_reference": sorted(set(by_coord) - set(by_name)),
            "transitive": len(trans),
            "transitive_units": trans,
        })

    counts = sorted({r["direct"] for r in rows})
    out = {
        "goal": "GOAL-who-imports-this",
        "metric": ("can a single command answer it for " + TARGET +
                   ", covering skill-imports AND skill_references, transitively"),
        "value": ("a command exists" if has_command else "no such command"),
        "target": "one command, both mechanisms, transitive, terminates on cycles",
        "met": has_command,
        "probes": probes,
        "target_unit": TARGET,
        "homes_reporting": len(rows),
        "distinct_direct_counts": counts,
        "why": (None if has_command else
                "`skill-manager deps` walks forward only and `skt` has no deps "
                "subcommand; the reverse edge is recorded in no file, so the "
                "question can only be answered by re-reading every unit tree."),
        "per_home": rows,
    }
    print(json.dumps(out, indent=1))
    return 0 if has_command else 1


if __name__ == "__main__":
    sys.exit(main())
