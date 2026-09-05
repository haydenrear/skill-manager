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
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import unit_graph as g  # noqa: E402


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
    out["gate"] = {"refuses_a_planted_collision": None, "probed_by": "OUN-2"}
    if live or latent:
        out["met"] = False
        print(json.dumps(out, indent=1))
        return 1
    out["met"] = None
    out["why"] = ("no (home, unit-name) pair resolves to two roots, but the "
                  "other half of this goal — an installer that REFUSES a "
                  "planted collision — has no probe yet. OUN-2 adds it.")
    print(json.dumps(out, indent=1))
    return 2


if __name__ == "__main__":
    sys.exit(main())
