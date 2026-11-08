"""#428 R6: binary provisioning downloads and verifies BEFORE it touches the
working install. A failed or offline reprovision keeps serving the old binary
and reports it; only a verified install is swapped in."""
from __future__ import annotations

import asyncio
import logging
import subprocess
from pathlib import Path

import pytest

from gateway.provisioning import MARKER_NAME, LoadSpec, Provisioner

SERVER = "probe-server"


def _spec(url: str, **extra) -> LoadSpec:
    binary = {"install": {"any": {"url": url, "archive": "raw"}}, "bin_path": "probe"}
    sha = extra.pop("sha256", None)
    if sha:
        binary["install"]["any"]["sha256"] = sha
    binary.update(extra)
    return LoadSpec.model_validate({"type": "binary", "binary": binary})


def _provision(data: Path, spec: LoadSpec) -> str:
    cfg = asyncio.run(Provisioner(data).provision(SERVER, spec))
    return subprocess.run(cfg.command, capture_output=True, text=True, check=True).stdout.strip()


def _write(path: Path, version: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"#!/bin/sh\necho {version}\n")
    return path


def _install_dir(data: Path) -> Path:
    return data / "mcp_binaries" / SERVER


def _leftovers(data: Path) -> list[str]:
    return sorted(p.name for p in (data / "mcp_binaries").iterdir() if p.name != SERVER)


def test_legacy_marker_offline_keeps_serving_the_old_binary(tmp_path: Path, caplog) -> None:
    """The upgrade migration itself: a pre-#103 'ok' marker never matches, and
    the url is unreachable (offline). The working install must survive."""
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    data = tmp_path / "data"
    spec = _spec(v1.as_uri())
    assert _provision(data, spec) == "1.0"
    (_install_dir(data) / MARKER_NAME).write_text("ok")
    v1.unlink()  # offline: the url no longer resolves
    with caplog.at_level(logging.WARNING, logger="gateway.provisioning"):
        assert _provision(data, spec) == "1.0"
    assert any("keeping the installed binary" in r.getMessage() for r in caplog.records)
    assert _leftovers(data) == []


def test_failed_download_of_a_new_url_keeps_the_old_binary(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    data = tmp_path / "data"
    assert _provision(data, _spec(v1.as_uri())) == "1.0"
    missing = (tmp_path / "v2" / "probe").as_uri()
    assert _provision(data, _spec(missing)) == "1.0"
    assert _leftovers(data) == []


def test_checksum_mismatch_is_never_swapped_in(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    v2 = _write(tmp_path / "v2" / "probe", "2.0")
    data = tmp_path / "data"
    assert _provision(data, _spec(v1.as_uri())) == "1.0"
    assert _provision(data, _spec(v2.as_uri(), sha256="0" * 64)) == "1.0"
    assert _leftovers(data) == []


def test_failed_init_script_restores_the_old_install(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    v2 = _write(tmp_path / "v2" / "probe", "2.0")
    data = tmp_path / "data"
    assert _provision(data, _spec(v1.as_uri())) == "1.0"
    assert _provision(data, _spec(v2.as_uri(), init_script="exit 3")) == "1.0"
    assert _leftovers(data) == []


def test_failure_with_no_prior_install_raises(tmp_path: Path) -> None:
    data = tmp_path / "data"
    with pytest.raises(Exception):
        _provision(data, _spec((tmp_path / "nowhere" / "probe").as_uri()))
    assert not (_install_dir(data) / "probe").exists()


def test_verified_download_is_swapped_in_with_nothing_left_behind(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    v2 = _write(tmp_path / "v2" / "probe", "2.0")
    data = tmp_path / "data"
    assert _provision(data, _spec(v1.as_uri())) == "1.0"
    assert _provision(data, _spec(v2.as_uri())) == "2.0"
    assert _leftovers(data) == []
    v2.unlink()
    assert _provision(data, _spec(v2.as_uri())) == "2.0"  # cached under the new digest
