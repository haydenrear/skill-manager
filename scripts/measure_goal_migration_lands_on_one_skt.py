#!/usr/bin/env python3
"""GOAL-migration-lands-on-one-skt.

Metric, per home: (skill-dev-skill present, standalone skill-manager present,
skt count). Target (0, 0, 1) -- the two retired units gone, exactly one plugin
carrying what they used to carry.

Two halves, and they answer different questions.

READ-ONLY, over the real homes on this machine: where does the triple stand?
That is the number the goal is graded against, and it does not move until skt
actually carries the skill-manager skill (OUN-6) and the homes are upgraded.

THE PROBE, from OUN-5 onward: bootstrap a scratch home at the PRE-EPIC SHAPE
and upgrade it. This is the goal's declared harness -- "bootstrap a home at
the pre-epic shape, upgrade it, and read the three counts" -- and it is the
half that says whether the migration WORKS, as opposed to whether it has been
run. Counting real homes alone would read "not yet upgraded" and "cannot be
upgraded" as the same number, which is exactly the confusion OUN-5 exists to
end: before it, the upgrade was REFUSED by the collision gate.

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


def probe_upgrade():
    """Bootstrap an old-shape home, upgrade it, and read the triple.

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
        upgrade = subprocess.run([cli, "install", str(carrier), "--yes"], cwd=REPO,
                                 capture_output=True, text=True, timeout=900, env=env)
        after = triple(home)
        reached = (after["skill_dev_skill"], after["standalone_skill_manager"],
                   after["skt"]) == (0, 0, 1)
        return reached, {
            "before": (before["skill_dev_skill"], before["standalone_skill_manager"],
                       before["skt"]),
            "after": (after["skill_dev_skill"], after["standalone_skill_manager"],
                      after["skt"]),
            "upgrade_exit": upgrade.returncode,
            "name_still_resolves_to": after["skill_manager_contained_in"],
            "product_said": next(
                (ln.strip() for ln in (upgrade.stdout + upgrade.stderr).splitlines()
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
    probed, probe_detail = (None, "skipped (--fast)") if "--fast" in sys.argv \
        else probe_upgrade()

    out = {
        "goal": "GOAL-migration-lands-on-one-skt",
        "metric": "(skill-dev-skill present, standalone skill-manager present, skt count) per home",
        "value": (f"root {(root['skill_dev_skill'], root['standalone_skill_manager'], root['skt'])}"
                  if root else "root home not readable")
                 + f"; {len(done)} of {len(scoped)} homes at (0, 0, 1)",
        "target": "(0, 0, 1)",
        # The real homes decide the goal. The probe decides whether the
        # mechanism works, and it is reported beside them rather than folded
        # in: an upgrade that has not been RUN and an upgrade that CANNOT run
        # are different states, and only the second is a defect.
        "met": bool(scoped) and len(done) == len(scoped),
        "upgrade_works_on_a_fixture": probed,
        "probe": probe_detail,
        "why": ("the real homes are graded on the triple they hold. From "
                "OUN-5 the probe additionally bootstraps a pre-epic home and "
                "upgrades it -- before that ticket the upgrade was refused by "
                "the collision gate, so this half could not be answered at "
                "all. The real homes move when skt carries the skill (OUN-6) "
                "and they are upgraded."),
        "homes_in_scope": len(scoped),
        "homes_at_target": len(done),
        "per_home": scoped,
    }
    print(json.dumps(out, indent=1))
    return 0 if out["met"] else 1


if __name__ == "__main__":
    sys.exit(main())
