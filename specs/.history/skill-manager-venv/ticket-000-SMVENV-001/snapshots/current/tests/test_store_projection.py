"""Ticket SMVENV-001 — the filesystem projector reads the content-addressed store.

``observe_home`` is what the external cases diff the real CLI against, so it has
to agree with ``Internal.tla``'s ``StoreUnitVersion`` on two points:

* ``store_versions`` / ``store_latest`` are real, observable state — every
  ``skills/<unit>/<sha>/`` snapshot and the sha named by ``.store-latest``.
* A slot outlives its working copy. ``RemoveUnit`` drops the unit from
  ``cli_store_units`` but leaves ``store_versions`` alone (cache semantics), so
  a snapshot-only slot must not read back as an installed unit — otherwise the
  projector's store/installed/lock divergence check fires on a state the model
  says is perfectly legal.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from adapters import LATEST_DIR, LATEST_MARKER, observe_home  # noqa: E402


def _install(home: Path, unit: str = "UnitA") -> None:
    """The on-disk footprint of a successful `skill-manager install`."""
    working_copy = home / "skills" / unit / LATEST_DIR
    working_copy.mkdir(parents=True)
    (working_copy / "SKILL.md").write_text(
        f"---\nname: {unit}\ndescription: fixture\n---\nBody.\n"
    )
    (home / "installed").mkdir(exist_ok=True)
    (home / "installed" / f"{unit}.json").write_text(json.dumps({"name": unit}))
    (home / "units.lock.toml").write_text(f'[[units]]\nname = "{unit}"\nkind = "skill"\n')


def _store(home: Path, sha: str, unit: str = "UnitA") -> None:
    """The on-disk footprint of `skill-manager store add <unit> --sha <sha>`."""
    snapshot = home / "skills" / unit / sha
    snapshot.mkdir(parents=True, exist_ok=True)
    (snapshot / "SKILL.md").write_text(f"---\nname: {unit}\n---\n{sha}\n")
    (home / "skills" / unit / LATEST_MARKER).write_text(sha + "\n")


def _remove(home: Path, unit: str = "UnitA") -> None:
    """`skill-manager remove` — the working copy goes, the snapshots stay."""
    import shutil

    shutil.rmtree(home / "skills" / unit / LATEST_DIR)
    (home / "installed" / f"{unit}.json").unlink()
    (home / "units.lock.toml").write_text("")


def test_installed_unit_has_no_stored_versions(tmp_path: Path) -> None:
    _install(tmp_path)
    state = observe_home(tmp_path)
    assert state["cli_store_units"] == ["UnitA"]
    assert state["project_model"]["store_versions"] == []
    assert state["project_model"]["store_latest"] == []


def test_store_add_is_observable(tmp_path: Path) -> None:
    _install(tmp_path)
    _store(tmp_path, "ShaA")
    state = observe_home(tmp_path)
    assert state["cli_store_units"] == ["UnitA"]
    assert state["project_model"]["store_versions"] == [("UnitA", "ShaA")]
    assert state["project_model"]["store_latest"] == [("UnitA", "ShaA")]


def test_two_shas_leave_both_snapshots_and_one_latest(tmp_path: Path) -> None:
    """VenvStoreLatestIsStored + VenvStoreLatestUniquePerUnit, read off disk."""
    _install(tmp_path)
    _store(tmp_path, "ShaA")
    _store(tmp_path, "ShaB")
    state = observe_home(tmp_path)
    assert state["project_model"]["store_versions"] == [("UnitA", "ShaA"), ("UnitA", "ShaB")]
    assert state["project_model"]["store_latest"] == [("UnitA", "ShaB")]


def test_remove_keeps_snapshots_and_drops_the_unit(tmp_path: Path) -> None:
    """The trace the external model generates: store, then remove.

    Counting bare slots as installed units would report UnitA as installed with
    no installed-record behind it, and the divergence check would raise.
    """
    _install(tmp_path)
    _store(tmp_path, "ShaA")
    _remove(tmp_path)

    state = observe_home(tmp_path)
    assert state["cli_store_units"] == []
    assert state["project_model"]["store_versions"] == [("UnitA", "ShaA")]
    assert state["project_model"]["store_latest"] == [("UnitA", "ShaA")]


def test_divergence_still_raises_on_a_real_inconsistency(tmp_path: Path) -> None:
    """The relaxation above must not blind the projector to an actual bug:
    a working copy with no installed record is still a divergence."""
    _install(tmp_path)
    (tmp_path / "installed" / "UnitA.json").unlink()
    with pytest.raises(AssertionError, match="store/installed/lock divergence"):
        observe_home(tmp_path)
