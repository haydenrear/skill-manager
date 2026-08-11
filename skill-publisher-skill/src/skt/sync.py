"""`skt sync <unit>` — pull a unit to its latest pushed source, loudly.

Thin wrapper over `skill-manager sync <unit> --git-latest` that closes
the documented trap: sync over an unpushed commit exits 0 and prints a
full success report while the store stays on the old gitHash. After the
underlying sync, the installed record is re-read and compared to the
remote tip; a mismatch is reported as a failure with the reason.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

from . import check as check_mod
from . import homes


def _cli(home: Path) -> Path:
    """The home's own pinned CLI — never a bare `skill-manager` from PATH."""
    return home / "bin" / "cli" / "skill-manager"


def run(unit_name: str | None, *, start: str | Path = ".") -> int:
    home = homes.find_home(start)
    if home is None:
        print("skt sync: no skill-manager home found")
        return 1
    if not unit_name:
        print("skt sync: name a unit (see `skt check` for which are stale)")
        return 1
    units = {u.name: u for u in homes.read_units(home)}
    unit = units.get(unit_name)
    if unit is None:
        print(f"skt sync: no installed unit named {unit_name!r}")
        return 1
    if not unit.change_managed:
        print(f"skt sync: {unit_name} has no git provenance; nothing to sync from")
        return 1
    cli = _cli(home)
    if not cli.is_file():
        print(f"skt sync: home CLI not found at {cli}")
        return 1
    from .publish import _cli_env  # livelock guard: strip SKILL_MANAGER_CLI

    proc = subprocess.run(
        [str(cli), "sync", unit_name, "--git-latest"],
        capture_output=True,
        text=True,
        env=_cli_env(),
    )
    if proc.returncode != 0:
        print(f"skt sync: underlying sync failed (exit {proc.returncode})")
        print(proc.stdout[-2000:] + proc.stderr[-2000:])
        return proc.returncode
    after = {u.name: u for u in homes.read_units(home)}.get(unit_name)
    tip = check_mod._remote_tip_safe(unit.origin, unit.git_ref)
    if after and tip and after.git_hash == tip:
        print(f"skt sync: {unit_name} now at {tip[:8]} (matches remote tip)")
        return 0
    if after and tip:
        print(
            f"skt sync: WARNING — sync reported success but the store is at "
            f"{(after.git_hash or '?')[:8]} while the remote tip is {tip[:8]}. "
            f"This is the silent-no-op trap: the usual cause is an unpushed commit "
            f"in the unit's source repo. Push there, then re-run skt sync {unit_name}."
        )
        return 11
    print(f"skt sync: {unit_name} synced; remote tip unverifiable (offline?)")
    return 0
