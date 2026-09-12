#!/usr/bin/env python3
"""Pre-release cross-checks, chosen from where the bugs actually cluster.

NOT a second test suite. Every check here compares a GUARD'S VERDICT against
an INDEPENDENT OBSERVATION of the same fact, because that is the shape 9 of
this epic's 22 deferred findings take:

  6x  a guard whose rule misses its own case
      DEF-OUN-003, DEF-283, DEF-OUN-010, DEF-OUN-013, DEF-OUN-014, DEF-OUN-018
      -- `home repair` reported 0 findings on a home holding 17; `home drift`
      and `home verify` both exit 0 on a stale home; the collision gate
      refused installs for an unrelated reason and masked a halt that could
      never fire.
  3x  two records of one fact, disagreeing
      DEF-OUN-013, DEF-OUN-015, DEF-OUN-017
      -- BundledSkills vs UnitSupersession; a home's record vs its checkout;
      the artifacts ledger vs the home.

A unit test cannot catch either shape, because both are about a rule being
too narrow rather than a branch being wrong: the code does exactly what it
says, and what it says is not the question. So these run against REAL homes
and read the answer from somewhere else.

Exit 0 when every check agrees, 1 when any disagreement is found.
"""
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def homes() -> list[Path]:
    return [p for p in (Path.home() / ".skill-manager", REPO / ".skill-manager")
            if (p / "installed").is_dir()]


def cli(home: Path) -> Path:
    return home / "bin" / "cli" / "skill-manager"


def run(argv: list[str], timeout: int = 600) -> tuple[int, str]:
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, str(exc)


def check_census_matches_disk(home: Path) -> dict:
    """R1 — `artifacts list` vs what is actually there.

    skill-manager#334: an uninstall removes a unit's files and keeps its
    LEDGER rows, so the census names artifacts that do not exist and nothing
    ever reaps them. Measured on a real home: 59 reported, 55 present.
    """
    # NO `--home` FLAG. `artifacts list` does not take one -- DEF-121's
    # lesson, which cost a remedy that had never been run: `--home` is a flag
    # of SOME commands, not of the CLI. The home's own pinned shim binds the
    # home it lives in, which is the addressing that always works.
    rc, out = run([str(cli(home)), "artifacts", "list", "--json"])
    if rc != 0:
        return {"check": "census matches disk", "home": str(home),
                "measured": False, "why": f"artifacts list exited {rc}"}
    try:
        doc = json.loads(out[out.index("{"):])
    except (ValueError, json.JSONDecodeError) as exc:
        return {"check": "census matches disk", "home": str(home),
                "measured": False, "why": f"unreadable json: {exc}"}
    phantom = []
    for a in doc.get("artifacts", []):
        for o in a.get("outputs", []):
            if o.get("scope") != "home":
                continue
            if o.get("presence") == "missing":
                phantom.append(a.get("id"))
    # A row with NO outputs at all and origin `ledger` is the #334 shape: the
    # ledger remembers an artifact whose outputs it can no longer name.
    orphan = [a.get("id") for a in doc.get("artifacts", [])
              if a.get("origin") == "ledger" and not a.get("outputs")]
    return {"check": "census matches disk", "home": str(home), "measured": True,
            "agrees": not phantom and not orphan,
            "declared": doc.get("summary", {}).get("artifacts"),
            "phantom_outputs": phantom, "ledger_only_rows": orphan}


def check_repair_agrees_with_copy_readiness(home: Path) -> dict:
    """R2 — `home repair` clean vs the copied-home harness.

    DEF-OUN-018 exactly: repair's rule asks "does this reach ANOTHER home",
    and a shim naming its OWN home does not, so a home that cannot survive
    being copied was reported clean. The harness copies and looks.
    """
    rc, out = run([str(cli(home)), "home", "repair", "--home", str(home)])
    # READ THE EXIT CODE, NOT THE PROSE. The clean line is "nothing in X IS
    # DAMAGED in a way this command knows about", so a substring test for
    # "is damaged" calls a clean home damaged -- a checker making the same
    # class of mistake it exists to catch. Caught by this script disagreeing
    # with a home it had just repaired to zero findings.
    repair_clean = rc == 0
    probe = REPO / "scripts" / "measure_goal_a_home_survives_being_copied.py"
    prc, pout = run([sys.executable, str(probe), str(home)], timeout=1200)
    try:
        doc = json.loads(pout[pout.index("{"):])
        copyable = doc.get("met")
        detail = {k: v.get("met") for k, v in doc.get("properties", {}).items()}
    except (ValueError, json.JSONDecodeError):
        return {"check": "repair agrees with copy-readiness", "home": str(home),
                "measured": False, "why": "probe produced no json"}
    return {"check": "repair agrees with copy-readiness", "home": str(home),
            "measured": True,
            # AN IMPLICATION, NOT AN EQUIVALENCE. `home repair` covers more
            # than copy-readiness -- misanchored links, dangling pins -- so it
            # may legitimately be red on a home that copies fine. What must
            # never happen is the direction DEF-OUN-018 found: repair says
            # CLEAN and the home cannot survive a copy.
            "agrees": (not repair_clean) or bool(copyable),
            "home_repair_says_clean": repair_clean,
            "copy_probe_says_ready": copyable, "properties": detail}


def check_record_matches_checkout(home: Path) -> dict:
    """R3 — every installed record vs the store it describes.

    The `record-disagrees-with-checkout` state `skt check` exists to report.
    Read here from the files directly, so a check that stopped checking is
    visible as a disagreement this finds and it does not.
    """
    disagree, unread = [], []
    for rec in sorted((home / "installed").glob("*.json")):
        if rec.name.endswith(".projections.json"):
            continue
        try:
            d = json.loads(rec.read_text())
        except (OSError, json.JSONDecodeError):
            unread.append(rec.name)
            continue
        recorded = (d.get("gitHash") or "")
        if not recorded:
            continue
        name = d.get("name", rec.stem)
        store = next((home / k / name for k in ("skills", "plugins", "docs", "harnesses")
                      if (home / k / name).is_dir()), None)
        if store is None or not (store / ".git").exists():
            continue
        rc, out = run(["git", "-C", str(store), "rev-parse", "HEAD"], timeout=60)
        if rc != 0:
            continue
        head = out.strip()
        if head and head != recorded:
            disagree.append({"unit": name, "record": recorded[:8], "checkout": head[:8]})
    return {"check": "record matches checkout", "home": str(home), "measured": True,
            "agrees": not disagree, "disagreements": disagree, "unreadable": unread}


def main() -> int:
    hs = homes()
    if not hs:
        print(json.dumps({"measured": False, "why": "no home found"}))
        return 1
    results = []
    for h in hs:
        results.append(check_census_matches_disk(h))
        results.append(check_record_matches_checkout(h))
        results.append(check_repair_agrees_with_copy_readiness(h))

    disagreed = [r for r in results if r.get("measured") and not r.get("agrees")]
    unmeasured = [r for r in results if not r.get("measured")]
    print(json.dumps({
        "checks": len(results),
        "disagreements": len(disagreed),
        "could_not_measure": len(unmeasured),
        "results": results,
    }, indent=2))
    return 1 if disagreed or unmeasured else 0


if __name__ == "__main__":
    sys.exit(main())
