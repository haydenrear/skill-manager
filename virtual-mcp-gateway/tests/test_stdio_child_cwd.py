"""Tests for the stable-cwd guarantee on spawned stdio MCP children.

Regression: when the gateway daemon was launched by ``GatewayRuntime``
with cwd set to ``<install>/virtual-mcp-gateway`` (under the Homebrew
cellar), a ``brew upgrade skill-manager`` removed the old cellar path.
The still-running daemon then spawned every stdio MCP server with the
inherited, now-deleted cwd. Node-based servers (e.g. ``runpod`` from
``hyper-experiments``) called ``process.cwd()`` at startup and crashed
with ``Error: ENOENT … uv_cwd`` before the MCP handshake — surfacing to
skill-manager as ``MCP_REGISTRATION_FAILED`` and requiring ``gateway
down`` + ``up`` + ``sync`` to clear. The fix pins an explicit, stable
cwd onto ``StdioServerParameters`` so children stop inheriting whatever
the daemon's cwd happens to be.

This file pins:
  1. ``_stable_subprocess_cwd`` always returns a directory that exists.
  2. ``$VMG_DATA_DIR`` wins over ``$HOME`` when both are set + valid.
  3. ``StdioMCPClient`` actually threads that cwd into the spawn params.
"""

from __future__ import annotations

import asyncio
import os

import pytest

from gateway.clients import StdioMCPClient, _stable_subprocess_cwd
from gateway.models import ClientConfig


def test_returns_existing_directory_with_no_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """With ``$VMG_DATA_DIR`` unset, falls back to ``$HOME`` (or tmp).

    The returned path must always be a directory that ``os.path.isdir``
    confirms exists — the whole point of the helper is that children
    can never inherit a missing cwd.
    """
    monkeypatch.delenv("VMG_DATA_DIR", raising=False)
    cwd = _stable_subprocess_cwd()
    assert os.path.isdir(cwd), f"chosen cwd {cwd!r} must exist"


def test_prefers_vmg_data_dir_when_valid(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """``$VMG_DATA_DIR`` is set by ``GatewayRuntime`` to a stable, non-
    cellar path; when valid it takes precedence over ``$HOME``.
    """
    monkeypatch.setenv("VMG_DATA_DIR", str(tmp_path))
    assert _stable_subprocess_cwd() == str(tmp_path)


def test_skips_vmg_data_dir_when_missing(
    tmp_path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """An ``$VMG_DATA_DIR`` that points at a non-existent path (e.g. a
    cellar that was already removed) is skipped — we never propagate
    the very class of broken cwd this helper exists to avoid.
    """
    missing = tmp_path / "does-not-exist"
    monkeypatch.setenv("VMG_DATA_DIR", str(missing))
    cwd = _stable_subprocess_cwd()
    assert cwd != str(missing)
    assert os.path.isdir(cwd)


def test_falls_back_to_tempdir_when_home_and_data_dir_invalid(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If both ``$VMG_DATA_DIR`` and ``$HOME`` are unusable, the
    system temp dir is the last-resort fallback. ``tempfile.gettempdir``
    always returns an existing directory.
    """
    monkeypatch.delenv("VMG_DATA_DIR", raising=False)
    # Force ``expanduser("~")`` to return ``~`` unchanged so the helper
    # treats it as invalid. Clearing HOME alone isn't enough on macOS
    # (the user db lookup still succeeds), so we also clear pwd lookups
    # via setting HOME to an invalid path.
    monkeypatch.setenv("HOME", "/nonexistent/path/that/does/not/exist")
    cwd = _stable_subprocess_cwd()
    assert os.path.isdir(cwd)


def test_stdio_client_passes_explicit_cwd(monkeypatch: pytest.MonkeyPatch) -> None:
    """``StdioMCPClient._transport_context`` must construct
    ``StdioServerParameters`` with an explicit, existing ``cwd``.

    Before the fix this kwarg was omitted, causing children to inherit
    the daemon's (potentially deleted) cwd. We capture the params by
    monkeypatching ``stdio_client`` and assert the cwd was set.
    """
    captured: dict = {}

    class _FakeStdioClientCM:
        def __init__(self, params):
            captured["params"] = params

        async def __aenter__(self):
            # Return dummy streams; the test exits the context before
            # the session is initialized.
            raise RuntimeError("short-circuit before session init")

        async def __aexit__(self, exc_type, exc, tb):
            return False

    def _fake_stdio_client(params):
        return _FakeStdioClientCM(params)

    monkeypatch.setattr("gateway.clients.stdio_client", _fake_stdio_client)

    config = ClientConfig(
        server_id="runpod-regression",
        transport="stdio",
        command=["echo", "hi"],
    )
    client = StdioMCPClient(config)

    async def _drive() -> None:
        ctx = client._transport_context(forwarded_headers=None)
        with pytest.raises(RuntimeError, match="short-circuit"):
            async with ctx:
                pass

    asyncio.run(_drive())

    params = captured["params"]
    assert params.cwd is not None, "stdio child must be spawned with explicit cwd"
    assert os.path.isdir(str(params.cwd)), (
        f"explicit cwd {params.cwd!r} must point at an existing directory — "
        "the bug this guards against is precisely an inherited missing cwd"
    )
