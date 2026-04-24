"""Regression: a transient refresh failure must not wipe a server's tool cache."""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

from gateway.models import DownstreamTool
from gateway.registry import ToolRegistry


def _tool(path: str, name: str, server_id: str) -> DownstreamTool:
    t = DownstreamTool(
        server_id=server_id,
        tool_name=name,
        description="",
        input_schema={},
        namespace=server_id,
    )
    t.path = path
    return t


def test_refresh_failure_preserves_prior_tools() -> None:
    reg = ToolRegistry(servers={}, matcher=MagicMock())

    # Pre-populate a previously successful refresh for server "A".
    tool = _tool("A/echo", "echo", "A")
    reg.global_tools_by_server["A"] = {"A/echo": tool}

    # Mock a client whose refresh_tools raises (transient downstream failure).
    bad_client = MagicMock()
    bad_client.refresh_tools = AsyncMock(side_effect=RuntimeError("transient"))
    reg.global_clients["A"] = bad_client

    asyncio.new_event_loop().run_until_complete(reg.refresh_all())

    # The prior tool entry must survive.
    assert "A/echo" in reg._visible_tools(session_id=None)
    assert reg.global_tools_by_server["A"]["A/echo"].tool_name == "echo"


def test_refresh_success_replaces_prior_tools() -> None:
    reg = ToolRegistry(servers={}, matcher=MagicMock())

    stale = _tool("A/old", "old", "A")
    reg.global_tools_by_server["A"] = {"A/old": stale}

    fresh = _tool("A/new", "new", "A")
    ok_client = MagicMock()
    ok_client.refresh_tools = AsyncMock(return_value=[fresh])
    reg.global_clients["A"] = ok_client

    asyncio.new_event_loop().run_until_complete(reg.refresh_all())

    visible = reg._visible_tools(session_id=None)
    assert "A/new" in visible
    assert "A/old" not in visible
