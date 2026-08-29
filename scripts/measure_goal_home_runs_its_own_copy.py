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
                row = {"home": str(home), "shim": shim.name,
                       "runs": str(cand), "other_home": str(other),
                       "unit": f"{kind}:{unit}",
                       "this_home_has_its_own": mine.exists(),
                       "via_symlink": shim.is_symlink(),
                       "also_execs_foreign_cache": bool(cache_hits)}
                (pairs if mine.exists() else fallback).append(row)
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
