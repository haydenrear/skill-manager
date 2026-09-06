#!/usr/bin/env python3
"""GOAL-migration-lands-on-one-skt.

Metric, per home: (skill-dev-skill present, standalone skill-manager present,
skt count). Target (0, 0, 1) -- the two retired units gone, exactly one plugin
carrying what they used to carry.

WHAT IS GRADED: the mechanism, on both paths a home can take.

Every home migrates ITSELF, when it syncs. So the question worth answering is
not "how many of the 38 homes on this laptop are at (0,0,1)" -- that counts
homes nobody has synced yet and homes belonging to checkouts nobody has opened
this month, and it reads "not yet upgraded" and "cannot be upgraded" as the
same number. It also cannot be driven to zero by any amount of correct code.

So the harness bootstraps a home at the PRE-EPIC SHAPE and upgrades it, twice,
by the two routes that exist:

  install <carrier>   -- how a home ACQUIRES skt. Before OUN-5 this was exit 3,
                         refused by the collision gate.
  sync                -- how a home that ALREADY has skt reaches the new shape
                         on its own. No gate to be refused by, so the failure
                         here was silent: both copies of the name exist and the
                         home keeps running the one the upgrade meant to
                         replace.

The real homes are still READ and reported, because they are the population the
mechanism has to work on -- but as context, not as the verdict. A goal that
grades a census cannot be met until somebody has visited every checkout on the
machine, which is not a property of this software.

`skill-manager` is counted STANDALONE only -- skills/skill-manager, not a
plugin-contained copy of the same name. The whole point of OUN-6 is that the
name survives while the copy moves, so a harness that counted both would read
a successful move as a failure.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unit_graph as g  # noqa: E402

REPO = Path(__file__).resolve().parent.parent

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


def probe_upgrade(route):
    """Bootstrap an old-shape home, upgrade it by `route`, and read the triple.

    The old shape is both retired units standing alone. The upgrade is a
    carrier named `skt` carrying a contained skill named `skill-manager` --
    the shape OUN-6 gives the real skt. Before OUN-5 this was exit 3, refused
    by the collision gate; the whole ticket is that the operation which fixes
    the home stopped being the operation the home rejects.

    Returns (triple_reached, detail) -- or (None, why) when it could not be
    measured, which is deliberately not the same as "not met".
    """
    tmp = Path(tempfile.mkdtemp(prefix="oun5-migration-"))
    try:
        home = tmp / "home"
        home.mkdir(parents=True)
        cli = str(REPO / "skill-manager")
        env = {**os.environ, "SKILL_MANAGER_HOME": str(home)}
        for var in ("CLAUDE_HOME", "CODEX_HOME", "GEMINI_HOME"):
            # #311: CODEX_HOME / GEMINI_HOME name the config directory ITSELF,
            # so pointing either at a Skill Manager home makes `install` delete
            # that home's plugins/. The probe that found it was the one that
            # pinned them "to be safe".
            env.pop(var, None)

        for name in (RETIRED_SKILL, MOVED_SKILL):
            unit = tmp / f"standalone-{name}"
            unit.mkdir(parents=True)
            (unit / "SKILL.md").write_text(
                f"---\nname: {name}\ndescription: pre-epic shape\n---\n\nbody\n",
                encoding="utf-8")
            (unit / "skill-manager.toml").write_text(
                f'[skill]\nname = "{name}"\nversion = "0.0.1"\n'
                'description = "pre-epic shape"\n', encoding="utf-8")
            seed = subprocess.run([cli, "install", str(unit), "--yes"], cwd=REPO,
                                  capture_output=True, text=True, timeout=900, env=env)
            if seed.returncode != 0:
                return None, {"stage": f"seeding {name}", "exit": seed.returncode,
                              "said": (seed.stdout + seed.stderr)[-300:]}

        carrier = tmp / PLUGIN
        (carrier / ".claude-plugin").mkdir(parents=True)
        (carrier / ".claude-plugin" / "plugin.json").write_text(
            f'{{"name":"{PLUGIN}","version":"0.0.1","description":"the carrier"}}\n',
            encoding="utf-8")
        (carrier / "skill-manager-plugin.toml").write_text(
            f'[plugin]\nname = "{PLUGIN}"\nversion = "0.0.1"\n'
            'description = "the carrier"\n', encoding="utf-8")
        inner = carrier / "skills" / MOVED_SKILL
        inner.mkdir(parents=True)
        (inner / "SKILL.md").write_text(
            f"---\nname: {MOVED_SKILL}\ndescription: carried\n---\n\nbody\n",
            encoding="utf-8")
        (inner / "skill-manager.toml").write_text(
            f'[skill]\nname = "{MOVED_SKILL}"\nversion = "0.0.1"\n'
            'description = "carried"\n', encoding="utf-8")

        before = triple(home)
        if route == "install":
            run = subprocess.run([cli, "install", str(carrier), "--yes"], cwd=REPO,
                                 capture_output=True, text=True, timeout=900, env=env)
        else:
            # The carrier is already here -- that is the state a project home
            # is in -- and the sync is what has to notice.
            #
            # Built the way a real home REACHES that state, not by planting
            # files. The first version wrote the standalone's tree and its
            # installed/ record straight onto disk, producing a unit with a
            # record and no units.lock entry -- a shape the product never
            # creates. It reached (0,0,1) and exited 1, and the equivalent
            # fixture in the graph node left a home that
            # home.membership.law failed as "LOST [skill-manager] -- a unit
            # nobody removed".
            #
            # The real chronology: the standalone is installed while nothing
            # carries the name; the carrier arrives WITHOUT it, so nothing is
            # due; then the carrier gains the skill the way a git pull of skt
            # delivers it. Only then is the home in the two-copies state.
            plain = tmp / f"{PLUGIN}-without-the-skill"
            (plain / ".claude-plugin").mkdir(parents=True)
            (plain / ".claude-plugin" / "plugin.json").write_text(
                f'{{"name":"{PLUGIN}","version":"0.0.1","description":"the carrier"}}\n',
                encoding="utf-8")
            (plain / "skill-manager-plugin.toml").write_text(
                f'[plugin]\nname = "{PLUGIN}"\nversion = "0.0.1"\n'
                'description = "the carrier"\n', encoding="utf-8")
            unrelated = plain / "skills" / "unrelated-skill"
            unrelated.mkdir(parents=True)
            (unrelated / "SKILL.md").write_text(
                "---\nname: unrelated-skill\ndescription: carried\n---\n\nbody\n",
                encoding="utf-8")
            (unrelated / "skill-manager.toml").write_text(
                '[skill]\nname = "unrelated-skill"\nversion = "0.0.1"\n'
                'description = "carried"\n', encoding="utf-8")
            seed = subprocess.run([cli, "install", str(plain), "--yes"], cwd=REPO,
                                  capture_output=True, text=True, timeout=900, env=env)
            if seed.returncode != 0:
                return None, {"stage": "seeding the carrier", "exit": seed.returncode,
                              "said": (seed.stdout + seed.stderr)[-300:]}
            carried = home / "plugins" / PLUGIN / "skills" / MOVED_SKILL
            carried.mkdir(parents=True, exist_ok=True)
            (carried / "SKILL.md").write_text(
                f"---\nname: {MOVED_SKILL}\ndescription: carried\n---\n\nbody\n",
                encoding="utf-8")
            (carried / "skill-manager.toml").write_text(
                f'[skill]\nname = "{MOVED_SKILL}"\nversion = "0.0.1"\n'
                'description = "carried"\n', encoding="utf-8")
            before = triple(home)
            run = subprocess.run([cli, "sync", MOVED_SKILL], cwd=REPO,
                                 capture_output=True, text=True, timeout=900, env=env)
        after = triple(home)
        # The triple AND the exit code. A route that reaches (0, 0, 1) while
        # the command it ran exits non-zero has not migrated the home cleanly,
        # and grading only the counts is the same mistake this harness was
        # just re-scoped to stop making: measuring the easy half of the
        # question. Caught exactly this way -- the sync route reached the
        # target triple and exited 1.
        orphaned = sorted(
            f.stem for f in (home / "installed").glob("*.json")
            if not f.stem.endswith(".projections")
            and not (home / "skills" / f.stem).is_dir()
            and not (home / "plugins" / f.stem).is_dir()
            and not (home / "docs" / f.stem).is_dir()
            and not (home / "harnesses" / f.stem).is_dir())
        reached = ((after["skill_dev_skill"], after["standalone_skill_manager"],
                    after["skt"]) == (0, 0, 1)
                   and run.returncode == 0
                   # An installed/ record naming a tree the home does not hold
                   # is what home.membership.law calls a LOST unit. A migration
                   # that leaves one has not migrated the home.
                   and not orphaned)
        return reached, {
            "route": route,
            "before": (before["skill_dev_skill"], before["standalone_skill_manager"],
                       before["skt"]),
            "after": (after["skill_dev_skill"], after["standalone_skill_manager"],
                      after["skt"]),
            "exit": run.returncode,
            "orphaned_records": orphaned,
            "name_still_resolves_to": after["skill_manager_contained_in"],
            "product_said": next(
                (ln.strip() for ln in (run.stdout + run.stderr).splitlines()
                 if "retired" in ln), None),
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


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
    if "--fast" in sys.argv:
        routes = {}
    else:
        routes = {r: probe_upgrade(r) for r in ("install", "sync")}
    verdicts = [ok for ok, _ in routes.values()]
    probed = None if not verdicts or None in verdicts else all(verdicts)

    out = {
        "goal": "GOAL-migration-lands-on-one-skt",
        "metric": "(skill-dev-skill present, standalone skill-manager present, skt count) per home",
        "value": (("both routes reach (0, 0, 1)" if probed is True
                   else "a route did not reach (0, 0, 1)" if probed is False
                   else "the mechanism was not probed")
                  + f"; for context {len(done)} of {len(scoped)} existing homes are already "
                  + "there, "
                  + (f"root {(root['skill_dev_skill'], root['standalone_skill_manager'], root['skt'])}"
                     if root else "root home not readable")),
        "target": "(0, 0, 1) by both routes, on a home at the pre-epic shape",
        # THE MECHANISM IS THE VERDICT. Every home migrates itself when it
        # syncs, so what has to be true is that the migration WORKS by both
        # routes -- not that somebody has already visited all 38 homes on this
        # laptop. Grading a census makes the goal a function of how many
        # checkouts happen to be open, which no amount of correct code moves.
        "met": probed is True,
        "routes": {r: detail for r, (_, detail) in routes.items()},
        "why": ("graded on the mechanism, by both routes a home can take: "
                "`install <carrier>` for a home acquiring skt, and `sync` for "
                "a home that already has it. The real homes below are the "
                "population it has to work on and are reported as context; a "
                "home that nobody has synced yet is not a defect, and before "
                "OUN-5 neither route worked at all -- install was refused by "
                "the collision gate and sync was silent."),
        "homes_are_context_not_verdict": True,
        "homes_in_scope": len(scoped),
        "homes_at_target": len(done),
        "per_home": scoped,
    }
    print(json.dumps(out, indent=1))
    return 0 if out["met"] else 1


if __name__ == "__main__":
    sys.exit(main())
