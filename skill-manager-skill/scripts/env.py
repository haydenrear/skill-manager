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
    env.py --for claude                 # skill paths under ~/.claude/skills
    env.py --for codex                  # skill paths under ~/.codex/skills
    env.py --project-root .             # include passive skill-project context
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
PROJECT_MANIFEST_NAMES = ("skill-project.toml", "skill-manager-project.toml")


def skill_manager_home() -> Path:
    env = os.environ.get("SKILL_MANAGER_HOME")
    base = Path(env).expanduser() if env else Path.home() / ".skill-manager"
    # Normalize to absolute without resolving symlinks — callers rely on
    # bin/cli/<name> being a stable, user-facing entry point. A relative
    # SKILL_MANAGER_HOME would otherwise leak relative paths into the
    # JSON contract and break callers that cd before invoking.
    return base if base.is_absolute() else base.absolute()


def absolute_path(p: Path) -> Path:
    return p.expanduser() if p.expanduser().is_absolute() else p.expanduser().absolute()


# Mirrors dev.skillmanager.agent.{Claude,Codex}Agent — the Java install
# code drops symlinks at <agent_skills_dir>/<name> -> <home>/skills/<name>/latest
# for each installed skill, so env.py can hand callers either the original
# store path or the agent-visible symlink path.
def claude_skills_dir() -> Path:
    env = os.environ.get("CLAUDE_HOME")
    base = Path(env).expanduser() if env else Path.home()
    return (base / ".claude" / "skills").absolute()


def codex_skills_dir() -> Path:
    env = os.environ.get("CODEX_HOME")
    base = Path(env).expanduser() if env else Path.home() / ".codex"
    return (base / "skills").absolute()


AGENT_SKILL_DIRS = {
    "claude": claude_skills_dir,
    "codex": codex_skills_dir,
}


def find_project_root(start: Path) -> tuple[Path, Path] | None:
    current = absolute_path(start).resolve()
    if current.is_file():
        current = current.parent
    while True:
        for manifest_name in PROJECT_MANIFEST_NAMES:
            manifest = current / manifest_name
            if manifest.is_file():
                return current, manifest
        if current.parent == current:
            return None
        current = current.parent


def table_keys(manifest: dict, name: str) -> list[str]:
    value = manifest.get(name)
    if isinstance(value, dict):
        return sorted(str(k) for k in value.keys())
    return []


def project_context(home: Path, project_root_arg: str | None) -> dict:
    start = Path(project_root_arg) if project_root_arg else Path.cwd()
    found = find_project_root(start)
    if not found:
        return {
            "detected": False,
            "project_root": None,
            "manifest": None,
        }

    root, manifest_path = found
    try:
        with manifest_path.open("rb") as f:
            manifest = tomllib.load(f)
    except Exception as e:
        return {
            "detected": True,
            "project_root": str(root),
            "manifest": str(manifest_path),
            "parse_error": str(e),
        }

    project = manifest.get("project") if isinstance(manifest.get("project"), dict) else {}
    project_name = project.get("name") if isinstance(project.get("name"), str) else None
    child_home = root / ".skill-manager"
    agent_homes = {
        "claude": root / ".claude",
        "codex": root / ".codex",
        "gemini": root / ".gemini",
    }
    existing_agent_homes = {
        name: str(path)
        for name, path in agent_homes.items()
        if path.exists() or path.is_symlink()
    }
    registered_lock = home / "projects" / project_name / "project-lock.toml" if project_name else None
    env_docs = child_home / "env.md"

    return {
        "detected": True,
        "project_root": str(root),
        "manifest": str(manifest_path),
        "project_name": project_name,
        "declared": {
            "skills": table_keys(manifest, "skills"),
            "plugins": table_keys(manifest, "plugins"),
            "docs": table_keys(manifest, "docs"),
            "harnesses": table_keys(manifest, "harnesses"),
            "envs": table_keys(manifest, "envs"),
            "libs": [str(lib.get("name")) for lib in manifest.get("libs", [])
                     if isinstance(lib, dict) and lib.get("name") is not None],
        },
        "registered_project_lock": str(registered_lock) if registered_lock and registered_lock.is_file() else None,
        "child_skill_manager_home": str(child_home),
        "child_home_initialized": child_home.is_dir(),
        "agent_homes": {k: str(v) for k, v in agent_homes.items()},
        "agent_homes_existing": existing_agent_homes,
        "project_env_docs": str(env_docs) if env_docs.is_file() else None,
        "launch_env": {
            "SKILL_MANAGER_HOME": str(child_home),
            "CODEX_HOME": str(agent_homes["codex"]),
            "CLAUDE_HOME": str(agent_homes["claude"]),
            "GEMINI_HOME": str(agent_homes["gemini"]),
        },
    }


def on_path(tool: str) -> str | None:
    path = os.environ.get("PATH", "")
    for part in path.split(os.pathsep):
        if not part:
            continue
        candidate = Path(part) / tool
        if candidate.is_file() and os.access(candidate, os.X_OK):
            # Absolute, but don't follow symlinks — preserves the
            # PATH-visible identity of the executable for callers.
            return str(candidate if candidate.is_absolute() else candidate.absolute())
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


_SPEC_RE = re.compile(r"^(?P<backend>pip|npm|brew|tar|skill-script):(?P<rest>.+)$")


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


def store_skill_dir(home: Path, name: str) -> Path:
    """Working copy of one installed skill.

    The store is content-addressed: ``skills/<name>/`` holds one immutable
    ``<sha>/`` snapshot per stored version alongside the mutable ``latest/``
    working copy. Everything outside the store reads ``latest/``.
    """
    return home / "skills" / name / "latest"


def list_installed_skills(home: Path) -> list[str]:
    skills_dir = home / "skills"
    if not skills_dir.is_dir():
        return []
    return sorted(
        p.name for p in skills_dir.iterdir()
        if p.is_dir() and (p / "latest" / "skill-manager.toml").is_file()
    )


def resolve_skill_paths(name: str, home: Path, prefer: str | None) -> dict:
    """Build the path block for a single resolved skill.

    Always reports the original store path under ``$SKILL_MANAGER_HOME/skills``
    plus per-agent symlink paths (only those that actually exist on disk).
    The top-level ``path`` field obeys ``prefer``:

    - ``None`` (no ``--for``) → original store path.
    - ``"claude"`` / ``"codex"`` → that agent's symlink path if present,
      otherwise fall back to the original so callers always get a usable path.
    """
    original = store_skill_dir(home, name).absolute()
    agents: dict[str, str] = {}
    for agent_id, dir_fn in AGENT_SKILL_DIRS.items():
        candidate = dir_fn() / name
        if candidate.exists() or candidate.is_symlink():
            agents[agent_id] = str(candidate)

    if prefer and prefer in agents:
        path = agents[prefer]
    else:
        path = str(original)

    return {
        "name": name,
        "path": path,
        "original": str(original),
        "agents": agents,
    }


def collect(home: Path, requested: list[str] | None, prefer: str | None,
            project_root: str | None) -> dict:
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
    skills: dict[str, dict] = {}

    for skill in targets:
        skills[skill] = resolve_skill_paths(skill, home, prefer)
        manifest = load_skill_manifest(store_skill_dir(home, skill))
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
        "skills": skills,
        "skills_for": prefer,
        "package_managers": package_manager_paths(home),
        "clis": clis,
        "missing": missing_clis,
        "cli_bin_dir": str(cli_bin_dir),
        "project": project_context(home, project_root),
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
        "--for",
        dest="for_agent",
        choices=sorted(AGENT_SKILL_DIRS.keys()),
        default=None,
        help=(
            "report each skill's path under the named agent's skills dir "
            "(falling back to the original store path when the agent has "
            "no symlink). Default: original store path."
        ),
    )
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="pretty-print the JSON output",
    )
    parser.add_argument(
        "--project-root",
        metavar="DIR",
        help=(
            "look for skill-project.toml / skill-manager-project.toml at DIR "
            "or an ancestor instead of starting at the current directory"
        ),
    )
    args = parser.parse_args(argv)

    home = skill_manager_home()
    result = collect(home, args.skills, args.for_agent, args.project_root)

    indent = 2 if args.pretty else None
    json.dump(result, sys.stdout, indent=indent, sort_keys=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
