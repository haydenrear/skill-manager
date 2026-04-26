#!/usr/bin/env -S uv run
# /// script
# requires-python = ">=3.11"
# ///
"""Report absolute paths to the package managers and CLIs that installed
skill-manager skills depend on.

The script never mutates PATH or any shell state. It prints a JSON map
the agent can use to invoke tools by absolute path, sidestepping any
version conflict with whatever the user has on PATH.

Usage:
    env.py                              # dump every installed skill
    env.py --skills hello-skill foo     # restrict to specific skills
    env.py --skills hello-skill --pretty
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

import tomllib  # Python 3.11+ — guaranteed by `requires-python` above


PACKAGE_MANAGERS = {
    "uv": ("uv", ["uv"]),
    "node": ("node", ["node", "npm", "npx"]),
}


def skill_manager_home() -> Path:
    env = os.environ.get("SKILL_MANAGER_HOME")
    if env:
        return Path(env).expanduser()
    return Path.home() / ".skill-manager"


def on_path(tool: str) -> str | None:
    path = os.environ.get("PATH", "")
    for part in path.split(os.pathsep):
        if not part:
            continue
        candidate = Path(part) / tool
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def resolve_pm_binary(home: Path, pm_id: str, tool: str) -> str | None:
    current = home / "pm" / pm_id / "current"
    if not current.exists() and not current.is_symlink():
        return None
    if current.is_symlink():
        target = os.readlink(current)
        vdir = Path(target) if os.path.isabs(target) else current.parent / target
    else:
        vdir = current.parent / current.read_text().strip()
    binary = vdir / "bin" / tool
    if binary.is_file() and os.access(binary, os.X_OK):
        return str(binary)
    return None


def package_manager_paths(home: Path) -> dict:
    out: dict[str, dict] = {}
    seen: set[str] = set()
    for pm_id, (_, tools) in PACKAGE_MANAGERS.items():
        for tool in tools:
            if tool in seen:
                continue
            seen.add(tool)
            bundled = resolve_pm_binary(home, pm_id, tool)
            if bundled:
                out[tool] = {"path": bundled, "bundled": True, "available": True}
                continue
            system = on_path(tool)
            if system:
                out[tool] = {"path": system, "bundled": False, "available": True}
            else:
                out[tool] = {"path": None, "bundled": False, "available": False}

    # brew is never bundled — system only.
    brew = on_path("brew")
    out["brew"] = {
        "path": brew,
        "bundled": False,
        "available": brew is not None,
    }
    return out


_SPEC_RE = re.compile(r"^(?P<backend>pip|npm|brew|tar):(?P<rest>.+)$")


def parse_spec(spec: str) -> tuple[str, str]:
    """Return (backend, package-without-version)."""
    m = _SPEC_RE.match(spec.strip())
    if not m:
        return ("unknown", spec)
    backend = m.group("backend")
    rest = m.group("rest")
    # Strip version qualifiers: pip uses ==, npm uses @ (ignore leading @ for scoped pkgs).
    if backend == "pip":
        pkg = re.split(r"[<>=!~ ]", rest, 1)[0]
    elif backend == "npm":
        if rest.startswith("@"):
            # @scope/pkg@version
            scope, _, tail = rest.partition("/")
            pkg_name, _, _ = tail.partition("@")
            pkg = f"{scope}/{pkg_name}" if pkg_name else rest
        else:
            pkg = rest.split("@", 1)[0]
    else:
        pkg = rest
    return (backend, pkg)


def candidate_names(dep: dict) -> list[str]:
    """Best-guess binary names for a cli_dependency entry, in priority order."""
    names: list[str] = []
    if isinstance(dep.get("name"), str):
        names.append(dep["name"])
    spec = dep.get("spec", "")
    _, pkg = parse_spec(spec)
    if pkg and pkg not in names:
        names.append(pkg)
    on_path_field = dep.get("on_path")
    if isinstance(on_path_field, str) and on_path_field not in names:
        names.append(on_path_field)
    return names


def resolve_cli(dep: dict, cli_bin_dir: Path, skill: str) -> dict:
    backend, _ = parse_spec(dep.get("spec", ""))
    candidates = candidate_names(dep)
    found_path: str | None = None
    found_name: str | None = None
    for name in candidates:
        candidate = cli_bin_dir / name
        # Don't resolve() — bin/cli/<name> is the stable, user-facing entry
        # point (often a symlink into a venv or node_modules tree). The
        # agent wants the symlink path, not the implementation target.
        if candidate.exists() and os.access(candidate, os.X_OK):
            found_path = str(candidate)
            found_name = name
            break
    return {
        "spec": dep.get("spec"),
        "backend": backend,
        "from_skill": skill,
        "candidate_names": candidates,
        "name": found_name,
        "path": found_path,
        "installed": found_path is not None,
    }


def load_skill_manifest(skill_dir: Path) -> dict | None:
    manifest = skill_dir / "skill-manager.toml"
    if not manifest.is_file():
        return None
    try:
        with manifest.open("rb") as f:
            return tomllib.load(f)
    except Exception as e:
        sys.stderr.write(f"warning: failed to parse {manifest}: {e}\n")
        return None


def list_installed_skills(home: Path) -> list[str]:
    skills_dir = home / "skills"
    if not skills_dir.is_dir():
        return []
    return sorted(
        p.name for p in skills_dir.iterdir()
        if p.is_dir() and (p / "skill-manager.toml").is_file()
    )


def collect(home: Path, requested: list[str] | None) -> dict:
    skills_dir = home / "skills"
    cli_bin_dir = home / "bin" / "cli"

    available = list_installed_skills(home)
    if requested is None:
        targets = available
        missing_skills: list[str] = []
    else:
        targets = [s for s in requested if s in available]
        missing_skills = [s for s in requested if s not in available]

    clis: dict[str, dict] = {}
    missing_clis: list[dict] = []

    for skill in targets:
        manifest = load_skill_manifest(skills_dir / skill)
        if not manifest:
            continue
        for dep in manifest.get("cli_dependencies", []) or []:
            resolved = resolve_cli(dep, cli_bin_dir, skill)
            if resolved["installed"]:
                key = resolved["name"]
                clis[key] = resolved
            else:
                missing_clis.append(resolved)

    return {
        "skill_manager_home": str(home),
        "skills_requested": requested,
        "skills_resolved": targets,
        "skills_unknown": missing_skills,
        "package_managers": package_manager_paths(home),
        "clis": clis,
        "missing": missing_clis,
        "cli_bin_dir": str(cli_bin_dir),
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="env.py",
        description=(
            "Report absolute paths to the package managers and CLIs that "
            "installed skill-manager skills depend on. Does not mutate PATH."
        ),
    )
    parser.add_argument(
        "--skills",
        nargs="+",
        metavar="NAME",
        help="restrict output to these installed skills (default: all installed)",
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="pretty-print the JSON output",
    )
    args = parser.parse_args(argv)

    home = skill_manager_home()
    result = collect(home, args.skills)

    indent = 2 if args.pretty else None
    json.dump(result, sys.stdout, indent=indent, sort_keys=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
