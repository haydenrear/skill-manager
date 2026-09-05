#!/usr/bin/env python3
"""GOAL-migration-lands-on-one-skt -- baseline. READ ONLY.

Metric, per home: (skill-dev-skill present, standalone skill-manager present,
skt count). Target (0, 0, 1) -- the two retired units gone, exactly one plugin
carrying what they used to carry.

The goal's own harness upgrades an old-shape home and reads the triple
afterwards. That upgrade does not exist until OUN-5, so this reads the triple
where it stands. The reading is the part that has to be right now: a migration
whose success criterion is invented after the migration is written grades its
own homework.

`skill-manager` is counted STANDALONE only -- skills/skill-manager, not a
plugin-contained copy of the same name. The whole point of OUN-6 is that the
name survives while the copy moves, so a harness that counted both would read
a successful move as a failure.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unit_graph as g  # noqa: E402

RETIRED_SKILL = "skill-dev-skill"
MOVED_SKILL = "skill-manager"
PLUGIN = "skt"


def triple(home: Path):
    order, units = g.load_home(home)
    standalone = {u.name for u in order if u.addressable}
    contained = {u.name: u.contained_in for u in order if not u.addressable}
    return {
        "home": g.home_label(home),
        "home_path": str(home),
        "skill_dev_skill": int(RETIRED_SKILL in standalone),
        "standalone_skill_manager": int(MOVED_SKILL in standalone),
        "skt": sum(1 for u in order if u.name == PLUGIN and u.kind == "plugin"),
        "skill_manager_contained_in": contained.get(MOVED_SKILL),
    }


def main() -> int:
    homes = ([Path(a) for a in sys.argv[1:] if not a.startswith("-")]
             or g.default_homes())
    rows = [triple(h) for h in homes if (h / "skills").is_dir()
            or (h / "plugins").is_dir()]
    # Only homes that carry skt are in scope: a home that never onboarded the
    # bundled set has nothing to migrate, and counting it as (0,0,0) would
    # read as progress.
    scoped = [r for r in rows if r["skt"] or r["skill_dev_skill"]
              or r["standalone_skill_manager"]]
    done = [r for r in scoped
            if (r["skill_dev_skill"], r["standalone_skill_manager"], r["skt"]) == (0, 0, 1)]
    root = next((r for r in scoped if r["home"] == "root"), None)
    out = {
        "goal": "GOAL-migration-lands-on-one-skt",
        "metric": "(skill-dev-skill present, standalone skill-manager present, skt count) per home",
        "value": (f"root {(root['skill_dev_skill'], root['standalone_skill_manager'], root['skt'])}"
                  if root else "root home not readable")
                 + f"; {len(done)} of {len(scoped)} homes at (0, 0, 1)",
        "target": "(0, 0, 1)",
        "met": bool(scoped) and len(done) == len(scoped),
        "why": ("no migration exists yet -- OUN-5 writes it. This reads the "
                "triple where it stands so the migration is graded against a "
                "number that predates it."),
        "homes_in_scope": len(scoped),
        "homes_at_target": len(done),
        "per_home": scoped,
    }
    print(json.dumps(out, indent=1))
    return 0 if out["met"] else 1


if __name__ == "__main__":
    sys.exit(main())
