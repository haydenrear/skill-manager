#!/usr/bin/env python3
"""GOAL-one-name-one-copy -- baseline walker. READ ONLY.

Metric: (home, unit-name) pairs that resolve to two distinct unit roots.

Reported per pair, never as a bare total: one bad pair is the whole defect,
and a count alone hides which home and which two roots.

TWO POPULATIONS, and the distinction is the whole baseline:

  LIVE     both roots are addressable TODAY, so a `unit:` name in a
           skill-import already has two answers and the resolver picks one by
           the order of installedRoot's four branches.
  LATENT   at least one root is a plugin-contained skill, which installedRoot
           cannot reach at all. The name is claimed twice on disk but only one
           claim can be resolved, so nothing breaks -- yet. OUN-1 makes
           contained skills addressable, and every latent pair becomes live
           the moment it lands.

That is why the goal's baseline reads 0 VACUOUSLY. Reporting only the live
count would say "already met" about a condition nothing can currently violate.
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
TMP_PREFIX = "oun2-collision-gate-"


def probe_gate():
    """Does the installer REFUSE a planted collision?

    The second half of this goal, and the half a walker cannot answer. The
    target is "0, non-vacuously, WITH THE GATE REFUSING A PLANTED COLLISION" —
    counting pairs shows there is nothing wrong right now; only planting one
    shows the product would not let it happen.

    Plants a plugin carrying a skill named after a unit that is already in a
    scratch clone, installs it, and reads the verdict. Returns (refused, why).
    """
    src = Path(os.environ.get("SKILL_MANAGER_HOME") or (REPO / ".skill-manager"))
    if not (src / "skills").is_dir():
        return None, f"source home {src} has no skills/ to collide with"
    victim = next((p.name for p in sorted((src / "skills").iterdir())
                   if p.is_dir() and not p.name.startswith(".")), None)
    if victim is None:
        return None, f"{src} holds no standalone skill to collide with"

    tmp = Path(tempfile.mkdtemp(prefix=TMP_PREFIX))
    try:
        home = tmp / "home"
        clone = subprocess.run([str(REPO / "skill-manager"), "home", "clone",
                                "--from", str(src), "--to", str(home)],
                               cwd=REPO, capture_output=True, text=True, timeout=1800)
        if clone.returncode != 0:
            return None, "home clone failed: " + (clone.stdout + clone.stderr)[-300:]

        plugin = tmp / "planted-plugin"
        (plugin / ".claude-plugin").mkdir(parents=True)
        (plugin / ".claude-plugin" / "plugin.json").write_text(
            '{"name":"planted-plugin","version":"0.0.1",'
            '"description":"a planted collision"}\n', encoding="utf-8")
        (plugin / "skill-manager-plugin.toml").write_text(
            '[plugin]\nname = "planted-plugin"\nversion = "0.0.1"\n'
            'description = "a planted collision"\n', encoding="utf-8")
        inner = plugin / "skills" / victim
        inner.mkdir(parents=True)
        (inner / "SKILL.md").write_text(
            f"---\nname: {victim}\ndescription: planted collision\n---\n\nbody\n",
            encoding="utf-8")
        (inner / "skill-manager.toml").write_text(
            f'[skill]\nname = "{victim}"\nversion = "0.0.1"\n'
            'description = "planted collision"\n', encoding="utf-8")

        run = subprocess.run([str(REPO / "skill-manager"), "install", str(plugin), "--yes"],
                             cwd=REPO, capture_output=True, text=True, timeout=900,
                             env={**os.environ, "SKILL_MANAGER_HOME": str(home)})
        out = run.stdout + run.stderr
        landed = (home / "plugins" / "planted-plugin").is_dir()
        refused = run.returncode != 0 and not landed
        return refused, {
            "collided_with": victim,
            "exit": run.returncode,
            "plugin_landed_anyway": landed,
            "product_said": next((ln.strip() for ln in out.splitlines()
                                  if "refusing to install plugin" in ln), None),
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    homes = ([Path(a) for a in sys.argv[1:] if not a.startswith("-")]
             or g.default_homes())
    live, latent = [], []
    for home in homes:
        try:
            order, units = g.load_home(home)
        except OSError:
            continue
        for name, claims in sorted(units.items()):
            if len(claims) < 2:
                continue
            row = {"home": g.home_label(home), "home_path": str(home),
                   "name": name,
                   "roots": [{"kind": u.kind,
                              "root": str(u.root),
                              "addressable_today": u.addressable,
                              "contained_in": u.contained_in}
                             for u in claims]}
            (live if sum(u.addressable for u in claims) > 1 else latent).append(row)

    out = {
        "goal": "GOAL-one-name-one-copy",
        "metric": "(home, unit-name) pairs that resolve to two distinct unit roots",
        "scanned_homes": len(homes),
        "live_pairs": len(live),
        "latent_pairs": len(latent),
        "value": f"{len(live)} live, {len(latent)} latent over {len(homes)} homes",
        "target": "0 live AND 0 latent",
        "vacuous": len(live) == 0 and len(latent) > 0,
        "why": ("0 live is VACUOUS while contained skills are unaddressable: "
                "the latent pairs cannot collide because only one of their two "
                "roots can be named. OUN-1 makes them live."
                if len(live) == 0 and latent else None),
        "live": live,
        "latent": latent,
    }
    # THE GOAL HAS TWO HALVES AND THIS MEASURES ONE.
    #
    # Target: "0, non-vacuously, WITH THE GATE REFUSING A PLANTED COLLISION."
    # Counting pairs answers the first clause. Nothing here installs a planted
    # collision, so nothing here can answer the second, and OUN-2 is the
    # ticket that builds the refusal.
    #
    # So the verdict is UNMEASURED, not MET, while zero pairs stand without a
    # gate. Reporting MET would repeat exactly the mistake OUN-0 was written
    # to catch: a number that reads as success because nothing can currently
    # violate it. UNMEASURED is deliberately loud — the ledger prints it and
    # exits 2 — so OUN-2 replacing this with a real probe is a visible task
    # rather than a hardcoded `False` somebody has to remember to flip.
    out["pairs_clean"] = not live and not latent
    if live or latent:
        out["gate"] = {"refuses_a_planted_collision": None,
                       "why": "not probed: there is already a live pair to fix"}
        out["met"] = False
        print(json.dumps(out, indent=1))
        return 1

    # THE SECOND HALF, and it is what makes the first non-vacuous. Zero pairs
    # says nothing is wrong now; only a planted collision shows the product
    # would not let one happen.
    refused, detail = probe_gate()
    out["gate"] = {"refuses_a_planted_collision": refused, "detail": detail}
    if refused is None:
        out["met"] = None
        out["why"] = ("no pair resolves to two roots, but the gate could not be "
                      "probed, so the number is not yet non-vacuous: "
                      + str(detail))
        print(json.dumps(out, indent=1))
        return 2
    out["met"] = bool(refused)
    out["value"] = out["value"] + ("; gate refuses a planted collision"
                                   if refused else "; GATE DID NOT REFUSE")
    out["why"] = None if refused else (
        "no pair resolves to two roots, but the installer accepted a planted "
        "collision — which is how the pairs got to zero, not proof they stay there")
    print(json.dumps(out, indent=1))
    return 0 if out["met"] else 1


if __name__ == "__main__":
    sys.exit(main())
