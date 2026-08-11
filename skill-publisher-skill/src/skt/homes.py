"""Read skill-manager home state from disk. Stdlib only, no network."""

from __future__ import annotations

import json
import os
import tomllib
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Unit:
    name: str
    version: str
    unit_kind: str
    origin: str | None
    git_hash: str | None
    git_ref: str | None
    loaded: bool
    errors: list[str]

    @property
    def change_managed(self) -> bool:
        """Git provenance present — the unit can round-trip to its own repo."""
        return bool(self.origin and self.git_hash)


def find_home(start: str | Path = ".") -> Path | None:
    """Resolve the home this session writes: env, then per-checkout, then root."""
    env = os.environ.get("SKILL_MANAGER_HOME")
    if env:
        return Path(env)
    node = Path(start).resolve()
    for candidate in (node, *node.parents):
        home = candidate / ".skill-manager"
        if (home / "installed").is_dir() or (home / "home.runtime.json").is_file():
            return home
    root = root_home()
    return root if root.is_dir() else None


def root_home() -> Path:
    """The operator root home. Overridable for tests."""
    override = os.environ.get("SKT_ROOT_HOME")
    return Path(override) if override else Path.home() / ".skill-manager"


def read_units(home: Path) -> list[Unit]:
    units: list[Unit] = []
    installed = home / "installed"
    if not installed.is_dir():
        return units
    for record in sorted(installed.glob("*.json")):
        if record.name.endswith(".projections.json"):
            continue
        try:
            data = json.loads(record.read_text())
        except (json.JSONDecodeError, OSError):
            units.append(
                Unit(record.stem, "?", "UNREADABLE", None, None, None, False, ["unreadable record"])
            )
            continue
        raw_errors = data.get("errors") or "[]"
        try:
            errors = json.loads(raw_errors) if isinstance(raw_errors, str) else list(raw_errors)
        except json.JSONDecodeError:
            errors = [str(raw_errors)]
        units.append(
            Unit(
                name=data.get("name", record.stem),
                version=data.get("version", "?"),
                unit_kind=data.get("unitKind", data.get("kind", "?")),
                origin=data.get("origin"),
                git_hash=data.get("gitHash"),
                git_ref=data.get("gitRef"),
                loaded=(installed / f"{record.stem}.projections.json").is_file(),
                errors=[str(e) for e in errors],
            )
        )
    return units


def read_plugins(home: Path) -> list[str]:
    plugins = home / "plugins"
    if not plugins.is_dir():
        return []
    return sorted(p.name for p in plugins.iterdir() if p.is_dir())


def read_policy(home: Path) -> str:
    policy_file = home / "home.policy.toml"
    if not policy_file.is_file():
        return "live"
    try:
        data = tomllib.loads(policy_file.read_text())
    except (tomllib.TOMLDecodeError, OSError):
        return "unreadable"
    return str(data.get("policy", "live"))


def read_cli_tools(home: Path) -> list[str]:
    """Tool names from cli-lock.toml — keyed [package-manager.tool]."""
    lock = home / "cli-lock.toml"
    if not lock.is_file():
        return []
    try:
        data = tomllib.loads(lock.read_text())
    except (tomllib.TOMLDecodeError, OSError):
        return []
    tools: set[str] = set()
    for backend_table in data.values():
        if isinstance(backend_table, dict):
            for tool, spec in backend_table.items():
                if isinstance(spec, dict):
                    tools.add(tool)
    return sorted(tools)


def drift_pending(home: Path) -> bool:
    """A recorded, UNACKNOWLEDGED drift blocks the next launch (exit 8).

    DriftGate.acknowledge rewrites the record with acknowledged=true —
    it never deletes the file — so presence alone is not pending.
    """
    for name in ("home.drift.json", "drift.json"):
        record = home / name
        if not record.is_file():
            continue
        try:
            data = json.loads(record.read_text())
        except (json.JSONDecodeError, OSError):
            return True  # unreadable record: assume the gate will refuse
        if isinstance(data, dict) and data.get("acknowledged") is True:
            continue
        return True
    return False
