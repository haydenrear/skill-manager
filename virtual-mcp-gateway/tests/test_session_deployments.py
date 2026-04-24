"""Tests for per-session deployment, sticky auto-redeploy, and error surfacing.

These tests exercise the registry directly with mocked downstream clients so
they're fast + deterministic. The end-to-end path is covered by
test_end_to_end.py separately.
"""
from __future__ import annotations

import asyncio
from pathlib import Path
from typing import List
from unittest.mock import AsyncMock, MagicMock

import pytest

from gateway.models import (
    ClientConfig,
    DownstreamTool,
    InitSchemaField,
    MCPServerDefinition,
)
from gateway.persistence import DynamicServerStore, LastInitStore
from gateway.registry import ToolRegistry


def _client_config(server_id: str = "srv") -> ClientConfig:
    return ClientConfig(server_id=server_id, transport="stdio", command=["true"])


def _def(server_id: str, default_scope: str = "global-sticky",
         required_init: List[str] | None = None) -> MCPServerDefinition:
    schema = [InitSchemaField(name=n, required=True) for n in (required_init or [])]
    return MCPServerDefinition(
        server_id=server_id,
        display_name=server_id,
        description="",
        client=_client_config(server_id),
        init_schema=schema,
        default_scope=default_scope,
    )


def _tool(server_id: str, name: str, path: str | None = None) -> DownstreamTool:
    t = DownstreamTool(
        server_id=server_id,
        tool_name=name,
        description="",
        input_schema={},
        namespace=server_id,
    )
    t.path = path or f"{server_id}/{name}"
    return t


def _mock_client(tools: List[DownstreamTool]) -> MagicMock:
    c = MagicMock()
    c.ensure_initialized = AsyncMock(return_value=None)
    c.refresh_tools = AsyncMock(return_value=tools)
    c.close = AsyncMock(return_value=None)
    return c


def _patch_build_client(monkeypatch, tool_factory) -> List[MagicMock]:
    created: List[MagicMock] = []

    def fake_build(cfg):
        client = _mock_client(tool_factory(cfg))
        created.append(client)
        return client

    import gateway.registry as registry_mod
    monkeypatch.setattr(registry_mod, "build_client", fake_build)
    return created


def _run(coro):
    return asyncio.new_event_loop().run_until_complete(coro)


def test_session_deploy_is_invisible_to_other_sessions(monkeypatch) -> None:
    server = _def("srv", default_scope="session")
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg.deploy_server("srv", scope="session", session_id="A"))

    # Session A sees it deployed, session B does not.
    a_view = reg.browse_servers(session_id="A")[0]
    b_view = reg.browse_servers(session_id="B")[0]
    assert a_view["deployed_in_session"] is True
    assert a_view["deployed_globally"] is False
    assert b_view["deployed_in_session"] is False
    assert b_view["deployed_globally"] is False


def test_session_tools_isolated_from_other_session(monkeypatch) -> None:
    server = _def("srv", default_scope="session")
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg.deploy_server("srv", scope="session", session_id="A"))

    assert reg.find_tool("srv/echo", session_id="A") is not None
    assert reg.find_tool("srv/echo", session_id="B") is None


def test_global_deploy_visible_to_all_sessions(monkeypatch) -> None:
    server = _def("srv", default_scope="global")
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg.deploy_server("srv", scope="global"))

    assert reg.find_tool("srv/echo", session_id="A") is not None
    assert reg.find_tool("srv/echo", session_id="B") is not None
    assert reg.browse_servers(session_id="A")[0]["deployed_globally"] is True


def test_session_deployment_shadows_global_for_its_session(monkeypatch) -> None:
    server = _def("srv", default_scope="global")
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())

    call_counter = {"n": 0}

    def factory(cfg):
        call_counter["n"] += 1
        tag = f"v{call_counter['n']}"
        t = _tool("srv", "echo", path="srv/echo")
        t.description = tag
        return [t]

    _patch_build_client(monkeypatch, factory)

    _run(reg.deploy_server("srv", scope="global"))
    _run(reg.deploy_server("srv", scope="session", session_id="A"))

    # Session A sees its session-scoped version (v2); session B sees the global (v1).
    a_tool = reg.find_tool("srv/echo", session_id="A")
    b_tool = reg.find_tool("srv/echo", session_id="B")
    assert a_tool.description == "v2"
    assert b_tool.description == "v1"


def test_global_sticky_persists_last_init_and_auto_redeploys(tmp_path: Path, monkeypatch) -> None:
    server = _def("srv", default_scope="global-sticky")
    # Declare "url" in the init_schema so validate_init keeps it.
    server.init_schema = [InitSchemaField(name="url")]
    store = LastInitStore(tmp_path)
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock(), last_init_store=store)
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg.deploy_server("srv", scope="global-sticky", init_values={"url": "x"}))

    # Init values are persisted to disk.
    assert store.load() == {"srv": {"url": "x"}}

    # New registry instance starts fresh but loads persisted init.
    server2 = _def("srv", default_scope="global-sticky")
    server2.init_schema = [InitSchemaField(name="url")]
    reg2 = ToolRegistry(servers={"srv": server2}, matcher=MagicMock(), last_init_store=store)
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])
    _run(reg2._auto_redeploy_sticky())

    assert "srv" in reg2.global_deployments
    assert reg2.global_deployments["srv"].init_values == {"url": "x"}


def test_global_sticky_auto_redeploy_records_error_when_saved_init_insufficient(
        tmp_path: Path, monkeypatch) -> None:
    # Saved init is stale: schema now requires api_key but disk only has url.
    server = _def("srv", default_scope="global-sticky", required_init=["api_key"])
    store = LastInitStore(tmp_path)
    store.set("srv", {"url": "x"})

    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock(), last_init_store=store)
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg._auto_redeploy_sticky())

    assert "srv" not in reg.global_deployments
    err = reg.global_errors.get("srv")
    assert err is not None
    assert "api_key" in err.missing_required_init


def test_global_sticky_never_deployed_is_left_alone(tmp_path: Path, monkeypatch) -> None:
    # No persisted last_init → no resurrection attempt → no error recorded.
    server = _def("srv", default_scope="global-sticky")
    store = LastInitStore(tmp_path)
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock(), last_init_store=store)
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg._auto_redeploy_sticky())

    assert "srv" not in reg.global_deployments
    assert reg.global_errors.get("srv") is None


def test_session_error_shadows_global_for_describe(monkeypatch) -> None:
    server = _def("srv", default_scope="session", required_init=["api_key"])
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())

    # Record a global error manually (no required init available globally).
    reg._record_error("srv", "global", None, "global boom", ["api_key"])
    # Session-scoped error.
    reg._record_error("srv", "session", "A", "session boom", ["api_key"])

    view_a = reg.describe_server("srv", session_id="A")
    view_b = reg.describe_server("srv", session_id="B")

    assert view_a["last_error"]["scope"] == "session"
    assert view_a["last_error"]["message"] == "session boom"
    # Session B has no session-scoped error; falls back to global.
    assert view_b["last_error"]["scope"] == "global"
    assert view_b["last_error"]["message"] == "global boom"


def test_successful_deploy_clears_prior_error(monkeypatch) -> None:
    server = _def("srv", default_scope="global")
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    reg._record_error("srv", "global", None, "prior failure", [])
    assert reg.global_errors.get("srv") is not None

    _run(reg.deploy_server("srv", scope="global"))

    assert reg.global_errors.get("srv") is None


def test_undeploy_scope_required_as_kwarg() -> None:
    server = _def("srv")
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())
    with pytest.raises(TypeError):
        _run(reg.undeploy_server("srv"))  # missing scope=...


def test_session_idle_expiry_does_not_touch_global(monkeypatch) -> None:
    server = _def("srv", default_scope="global")
    server.idle_timeout_seconds = 0  # instant expiry
    reg = ToolRegistry(servers={"srv": server}, matcher=MagicMock())
    _patch_build_client(monkeypatch, lambda cfg: [_tool("srv", "echo")])

    _run(reg.deploy_server("srv", scope="global"))
    _run(reg.deploy_server("srv", scope="session", session_id="A"))

    # Nudge last_used_at back so both look expired.
    for d in reg.global_deployments.values():
        d.last_used_at = 0
    for sess in reg.session_deployments.values():
        for d in sess.values():
            d.last_used_at = 0

    _run(reg.expire_idle_servers())

    # Both gone once timeout is 0, but importantly each was reaped via its own
    # map — no cross-contamination.
    assert "srv" not in reg.global_deployments
    assert "A" not in reg.session_deployments


def test_missing_required_init_helper() -> None:
    sd = _def("srv", required_init=["a", "b"])
    assert sd.missing_required_init({}) == ["a", "b"]
    assert sd.missing_required_init({"a": "x"}) == ["b"]
    assert sd.missing_required_init({"a": "x", "b": "y"}) == []
