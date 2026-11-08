"""#103: binary provisioning must reprovision when the install spec changes
under the same bin_path, and keep serving the cache when it does not."""
from __future__ import annotations

import asyncio
import subprocess
from pathlib import Path

from gateway.provisioning import MARKER_NAME, LoadSpec, Provisioner


def _spec(url: str, **extra) -> LoadSpec:
    binary = {"install": {"any": {"url": url, "archive": "raw"}}, "bin_path": "probe"}
    binary.update(extra)
    return LoadSpec.model_validate({"type": "binary", "binary": binary})


def _run(data: Path, spec: LoadSpec) -> str:
    cfg = asyncio.run(Provisioner(data).provision("probe-server", spec))
    return subprocess.run(cfg.command, capture_output=True, text=True, check=True).stdout.strip()


def _write(path: Path, version: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"#!/bin/sh\necho {version}\n")
    return path


def test_changed_url_same_bin_path_serves_new_binary(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    v2 = _write(tmp_path / "v2" / "probe", "2.0")
    data = tmp_path / "data"
    assert _run(data, _spec(v1.as_uri())) == "1.0"
    assert _run(data, _spec(v2.as_uri())) == "2.0"


def test_unchanged_spec_is_served_from_cache_without_url(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    data = tmp_path / "data"
    spec = _spec(v1.as_uri())
    assert _run(data, spec) == "1.0"
    v1.unlink()  # a second download would fail
    assert _run(data, spec) == "1.0"


def test_args_change_does_not_reprovision(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    data = tmp_path / "data"
    assert _run(data, _spec(v1.as_uri())) == "1.0"
    v1.unlink()
    assert _run(data, _spec(v1.as_uri(), args=["--verbose"])) == "1.0"


def test_legacy_ok_marker_reprovisions(tmp_path: Path) -> None:
    v1 = _write(tmp_path / "v1" / "probe", "1.0")
    v2 = _write(tmp_path / "v2" / "probe", "2.0")
    data = tmp_path / "data"
    assert _run(data, _spec(v1.as_uri())) == "1.0"
    marker = data / "mcp_binaries" / "probe-server" / MARKER_NAME
    assert marker.exists()
    marker.write_text("ok")  # what every gateway before #103 wrote
    assert _run(data, _spec(v2.as_uri())) == "2.0"
