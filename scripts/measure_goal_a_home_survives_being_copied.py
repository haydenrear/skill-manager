#!/usr/bin/env python3
"""GOAL-a-home-survives-being-copied-into-an-image, measured on real homes.

THE COPY IS A PLAIN RECURSIVE COPY, DELIBERATELY. The goal's own harness note
says so: "a probe that copies a home the way an image build would -- not `home
clone`, which is the APFS-only path". A Dockerfile runs `COPY`, not a product
command, so every property here has to survive a copy that applies no product
logic at all.

That distinction is the whole point of the goal, and it is why property (a)
is read differently from (b) and (c):

  (a) NO CREDENTIAL TRAVELS. A raw copy cannot exclude anything, so this is
      not a property of the copy -- it is a property of the PRODUCT's own
      copier, which is what an image build is told to use. Measured as: does
      `home clone` declare the credential excluded, and does the source
      actually hold one for it to exclude.
  (b) NO bin/cli ENTRY NAMES THE SOURCE HOME. This one IS read off the raw
      copy, because a frozen absolute path is exactly what a raw copy carries
      and what `home repair` does not report -- its rule asks "does this reach
      ANOTHER home", and an own-home absolute path does not.
  (c) THE COPY IS USABLE ON A DIFFERENT PLATFORM. Read off the raw copy too:
      `pm/` is deliberately NOT skipped, so the bytes travel, and what must be
      true is that each version directory is STAMPED with the platform that
      provisioned it -- an unstamped tree is one a foreign host would execute
      and get ENOEXEC from, far from the copy that caused it.

One bad property is the whole defect, per the goal's own wording, so the value
is reported per property and `met` is the conjunction.

Contract: one JSON object on stdout; exit 0 met, 1 not met, 2 could not
measure.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
AUTH_FILENAME = "auth.token"


def _java_const(path: Path, pattern: str):
    try:
        return re.search(pattern, path.read_text())
    except OSError:
        return None


def credential_is_declared_excluded() -> tuple[bool, str]:
    """Does the product's own copier declare the credential file skipped?

    Read out of the source rather than restated here, so a change that drops
    the exclusion fails this instead of silently disagreeing with it.
    """
    cloner = REPO / "src/main/java/dev/skillmanager/store/HomeCloner.java"
    m = _java_const(cloner, r"CREDENTIAL_ROOT_FILES\s*=\s*Set\.of\(([^;]*)\);")
    if m is None:
        return False, "HomeCloner declares no CREDENTIAL_ROOT_FILES"
    body = m.group(1)
    if "AuthStore.FILENAME" in body or AUTH_FILENAME in body:
        return True, "HomeCloner.CREDENTIAL_ROOT_FILES covers the auth token"
    return False, f"CREDENTIAL_ROOT_FILES does not name the auth token: {body.strip()}"


def shims_naming_the_source(copy_root: Path, source_root: Path) -> list[str]:
    """bin/cli entries in the COPY whose text still names the source home.

    A shim that execs an absolute path into the home it was written in runs
    the wrong home the moment the copy lands somewhere else -- and it is not a
    dangling reference, so nothing that checks for those reports it.
    """
    out = []
    bindir = copy_root / "bin" / "cli"
    if not bindir.is_dir():
        return out
    src = str(source_root)
    for entry in sorted(bindir.iterdir()):
        if not entry.is_file():
            continue
        try:
            text = entry.read_text(errors="replace")
        except OSError:
            continue
        if src in text:
            out.append(entry.name)
    return out


def unstamped_pm_versions(copy_root: Path) -> tuple[list[str], int]:
    """pm/<tool>/<version> directories in the COPY with no platform stamp.

    Returns (unstamped, total). An unstamped tree is executable by permission
    bits on a foreign host and answers ENOEXEC in fact.
    """
    pm = copy_root / "pm"
    unstamped, total = [], 0
    if not pm.is_dir():
        return unstamped, total
    for tool in sorted(p for p in pm.iterdir() if p.is_dir()):
        for version in sorted(p for p in tool.iterdir() if p.is_dir()):
            total += 1
            if not (version / ".platform").is_file():
                unstamped.append(f"pm/{tool.name}/{version.name}")
    return unstamped, total


def default_homes() -> list[Path]:
    roots = [Path.home() / ".skill-manager", REPO / ".skill-manager"]
    return [r for r in roots if (r / "installed").is_dir()]


def copy_like_an_image_build(source: Path, dest: Path) -> None:
    """cp -R, with the derived bulk left out so the probe stays cheap.

    `cache/` is the majority of a real home and is re-derivable by definition;
    excluding it changes none of the three answers and turns a multi-GB copy
    into a fast one. Everything a property is read from is copied.
    """
    shutil.copytree(
        source, dest, symlinks=True, ignore=shutil.ignore_patterns("cache", "venvs", "tools", "npm"),
        ignore_dangling_symlinks=True,
    )


def main() -> int:
    homes = [Path(a) for a in sys.argv[1:] if not a.startswith("-")] or default_homes()
    if not homes:
        print(json.dumps({
            "goal": "GOAL-a-home-survives-being-copied-into-an-image",
            "metric": "three properties of a home copied the way an image build copies one",
            "value": "no home to measure",
            "target": "3 of 3",
            "met": False,
            "could_not_measure": "no Skill Manager home found",
        }, indent=None))
        return 2

    declared, why_declared = credential_is_declared_excluded()
    per_home, bad_b, bad_c, sources_with_credential = [], [], [], 0

    for home in homes:
        tmp = Path(tempfile.mkdtemp(prefix="goal-image-copy-"))
        try:
            copy = tmp / "home"
            try:
                copy_like_an_image_build(home, copy)
            except OSError as exc:
                per_home.append({"home": str(home), "error": str(exc)})
                continue
            had_credential = (home / AUTH_FILENAME).is_file()
            sources_with_credential += int(had_credential)
            frozen = shims_naming_the_source(copy, home)
            unstamped, pm_total = unstamped_pm_versions(copy)
            bad_b.extend(f"{home.name}:{s}" for s in frozen)
            bad_c.extend(f"{home.name}:{v}" for v in unstamped)
            per_home.append({
                "home": str(home),
                "source_holds_a_credential": had_credential,
                "shims_naming_the_source_home": frozen,
                "pm_versions": pm_total,
                "pm_versions_unstamped": unstamped,
            })
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    a_met = declared
    b_met = not bad_b
    c_met = not bad_c
    met_count = sum((a_met, b_met, c_met))

    payload = {
        "goal": "GOAL-a-home-survives-being-copied-into-an-image",
        "metric": "three independently checkable properties of a copied home: "
                  "(a) no credential travels, (b) no bin/cli entry names the source home, "
                  "(c) the copy is usable on another platform",
        "value": f"{met_count} of 3 over {len(homes)} home(s)",
        "target": "3 of 3",
        "met": met_count == 3,
        "properties": {
            "a_no_credential_travels": {
                "met": a_met, "why": why_declared,
                "sources_holding_a_credential": sources_with_credential,
                "note": "a raw copy applies no product logic, so this is a property of "
                        "`home clone` -- the copier an image build is told to use",
            },
            "b_no_shim_names_the_source_home": {
                "met": b_met, "offenders": bad_b,
                "note": "read off the RAW copy: a frozen absolute path is what a raw copy "
                        "carries, and it is not a dangling reference so nothing else reports it",
            },
            "c_usable_on_another_platform": {
                "met": c_met, "unstamped": bad_c,
                "note": "pm/ is deliberately not skipped, so the bytes travel; each version "
                        "directory must be stamped with the platform that provisioned it",
            },
        },
        "homes": per_home,
        "method": "shutil.copytree(symlinks=True) -- cp -R, the way a Dockerfile COPY works, "
                  "never `home clone`, whose exclusions and re-anchoring are exactly what an "
                  "image build does NOT get",
    }
    print(json.dumps(payload))
    return 0 if payload["met"] else 1


if __name__ == "__main__":
    sys.exit(main())
