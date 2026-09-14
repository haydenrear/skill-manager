"""Shared, read-only disk observations for the one-home-one-verdict goal harnesses.

Promoted from the kickoff scripts under
results/epic-one-home-one-verdict/baseline/2026-09-13-v0.27.2/ (measure_baseline.py,
measure_fleet.py). Two things changed on the way:

* stdout and stderr are kept APART. `home repair --json` writes its JSON to stdout
  and a recorded-errors banner to stderr; the kickoff fleet script parsed the two
  together and recorded four homes' finding counts as null.
* "this project home" is the MAIN checkout's home, found through git's common dir,
  so a harness run from a ticket worktree measures the same home the baseline did
  rather than the worktree's own scratch home.

Nothing here writes to a home: no --fix, --record, --ack or sync.
Python 3.9 compatible.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
HOME = Path.home()


def main_checkout() -> Path:
    """The main working tree of this repository, even when run from a worktree."""
    try:
        p = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--path-format=absolute",
                            "--git-common-dir"], capture_output=True, text=True, timeout=30)
        if p.returncode == 0 and p.stdout.strip():
            return Path(p.stdout.strip()).parent
    except (OSError, subprocess.TimeoutExpired):
        pass
    return REPO


def default_homes() -> list:
    """[(label, store, checkout)] for root and this project's home, where they exist."""
    checkout = main_checkout()
    out = [("root", HOME / ".skill-manager", HOME),
           ("project", checkout / ".skill-manager", checkout)]
    return [h for h in out if (h[1] / "installed").is_dir()]


def spellings(home: Path) -> list:
    s = {str(home), os.path.realpath(home)}
    for x in list(s):
        if x.startswith("/private/"):
            s.add(x[len("/private"):])
        elif x.startswith(("/var/", "/tmp/")):
            s.add("/private" + x)
    return sorted(s, key=len, reverse=True)


def cli_for(home: Path):
    """The home's own pinned CLI (binds the home it lives in), else skill-manager on PATH."""
    pinned = home / "bin" / "cli" / "skill-manager"
    if pinned.exists():
        return str(pinned)
    return shutil.which("skill-manager")


def run(argv, env_home=None, cwd=None, timeout=900):
    """(rc, stdout, stderr) — never merged."""
    env = dict(os.environ)
    if env_home is not None:
        env["SKILL_MANAGER_HOME"] = str(env_home)
    try:
        p = subprocess.run([str(a) for a in argv], capture_output=True, text=True,
                           timeout=timeout, env=env, cwd=cwd)
        return p.returncode, p.stdout or "", p.stderr or ""
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, "", str(exc)


def json_from_stdout(stdout: str):
    """The JSON object on stdout alone, or None."""
    try:
        return json.loads(stdout[stdout.index("{"):])
    except (ValueError, json.JSONDecodeError):
        return None


def repair_report(home: Path) -> dict:
    """`home repair --home <h> --json`, read-only. {rc, doc, stderr_tail}."""
    cli = cli_for(home)
    if cli is None:
        return {"rc": None, "doc": None, "why": "no skill-manager CLI found"}
    rc, out, err = run([cli, "home", "repair", "--home", str(home), "--json"], env_home=home)
    doc = json_from_stdout(out)
    return {"rc": rc, "doc": doc, "cli": cli,
            "stderr_tail": err.strip().splitlines()[-2:] if err.strip() else []}


def discover_fleet() -> list:
    """[(store, checkout)]: root plus every <dir>/.skill-manager under ~/IdeaProjects, depth <= 2."""
    homes = [(HOME / ".skill-manager", HOME)]
    base = HOME / "IdeaProjects"
    if base.is_dir():
        for d in sorted(base.iterdir()):
            if not d.is_dir():
                continue
            if (d / ".skill-manager" / "installed").is_dir():
                homes.append((d / ".skill-manager", d))
            try:
                for e in sorted(d.iterdir()):
                    if e.is_dir() and (e / ".skill-manager" / "installed").is_dir():
                        homes.append((e / ".skill-manager", e))
            except OSError:
                pass
    seen, out = set(), []
    for h, c in homes:
        r = os.path.realpath(h)
        if r not in seen and (h / "installed").is_dir():
            seen.add(r)
            out.append((h, c))
    return out


def is_text(p: Path):
    try:
        if p.stat().st_size > 2_000_000:
            return None
        b = p.read_bytes()
    except OSError:
        return None
    return None if b"\0" in b[:4096] else b.decode("utf-8", "replace")


def bin_cli_own_path_entries(home: Path) -> list:
    """Regular-file bin/cli entries whose text spells this home's own absolute path."""
    sp = spellings(home)
    out = []
    cli = home / "bin" / "cli"
    if not cli.is_dir():
        return out
    for p in sorted(cli.iterdir()):
        if p.is_symlink() or not p.is_file():
            continue
        t = is_text(p)
        if t is None or not any(s in t for s in sp):
            continue
        out.append({"entry": p.name,
                    "half_rewritten": "SKILL_MANAGER_SHIM_HOME" in t})
    return out


def generated_marketplace_name(home: Path):
    try:
        return json.loads((home / "plugin-marketplace" / ".claude-plugin" / "marketplace.json")
                          .read_text()).get("name")
    except (OSError, json.JSONDecodeError):
        return None


def codex_marketplaces(path: Path):
    if not path.is_file():
        return {}, []
    t = path.read_text(errors="replace")
    res = {}
    for m in re.finditer(r'^\[marketplaces\.("?)([^\]"]+)\1\]\s*\n((?:(?!^\[).*\n?)*)', t, re.M):
        src = re.search(r'^\s*source\s*=\s*"([^"]+)"', m.group(3), re.M)
        res[m.group(2)] = src.group(1) if src else None
    return res, re.findall(r'^\[plugins\."([^"]+)"\]', t, re.M)


def emit(payload: dict, unmeasured: bool) -> int:
    """Print the one JSON object; exit 0 met, 1 not met, 2 could not measure."""
    if unmeasured:
        payload["met"] = False
    print(json.dumps(payload))
    if unmeasured:
        return 2
    return 0 if payload.get("met") else 1
