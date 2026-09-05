#!/usr/bin/env python3
"""GOAL-a-contained-skill-is-addressable -- baseline probe.

Metric: does `unit: unit-authoring` resolve from a markdown skill-import?

THE PRODUCT ANSWERS, NOT THIS SCRIPT. Two fixture units are installed into a
scratch home and the CLI's own validation is the verdict:

  CONTROL  imports `unit: skill-manager` -- a standalone skill. Must install
           clean. Without it a broken fixture is indistinguishable from the
           defect, which is how three measurements in the previous epic first
           came back red for the wrong reason.
  PROBE    imports `unit: unit-authoring` -- a skill that EXISTS in the same
           home, at plugins/skt/skills/unit-authoring, and is contained by a
           plugin. MarkdownImportValidator.installedRoot searches plugins/,
           harnesses/, docs/ and skills/ and never descends into a plugin, so
           the product reports it missing.

The scratch home is a `home clone` of a real one, never a home the operator
works in: the probe installs units, and installing into a live home would be a
write this measurement has no business making.

  measure_goal_contained_skill_is_addressable.py [--home DIR] [--keep]

`--home` reuses a scratch home from an earlier run (the clone is the slow
part). It must not be a home anyone works in; the script refuses the operator
root and the repository's project home.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unit_graph as g  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
TMP_PREFIX = "oun0-addressability-"
CLI = REPO / "skill-manager"
CONTAINED = "unit-authoring"
STANDALONE = "skill-manager"

CONTROL_MD = """---
name: standalone-addressability-control
description: Control unit. Imports a standalone skill by name; this is expected to resolve.
skill-imports:
  - unit: {unit}
    path: references/skill-imports.md
    reason: Control edge naming a standalone unit, to prove the probe measures addressability.
---

# standalone-addressability-control
"""

PROBE_MD = """---
name: contained-addressability-probe
description: Probe unit. Imports a plugin-contained skill by name to see whether the name resolves.
skill-imports:
  - unit: {unit}
    path: SKILL.md
    reason: Probes whether a plugin-contained skill is addressable by name.
---

# contained-addressability-probe
"""

TOML = '[skill]\nname = "{name}"\nversion = "0.0.1"\ndescription = "Fixture for GOAL-a-contained-skill-is-addressable."\n'


def fail(why, **extra):
    out = {"goal": "GOAL-a-contained-skill-is-addressable",
           "metric": f"does `unit: {CONTAINED}` resolve from a markdown skill-import",
           "value": "could not measure", "target": "yes", "met": None,
           "why": why}
    out.update(extra)
    print(json.dumps(out, indent=1))
    return 2


def write_fixture(dst: Path, name: str, body: str, unit: str) -> Path:
    d = dst / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "SKILL.md").write_text(body.format(unit=unit), encoding="utf-8")
    (d / "skill-manager.toml").write_text(TOML.format(name=name), encoding="utf-8")
    return d


def install(home: Path, unit_dir: Path):
    # ALL the home axes, not just SKILL_MANAGER_HOME. Install registers the
    # unit with the agent CLIs, and SKILL_MANAGER_HOME alone does not move
    # where those writes land -- CLAUDE_HOME is the parent of .claude/. The
    # clone already carries its own .claude/.codex/.gemini; this points the
    # child at them instead of the operator's.
    #
    # CLAUDE_CONFIG_DIR is deliberately NOT set: it silently relocates the
    # keychain slot, and a probe has no business touching credentials.
    env = {**os.environ,
           "SKILL_MANAGER_HOME": str(home),
           "CLAUDE_HOME": str(home),
           "CODEX_HOME": str(home),
           "GEMINI_HOME": str(home)}
    proc = subprocess.run([str(CLI), "install", str(unit_dir), "--yes"],
                          cwd=REPO, capture_output=True, text=True,
                          timeout=900, env=env)
    return proc.returncode, (proc.stdout + proc.stderr)


def sweep_stale():
    """Remove temp homes an earlier run was killed before cleaning up.

    A clone is ~460 MB. This repository has already lost 1.4 GB to leaked
    per-run temp homes once; a harness that makes one every invocation should
    take its own litter with it, not only on the happy path.
    """
    root = Path(tempfile.gettempdir())
    cutoff = time.time() - 2 * 3600
    removed = []
    try:
        for d in root.glob(TMP_PREFIX + "*"):
            # Age-gated, so two concurrent runs do not delete each other's
            # scratch out from under them.
            if d.is_dir() and d.stat().st_mtime < cutoff:
                shutil.rmtree(d, ignore_errors=True)
                removed.append(str(d))
    except OSError:
        pass
    return removed


def main() -> int:
    args = sys.argv[1:]
    keep = "--keep" in args
    given = None
    if "--home" in args:
        i = args.index("--home") + 1
        if i >= len(args):
            return fail("--home needs a directory")
        given = Path(args[i]).expanduser()

    # PORTABLE, not a hardcoded path. The protected set is every home this
    # machine actually has -- the operator root, this checkout's, every
    # sibling checkout's and worktree's -- because the probe INSTALLS units,
    # and "is this a home someone works in" is not a question to answer with
    # one absolute path baked into a script that ships in a public repo.
    if given:
        working = {h.resolve() for h in g.default_homes()}
        try:
            target = given.resolve()
        except OSError:
            target = given
        if target in working:
            return fail(f"refusing to install fixtures into {given}: that is a "
                        "home someone works in. Pass a scratch home or none "
                        "at all.")

    source = Path(os.environ.get("SKILL_MANAGER_HOME") or (REPO / ".skill-manager"))
    if not (source / "plugins").is_dir():
        return fail(f"source home {source} has no plugins/ to clone from")

    # The probe is only meaningful if the contained skill actually exists on
    # disk. Otherwise "missing unit" is true for a boring reason.
    contained_roots = sorted((source / "plugins").glob(f"*/skills/{CONTAINED}"))
    if not contained_roots:
        return fail(f"no plugin in {source} contains a skill named {CONTAINED}; "
                    "the probe would be measuring an absent unit, not an "
                    "unaddressable one")

    swept = sweep_stale()
    tmp = None
    home = given
    try:
        if home is None:
            tmp = Path(tempfile.mkdtemp(prefix=TMP_PREFIX))
            home = tmp / "home"
            proc = subprocess.run([str(CLI), "home", "clone", "--from", str(source),
                                   "--to", str(home)], cwd=REPO,
                                  capture_output=True, text=True, timeout=1800)
            if proc.returncode != 0:
                return fail("home clone failed",
                            detail=(proc.stdout + proc.stderr)[-600:])

        # Fixtures always go somewhere disposable, never beside a home the
        # caller named: --home points at a home, not at a scratch parent.
        fxroot = tmp or Path(tempfile.mkdtemp(prefix=TMP_PREFIX))
        fx = fxroot / "fixtures"
        control = write_fixture(fx, "standalone-addressability-control",
                                CONTROL_MD, STANDALONE)
        probe = write_fixture(fx, "contained-addressability-probe",
                              PROBE_MD, CONTAINED)

        c_rc, c_out = install(home, control)
        if c_rc != 0:
            return fail("the CONTROL fixture failed to install, so the probe "
                        "cannot separate the defect from a broken fixture",
                        control_exit=c_rc, detail=c_out[-800:])

        p_rc, p_out = install(home, probe)
        resolves = p_rc == 0 and "missing unit" not in p_out
        evidence = next((ln.strip() for ln in p_out.splitlines()
                         if "missing unit" in ln), None)

        out = {
            "goal": "GOAL-a-contained-skill-is-addressable",
            "metric": f"does `unit: {CONTAINED}` resolve from a markdown skill-import",
            "value": "yes" if resolves else "no",
            "target": "yes",
            "met": resolves,
            "contained_root": str(contained_roots[0]),
            "control": {"unit": STANDALONE, "exit": c_rc, "resolved": True},
            "probe": {"unit": CONTAINED, "exit": p_rc, "resolved": resolves},
            "product_said": evidence,
            "swept_stale_temp_homes": len(swept),
            "why": None if resolves else (
                f"{CONTAINED} exists at {contained_roots[0]} in the very home "
                "the import was validated against. installedRoot has four "
                "branches -- plugins/, harnesses/, docs/, skills/ -- and none "
                "descends into a plugin's contained skills."),
        }
        print(json.dumps(out, indent=1))
        return 0 if resolves else 1
    finally:
        if not keep:
            for d in {tmp, locals().get("fxroot")}:
                if d:
                    shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
