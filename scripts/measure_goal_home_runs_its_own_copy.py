#!/usr/bin/env python3
"""GOAL-a-home-runs-its-own-copy -- baseline walker. READ ONLY.

Metric: (home, cli-shim) pairs whose shim resolves a skill copy living in a
DIFFERENT skill-manager home than the one the shim lives in.

Reported per pair, never aggregated into a single verdict: one bad pair is the
whole defect, and a count alone hides which homes are affected.

Two populations are separated, because they are different defects:

  UNSANCTIONED  the shim's own home HOLDS its own copy of that unit, and the
                shim runs the other home's anyway. This is the #262 case and
                HomeRepair.foreignPathsInShims' rewrite remedy applies.
  FALLBACK      the shim's own home has no copy, so reaching the parent store
                is the sanctioned behaviour HomeCloner.unsanctionedForeignHome
                exists to permit. Counted, not charged.

A path into a Homebrew keg, a venv or anywhere outside a home is NOT a finding:
those are builds and interpreters, not another home's skill copy.
"""
import json, os, re, sys
from pathlib import Path

ABS = re.compile(r'(?<![\w$])(/(?:[^\s"\'|;:()]+))')

def homes(roots):
    seen = {}
    for root in roots:
        r = Path(root)
        if not r.is_dir():
            continue
        for p in [r] if r.name == ".skill-manager" else []:
            seen[str(p.resolve())] = p
        try:
            for child in r.iterdir():
                c = child / ".skill-manager"
                if c.is_dir():
                    seen[str(c.resolve())] = c
        except OSError:
            pass
    return sorted(seen.values(), key=lambda p: str(p))

def owning_home(path: Path):
    """The .skill-manager directory this path lives under, or None."""
    for a in path.parents:
        if a.name == ".skill-manager":
            return a
    return None

def unit_of(path: Path, home: Path):
    """<home>/skills/<unit>/... or <home>/plugins/<unit>/... -> (kind, unit)."""
    try:
        rel = path.resolve().relative_to(home.resolve()).parts
    except (ValueError, OSError):
        return None
    if len(rel) >= 2 and rel[0] in ("skills", "plugins"):
        return rel[0], rel[1]
    return None

def main():
    roots = sys.argv[1:] or [os.path.expanduser("~/.skill-manager"),
                             os.path.expanduser("~/IdeaProjects")]
    pairs, fallback, cache_only, scanned_homes, scanned_shims = [], [], [], 0, 0
    for home in homes(roots):
        cli = home / "bin" / "cli"
        if not cli.is_dir():
            continue
        scanned_homes += 1
        for shim in sorted(cli.iterdir()):
            if shim.is_dir():
                continue
            scanned_shims += 1
            # follow a symlink to the shim that actually holds the body
            body_src = shim
            try:
                if shim.is_symlink():
                    body_src = Path(os.path.realpath(shim))
            except OSError:
                pass
            try:
                text = body_src.read_text(errors="replace")
            except (OSError, UnicodeDecodeError):
                continue
            if "\0" in text[:2048]:
                continue  # a copied binary, not a script
            # Classify EVERY absolute path in the body before deciding.
            # An earlier version broke on the first skills/ hit, so a cache
            # exec on a later line was invisible -- and in the measured
            # computeq shim the export line precedes the exec, so the flag
            # could never be true. Collect first, decide after.
            skill_hits, cache_hits = [], []
            for m in ABS.finditer(text):
                cand = Path(m.group(1).rstrip("\"'"))
                other = owning_home(cand)
                if other is None:
                    continue                      # a keg, a venv, an interpreter
                if str(other.resolve()) == str(home.resolve()):
                    continue                      # its own home: correct
                ku = unit_of(cand, other)
                if ku is not None:
                    skill_hits.append((cand, other, ku))
                    continue
                try:
                    rel = cand.resolve().relative_to(other.resolve()).parts
                except (ValueError, OSError):
                    rel = ()
                if rel and rel[0] == "cache":
                    cache_hits.append(cand)
            if skill_hits:
                cand, other, (kind, unit) = skill_hits[0]
                mine = home / kind / unit
                # CAN THIS HOME ACTUALLY RUN ITS OWN COPY?  Not "does it hold
                # the unit DIRECTORY" -- that was this walker's original test
                # and it is the wrong question.  A shim runs a command line,
                # and `deploy-helm`'s runs a per-unit CACHE VENV BINARY:
                #
                #   exec <home>/cache/skill-script-deploy-helm-computeq/venv/bin/computeq
                #
                # `home clone` deliberately does not copy cache/, venvs/,
                # tools/ or npm/, so a freshly cloned home holds
                # skills/deploy-helm and NOT the venv that shim execs.  Under
                # the old test that scored as "has its own copy, runs the
                # parent's" -- an unsanctioned crossing.  It is the opposite:
                # the mirror is the ONLY way the tool runs at all, which is
                # exactly the sanctioned fallback this metric says it excludes.
                #
                # Measured 2026-09-02: 53 of 55 "unsanctioned" pairs were this,
                # and the baseline's claim of "0 sanctioned fallbacks" was an
                # artefact of the same error.  So the test is now every foreign
                # path the shim names, mapped into this home: a crossing is
                # unsanctioned only when the home holds ALL of them and reaches
                # past them anyway.
                needed = [cand] + list(cache_hits)
                missing = []
                for path in needed:
                    try:
                        rel = path.resolve().relative_to(other.resolve())
                    except (ValueError, OSError):
                        continue
                    if not (home / rel).exists():
                        missing.append(str(rel))
                has_everything = mine.exists() and not missing
                row = {"home": str(home), "shim": shim.name,
                       "runs": str(cand), "other_home": str(other),
                       "unit": f"{kind}:{unit}",
                       "this_home_has_its_own": mine.exists(),
                       "can_run_its_own": has_everything,
                       "missing_locally": missing,
                       "via_symlink": shim.is_symlink(),
                       "also_execs_foreign_cache": bool(cache_hits)}
                (pairs if has_everything else fallback).append(row)
            elif cache_hits:
                cache_only.append({"home": str(home), "shim": shim.name,
                                   "runs": str(cache_hits[0])})

    out = {"scanned_homes": scanned_homes, "scanned_shims": scanned_shims,
           "unsanctioned_pairs": len(pairs), "sanctioned_fallback": len(fallback),
           "foreign_cache_only": len(cache_only),
           "pairs": pairs, "fallback": fallback, "cache_only": cache_only}
    print(json.dumps(out, indent=1))

if __name__ == "__main__":
    main()
