from __future__ import annotations

import asyncio
import contextlib
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import httpx

from gateway.clients import build_client
from gateway.models import ClientConfig


REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE_SERVER = REPO_ROOT / "tests" / "fixtures" / "downstream_mcp_server.py"


def test_gateway_proxies_stdio_backend() -> None:
    gateway_port = _unused_tcp_port()
    gateway_url = f"http://127.0.0.1:{gateway_port}/mcp"

    with contextlib.ExitStack() as stack:
        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "gateway-config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "clients": [
                            {
                                "server_id": "fixture_stdio",
                                "transport": "stdio",
                                "command": [
                                    sys.executable,
                                    str(FIXTURE_SERVER),
                                    "--transport",
                                    "stdio",
                                    "--name",
                                    "fixture-stdio",
                                ],
                                "tool_path_prefix": "fixtures/stdio",
                                "request_timeout_seconds": 10,
                            }
                        ]
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )

            gateway_proc = stack.enter_context(
                _managed_process(
                    [
                        sys.executable,
                        "-m",
                        "gateway.server",
                        "--config",
                        str(config_path),
                        "--host",
                        "127.0.0.1",
                        "--port",
                        str(gateway_port),
                    ]
                )
            )
            _wait_for_http_ready(gateway_proc, f"http://127.0.0.1:{gateway_port}/health")

            asyncio.run(_assert_stdio_proxy_flow(gateway_url))


def test_gateway_end_to_end_with_stdio_streamable_http_and_sse_backends() -> None:
    http_port = _unused_tcp_port()
    sse_port = _unused_tcp_port()
    gateway_port = _unused_tcp_port()

    http_url = f"http://127.0.0.1:{http_port}"
    sse_url = f"http://127.0.0.1:{sse_port}"
    gateway_url = f"http://127.0.0.1:{gateway_port}/mcp"

    with contextlib.ExitStack() as stack:
        http_proc = stack.enter_context(
            _managed_process(
                [
                    sys.executable,
                    str(FIXTURE_SERVER),
                    "--transport",
                    "streamable-http",
                    "--name",
                    "fixture-http",
                    "--port",
                    str(http_port),
                ]
            )
        )
        _wait_for_http_ready(http_proc, f"{http_url}/health")

        sse_proc = stack.enter_context(
            _managed_process(
                [
                    sys.executable,
                    str(FIXTURE_SERVER),
                    "--transport",
                    "sse",
                    "--name",
                    "fixture-sse",
                    "--port",
                    str(sse_port),
                ]
            )
        )
        _wait_for_http_ready(sse_proc, f"{sse_url}/health")

        with tempfile.TemporaryDirectory() as tmp_dir:
            config_path = Path(tmp_dir) / "gateway-config.json"
            config_path.write_text(
                json.dumps(
                    {
                        "mcp_servers": [],
                        "clients": [
                            {
                                "server_id": "fixture_stdio",
                                "transport": "stdio",
                                "command": [
                                    sys.executable,
                                    str(FIXTURE_SERVER),
                                    "--transport",
                                    "stdio",
                                    "--name",
                                    "fixture-stdio",
                                ],
                                "tool_path_prefix": "fixtures/stdio",
                                "request_timeout_seconds": 10,
                            },
                            {
                                "server_id": "fixture_http",
                                "transport": "streamable-http",
                                "url": f"{http_url}/mcp",
                                "tool_path_prefix": "fixtures/http",
                                "request_timeout_seconds": 10,
                            },
                            {
                                "server_id": "fixture_sse",
                                "transport": "sse",
                                "url": f"{sse_url}/sse",
                                "tool_path_prefix": "fixtures/sse",
                                "request_timeout_seconds": 10,
                            },
                        ]
                    },
                    indent=2,
                ),
                encoding="utf-8",
            )

            gateway_proc = stack.enter_context(
                _managed_process(
                    [
                        sys.executable,
                        "-m",
                        "gateway.server",
                        "--config",
                        str(config_path),
                        "--host",
                        "127.0.0.1",
                        "--port",
                        str(gateway_port),
                    ]
                )
            )
            _wait_for_http_ready(gateway_proc, f"http://127.0.0.1:{gateway_port}/health")

            asyncio.run(_assert_gateway_end_to_end(gateway_url))


async def _assert_gateway_end_to_end(gateway_url: str) -> None:
    gateway_client = build_client(
        ClientConfig(
            server_id="gateway",
            transport="streamable-http",
            url=gateway_url,
            headers={"x-session-id": "gateway-e2e-session"},
            request_timeout_seconds=10,
        )
    )

    try:
        tools = await gateway_client.refresh_tools()
        tool_names = {tool.tool_name for tool in tools}
        assert {"browse_active_tools", "search_tools", "describe_tool", "invoke_tool", "refresh_registry"}.issubset(
            tool_names
        )

        refreshed = _extract_structured_content(await gateway_client.call_tool("refresh_registry", {}))
        assert refreshed["refreshed"] is True
        assert refreshed["tool_count"] >= 3

        browse_result = _extract_structured_content(
            await gateway_client.call_tool(
                "browse_active_tools",
                {
                    "path_prefix": "fixtures",
                    "limit": 20,
                },
            )
        )
        paths = {item["path"] for item in browse_result["items"]}
        assert {
            "fixtures/stdio/echo",
            "fixtures/http/echo",
            "fixtures/sse/echo",
        }.issubset(paths)

        search_result = _extract_structured_content(
            await gateway_client.call_tool(
                "search_tools",
                {
                    "query": "echo",
                    "limit": 10,
                },
            )
        )
        assert len(search_result["matches"]) >= 3

        unauthorized_stdio_invoke = _extract_structured_content(
            await gateway_client.call_tool(
                "invoke_tool",
                {
                    "tool_path": "fixtures/stdio/echo",
                    "arguments": {
                        "message": "should fail before describe",
                    },
                },
            )
        )
        assert "Tool invocation denied" in unauthorized_stdio_invoke

        for tool_path, expected_transport, expected_server in [
            ("fixtures/stdio/echo", "stdio", "fixture-stdio"),
            ("fixtures/http/echo", "streamable-http", "fixture-http"),
            ("fixtures/sse/echo", "sse", "fixture-sse"),
        ]:
            described = _extract_structured_content(
                await gateway_client.call_tool(
                    "describe_tool",
                    {
                        "tool_path": tool_path,
                    },
                )
            )
            assert described["tool_path"] == tool_path
            assert described["tool_name"] == "echo"

            proxied_call = _extract_structured_content(
                await gateway_client.call_tool(
                    "invoke_tool",
                    {
                        "tool_path": tool_path,
                        "arguments": {
                            "message": f"hello from {expected_transport}",
                        },
                    },
                )
            )
            proxied_payload = _extract_structured_content(proxied_call)
            assert proxied_payload == {
                "transport": expected_transport,
                "server": expected_server,
                "message": f"hello from {expected_transport}",
            }
    finally:
        await gateway_client.close()


async def _assert_stdio_proxy_flow(gateway_url: str) -> None:
    gateway_client = build_client(
        ClientConfig(
            server_id="gateway",
            transport="streamable-http",
            url=gateway_url,
            headers={"x-session-id": "gateway-stdio-session"},
            request_timeout_seconds=10,
        )
    )

    try:
        tools = await gateway_client.refresh_tools()
        tool_names = {tool.tool_name for tool in tools}
        assert {"browse_active_tools", "search_tools", "describe_tool", "invoke_tool", "refresh_registry"}.issubset(
            tool_names
        )

        browse_result = _extract_structured_content(
            await gateway_client.call_tool(
                "browse_active_tools",
                {
                    "path_prefix": "fixtures/stdio",
                    "limit": 10,
                },
            )
        )
        assert [
            {"path": item["path"], "tool_name": item["tool_name"], "description": item["description"]}
            for item in browse_result["items"]
        ] == [
            {
                "path": "fixtures/stdio/echo",
                "tool_name": "echo",
                "description": "",
            }
        ]

        unauthorized_invoke = _extract_structured_content(
            await gateway_client.call_tool(
                "invoke_tool",
                {
                    "tool_path": "fixtures/stdio/echo",
                    "arguments": {
                        "message": "blocked-before-describe",
                    },
                },
            )
        )
        assert "Tool invocation denied" in unauthorized_invoke

        described = _extract_structured_content(
            await gateway_client.call_tool(
                "describe_tool",
                {
                    "tool_path": "fixtures/stdio/echo",
                },
            )
        )
        assert described["tool_path"] == "fixtures/stdio/echo"
        assert described["tool_name"] == "echo"

        invoked = _extract_structured_content(
            await gateway_client.call_tool(
                "invoke_tool",
                {
                    "tool_path": "fixtures/stdio/echo",
                    "arguments": {
                        "message": "hello from stdio-only test",
                    },
                },
            )
        )
        payload = _extract_structured_content(invoked)
        assert payload == {
            "transport": "stdio",
            "server": "fixture-stdio",
            "message": "hello from stdio-only test",
        }
    finally:
        await gateway_client.close()


@contextlib.contextmanager
def _managed_process(args: list[str]) -> Iterator[subprocess.Popen[str]]:
    env = dict(os.environ)
    env["PYTHONUNBUFFERED"] = "1"
    proc = subprocess.Popen(
        args,
        cwd=REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=env,
    )
    try:
        yield proc
    finally:
        _stop_process(proc)


def _stop_process(proc: subprocess.Popen[str]) -> None:
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)
    if proc.stdout is not None:
        with contextlib.suppress(Exception):
            proc.stdout.close()


def _wait_for_http_ready(proc: subprocess.Popen[str], url: str, timeout_seconds: float = 20.0) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None

    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise AssertionError(f"Process exited before becoming ready for {url}:\n{_process_output(proc)}")
        try:
            response = httpx.get(url, timeout=1.0)
            if response.status_code == 200:
                return
        except Exception as exc:  # pragma: no cover - exercised only during startup races.
            last_error = exc
        time.sleep(0.2)

    raise AssertionError(f"Timed out waiting for {url}. Last error: {last_error}")


def _process_output(proc: subprocess.Popen[str]) -> str:
    if proc.stdout is None:
        return ""
    try:
        return proc.stdout.read() or ""
    except Exception:
        return ""


def _extract_structured_content(result: dict[str, Any]) -> Any:
    if "structuredContent" in result:
        return result["structuredContent"]
    content = result.get("content") or []
    if len(content) == 1 and content[0].get("type") == "text":
        text = content[0].get("text", "")
        try:
            return json.loads(text)
        except Exception:
            return text
    return result


def _unused_tcp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        sock.listen(1)
        return int(sock.getsockname()[1])
