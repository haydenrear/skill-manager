from __future__ import annotations

import asyncio
import logging
import os
import time
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from typing import Any, Iterator, Mapping

import pytest
from starlette.testclient import TestClient

import gateway.clients as clients_module
import gateway.observability as observability_module
from gateway.clients import (
    SSEMCPClient,
    StdioMCPClient,
    StreamableHTTPMCPClient,
)
from gateway.config import GatewayConfigModel
from gateway.models import ClientConfig
from gateway.observability import (
    DEFAULT_FLUSH_TIMEOUT_MILLIS,
    DisabledGatewayObservability,
    GatewayObservability,
    RequestTrace,
    configure_gateway_observability,
    w3c_carrier,
)
from gateway.provisioning import Provisioner
from gateway.server import (
    GatewayServer,
    _forwardable_headers,
    _observability_method,
    _observability_route,
    _scope_with_trace_context,
)

TRACE_ID = "1234567890abcdef1234567890abcdef"
PARENT_TRACEPARENT = f"00-{TRACE_ID}-1111111111111111-01"
CHILD_TRACEPARENT = f"00-{TRACE_ID}-2222222222222222-01"
TRACESTATE = "vendor=value"
PROCESS_TRACE_ID = "abcdefabcdefabcdefabcdefabcdefab"
PROCESS_TRACEPARENT = (
    f"00-{PROCESS_TRACE_ID}-3333333333333333-01"
)


@pytest.fixture(autouse=True)
def _isolate_process_trace_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for key in ("traceparent", "tracestate", "TRACEPARENT", "TRACESTATE"):
        monkeypatch.delenv(key, raising=False)


class _FakeInstrument:
    def __init__(self) -> None:
        self.records: list[tuple[Any, dict[str, Any]]] = []

    def add(self, value: Any, attributes: Mapping[str, Any]) -> None:
        self.records.append((value, dict(attributes)))

    def record(self, value: Any, attributes: Mapping[str, Any]) -> None:
        self.records.append((value, dict(attributes)))


class _FakeMeter:
    def __init__(self) -> None:
        self.counter = _FakeInstrument()
        self.histogram = _FakeInstrument()
        self.created: list[tuple[str, str, str]] = []

    def create_counter(
        self,
        name: str,
        *,
        unit: str,
        description: str,
    ) -> _FakeInstrument:
        self.created.append((name, unit, description))
        return self.counter

    def create_histogram(
        self,
        name: str,
        *,
        unit: str,
        description: str,
    ) -> _FakeInstrument:
        self.created.append((name, unit, description))
        return self.histogram


class _FakeHandle:
    def __init__(self) -> None:
        self.extracted: list[dict[str, str]] = []
        self.flush_timeouts: list[int] = []

    def extract(self, carrier: Mapping[str, str] | None) -> dict[str, str]:
        normalized = dict(carrier or {})
        self.extracted.append(normalized)
        return normalized

    def inject(self, carrier: dict[str, str] | None = None) -> dict[str, str]:
        target = carrier if carrier is not None else {}
        target["traceparent"] = CHILD_TRACEPARENT
        target["tracestate"] = TRACESTATE
        return target

    def flush(self, timeout_millis: int = 5_000) -> bool:
        self.flush_timeouts.append(timeout_millis)
        return True


class _RecordingSink:
    def __init__(self, *, fail_flush: bool = False) -> None:
        self.begin_headers: list[dict[str, str]] = []
        self.activation_carriers: list[dict[str, str]] = []
        self.finishes: list[tuple[int, str]] = []
        self.flush_timeouts: list[int] = []
        self.fail_flush = fail_flush

    def begin_request(
        self,
        headers: Mapping[str, str],
        *,
        method: str,
        route: str,
    ) -> RequestTrace:
        self.begin_headers.append(dict(headers))
        return RequestTrace(
            carrier={
                "traceparent": CHILD_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
            trace_id=TRACE_ID,
            started_at=time.monotonic(),
            method=method,
            route=route,
        )

    @contextmanager
    def activate(self, request_trace: RequestTrace) -> Iterator[None]:
        self.activation_carriers.append(dict(request_trace.carrier))
        yield

    def finish_request(
        self,
        request_trace: RequestTrace,
        *,
        status_code: int,
        outcome: str,
    ) -> None:
        del request_trace
        self.finishes.append((status_code, outcome))

    def flush(self, timeout_millis: int = 5_000) -> bool:
        self.flush_timeouts.append(timeout_millis)
        if self.fail_flush:
            raise RuntimeError("telemetry unavailable")
        return True


def _gateway(tmp_path: Path, sink: Any) -> GatewayServer:
    config = GatewayConfigModel.model_validate({"mcp_servers": []})
    return GatewayServer(
        config=config,
        provisioner=Provisioner(tmp_path / "data"),
        observability=sink,
    )


def test_configures_shared_provider_with_all_signals_by_default(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    handle = _FakeHandle()
    meter = _FakeMeter()
    calls: list[dict[str, Any]] = []

    def fake_configure(**kwargs: Any) -> _FakeHandle:
        calls.append(kwargs)
        return handle

    monkeypatch.setattr(
        observability_module,
        "configure_observability",
        fake_configure,
    )
    monkeypatch.setattr(observability_module, "get_meter", lambda *_: meter)
    monkeypatch.setattr(
        observability_module,
        "get_logger",
        lambda *_: logging.getLogger("test.gateway.observability"),
    )
    marker_names: list[str] = []

    @contextmanager
    def fake_span(name: str, **_: Any) -> Iterator[None]:
        marker_names.append(name)
        yield

    monkeypatch.setattr(observability_module, "span", fake_span)
    monkeypatch.setattr(observability_module, "attach", lambda _: 1)
    monkeypatch.setattr(observability_module, "detach", lambda _: None)
    monkeypatch.setenv("TRACEPARENT", PARENT_TRACEPARENT)
    monkeypatch.setenv("TRACESTATE", TRACESTATE)
    monkeypatch.delenv("VMG_OBSERVABILITY_LOG_MODE", raising=False)

    sink = configure_gateway_observability("debug")

    assert isinstance(sink, GatewayObservability)
    assert calls == [
        {
            "service_name": "virtual-mcp-gateway",
            "service_version": "0.18.1",
            "log_level": "debug",
            "log_mode": "otlp",
            "metrics_enabled": True,
        }
    ]
    assert [entry[:2] for entry in meter.created] == [
        ("virtual_mcp_gateway.request.count", "{request}"),
        ("virtual_mcp_gateway.request.duration", "s"),
    ]
    assert marker_names == ["virtual_mcp_gateway.process.started"]
    assert handle.extracted == [
        {
            "traceparent": PARENT_TRACEPARENT,
            "tracestate": TRACESTATE,
        }
    ]
    assert os.environ["traceparent"] == CHILD_TRACEPARENT
    assert os.environ["tracestate"] == TRACESTATE


def test_configuration_failure_is_fail_open(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_configuration(**_: Any) -> None:
        raise RuntimeError("collector setup failed")

    monkeypatch.setattr(
        observability_module,
        "configure_observability",
        fail_configuration,
    )

    sink = configure_gateway_observability()

    assert isinstance(sink, DisabledGatewayObservability)
    trace = sink.begin_request(
        {"traceparent": PARENT_TRACEPARENT},
        method="GET",
        route="/health",
    )
    assert trace.trace_id == TRACE_ID


def test_request_markers_share_the_pinned_trace_without_spanning_the_wait(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    handle = _FakeHandle()
    meter = _FakeMeter()
    active_contexts: list[dict[str, str]] = []
    marker_events: list[tuple[str, str, dict[str, str] | None]] = []

    def fake_attach(context: dict[str, str]) -> int:
        active_contexts.append(context)
        return len(active_contexts)

    def fake_detach(_: int) -> None:
        active_contexts.pop()

    @contextmanager
    def fake_span(name: str, **_: Any) -> Iterator[None]:
        active = dict(active_contexts[-1]) if active_contexts else None
        marker_events.append(("enter", name, active))
        yield
        marker_events.append(("exit", name, active))

    monkeypatch.setattr(observability_module, "attach", fake_attach)
    monkeypatch.setattr(observability_module, "detach", fake_detach)
    monkeypatch.setattr(observability_module, "span", fake_span)
    monkeypatch.setattr(
        observability_module,
        "current_trace_id",
        lambda: TRACE_ID,
    )
    sink = GatewayObservability(
        handle,
        meter=meter,
        logger=logging.getLogger("test.gateway.markers"),
    )
    marker_events.clear()

    request_trace = sink.begin_request(
        {
            "traceparent": PARENT_TRACEPARENT,
            "tracestate": TRACESTATE,
        },
        method="POST",
        route="/mcp",
    )
    assert marker_events == [
        (
            "enter",
            "virtual_mcp_gateway.request.accepted",
            {
                "traceparent": PARENT_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
        ),
        (
            "exit",
            "virtual_mcp_gateway.request.accepted",
            {
                "traceparent": PARENT_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
        ),
    ]

    # No recording span remains open across request work or downstream waits.
    time.sleep(0.001)
    sink.finish_request(
        request_trace,
        status_code=200,
        outcome="success",
    )

    assert marker_events[-2:] == [
        (
            "enter",
            "virtual_mcp_gateway.request.completed",
            {
                "traceparent": CHILD_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
        ),
        (
            "exit",
            "virtual_mcp_gateway.request.completed",
            {
                "traceparent": CHILD_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
        ),
    ]
    assert request_trace.trace_id == TRACE_ID
    assert handle.extracted[-1]["traceparent"].split("-")[1] == TRACE_ID
    for _, attributes in meter.counter.records + meter.histogram.records:
        assert "trace_id" not in attributes
        assert attributes["http.route"] == "/mcp"


def test_empty_provider_injection_preserves_valid_incoming_parent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class EmptyInjectHandle(_FakeHandle):
        def inject(
            self,
            carrier: dict[str, str] | None = None,
        ) -> dict[str, str]:
            return carrier if carrier is not None else {}

    @contextmanager
    def fake_span(_: str, **__: Any) -> Iterator[None]:
        yield

    monkeypatch.setattr(observability_module, "attach", lambda _: 1)
    monkeypatch.setattr(observability_module, "detach", lambda _: None)
    monkeypatch.setattr(observability_module, "span", fake_span)
    monkeypatch.setattr(observability_module, "current_trace_id", lambda: None)
    monkeypatch.setenv("TRACEPARENT", PROCESS_TRACEPARENT)

    sink = GatewayObservability(
        EmptyInjectHandle(),
        meter=_FakeMeter(),
        logger=logging.getLogger("test.gateway.empty-injection"),
    )
    request_trace = sink.begin_request(
        {"traceparent": PARENT_TRACEPARENT},
        method="POST",
        route="/mcp",
    )

    assert sink.process_carrier["traceparent"] == PROCESS_TRACEPARENT
    assert request_trace.carrier == {"traceparent": PARENT_TRACEPARENT}
    assert request_trace.trace_id == TRACE_ID


def test_http_response_exposes_trace_handle_and_shutdown_flushes_once(
    tmp_path: Path,
) -> None:
    sink = _RecordingSink()
    gateway = _gateway(tmp_path, sink)

    with TestClient(gateway.app) as client:
        response = client.get(
            "/health",
            headers={
                "traceparent": PARENT_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
        )

    assert response.status_code == 200
    assert response.json()["ok"] is True
    assert response.headers["x-trace-id"] == TRACE_ID
    assert sink.begin_headers[0]["traceparent"] == PARENT_TRACEPARENT
    assert sink.activation_carriers == [
        {
            "traceparent": CHILD_TRACEPARENT,
            "tracestate": TRACESTATE,
        }
    ]
    assert sink.finishes == [(200, "success")]
    assert sink.flush_timeouts == [DEFAULT_FLUSH_TIMEOUT_MILLIS]


def test_shutdown_flush_failure_does_not_break_server_shutdown(
    tmp_path: Path,
) -> None:
    sink = _RecordingSink(fail_flush=True)
    gateway = _gateway(tmp_path, sink)

    with TestClient(gateway.app) as client:
        assert client.get("/health").status_code == 200

    assert sink.flush_timeouts == [DEFAULT_FLUSH_TIMEOUT_MILLIS]


def test_direct_constructor_does_not_configure_global_provider(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        observability_module,
        "configure_observability",
        lambda **_: pytest.fail("direct constructor configured global telemetry"),
    )
    config = GatewayConfigModel.model_validate({"mcp_servers": []})
    gateway = GatewayServer(
        config=config,
        provisioner=Provisioner(tmp_path / "data"),
    )

    assert isinstance(gateway.observability, DisabledGatewayObservability)


@pytest.mark.parametrize(
    ("path", "route"),
    [
        ("/mcp", "/mcp"),
        ("/mcp/", "/mcp"),
        ("/health", "/health"),
        ("/servers", "/servers"),
        ("/servers/example", "/servers/{server_id}"),
        ("/missing/123", "unmatched"),
    ],
)
def test_route_dimensions_are_low_cardinality(path: str, route: str) -> None:
    assert _observability_route(path) == route


@pytest.mark.parametrize(
    ("method", "dimension"),
    [
        ("post", "POST"),
        ("GET", "GET"),
        ("attacker-defined-method-123", "OTHER"),
        ("", "OTHER"),
    ],
)
def test_method_dimensions_are_low_cardinality(
    method: str,
    dimension: str,
) -> None:
    assert _observability_method(method) == dimension


def test_scope_replaces_w3c_parent_without_changing_other_headers() -> None:
    scope = {
        "type": "http",
        "headers": [
            (b"traceparent", PARENT_TRACEPARENT.encode()),
            (b"tracestate", b"old=value"),
            (b"authorization", b"Bearer secret"),
        ],
    }

    traced = _scope_with_trace_context(
        scope,
        {
            "traceparent": CHILD_TRACEPARENT,
            "tracestate": TRACESTATE,
        },
    )
    headers = dict(traced["headers"])

    assert headers[b"traceparent"].decode() == CHILD_TRACEPARENT
    assert headers[b"tracestate"].decode() == TRACESTATE
    assert headers[b"authorization"] == b"Bearer secret"
    assert scope["headers"][0][1].decode() == PARENT_TRACEPARENT


def test_forwarding_keeps_w3c_headers_and_blocks_gateway_session_headers() -> None:
    forwarded = _forwardable_headers(
        {
            "traceparent": CHILD_TRACEPARENT,
            "tracestate": TRACESTATE,
            "authorization": "Bearer secret",
            "mcp-session-id": "gateway-owned",
            "x-session-id": "disclosure-owned",
        }
    )

    assert forwarded == {
        "traceparent": CHILD_TRACEPARENT,
        "tracestate": TRACESTATE,
        "authorization": "Bearer secret",
    }


@pytest.mark.parametrize(
    ("forwarded", "active", "expected_traceparent"),
    [
        (
            {
                "traceparent": CHILD_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
            {"traceparent": PROCESS_TRACEPARENT},
            CHILD_TRACEPARENT,
        ),
        (
            None,
            {
                "traceparent": CHILD_TRACEPARENT,
                "tracestate": TRACESTATE,
            },
            CHILD_TRACEPARENT,
        ),
        (
            None,
            {},
            PARENT_TRACEPARENT,
        ),
    ],
)
def test_http_and_sse_transports_receive_current_w3c_context(
    monkeypatch: pytest.MonkeyPatch,
    forwarded: dict[str, str] | None,
    active: dict[str, str],
    expected_traceparent: str,
) -> None:
    captured: dict[str, dict[str, str]] = {}

    class FakeAsyncClient:
        def __init__(self, **kwargs: Any) -> None:
            captured["http"] = dict(kwargs["headers"])

        async def __aenter__(self) -> "FakeAsyncClient":
            return self

        async def __aexit__(self, *_: Any) -> bool:
            return False

    @asynccontextmanager
    async def fake_streamable_http_client(*_: Any, **__: Any):
        yield ("read", "write", None)

    @asynccontextmanager
    async def fake_sse_client(
        _: str,
        *,
        headers: Mapping[str, str],
        **__: Any,
    ):
        captured["sse"] = dict(headers)
        yield ("read", "write")

    monkeypatch.setattr(clients_module.httpx, "AsyncClient", FakeAsyncClient)
    monkeypatch.setattr(
        clients_module,
        "streamable_http_client",
        fake_streamable_http_client,
    )
    monkeypatch.setattr(clients_module, "sse_client", fake_sse_client)
    monkeypatch.setattr(
        clients_module,
        "inject_trace_context",
        lambda _: dict(active),
    )
    monkeypatch.setenv("TRACEPARENT", PARENT_TRACEPARENT)
    monkeypatch.setenv("TRACESTATE", TRACESTATE)
    http_client = StreamableHTTPMCPClient(
        ClientConfig(
            server_id="http",
            transport="streamable-http",
            url="https://example.test/mcp",
            headers={"authorization": "Bearer configured"},
        )
    )
    sse_client = SSEMCPClient(
        ClientConfig(
            server_id="sse",
            transport="sse",
            url="https://example.test/sse",
            headers={"authorization": "Bearer configured"},
        )
    )

    async def exercise() -> None:
        async with http_client._transport_context(forwarded):
            pass
        async with sse_client._transport_context(forwarded):
            pass

    asyncio.run(exercise())

    for headers in captured.values():
        assert headers["traceparent"] == expected_traceparent
        assert headers["tracestate"] == TRACESTATE
        assert headers["authorization"] == "Bearer configured"


def test_stdio_spawn_environment_carries_active_w3c_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, Any] = {}

    class FakeStdioContext:
        async def __aenter__(self) -> None:
            raise RuntimeError("captured")

        async def __aexit__(self, *_: Any) -> bool:
            return False

    def fake_stdio_client(params: Any) -> FakeStdioContext:
        captured["params"] = params
        return FakeStdioContext()

    monkeypatch.setattr(clients_module, "stdio_client", fake_stdio_client)
    monkeypatch.setattr(
        clients_module,
        "inject_trace_context",
        lambda _: {
            "traceparent": CHILD_TRACEPARENT,
            "tracestate": TRACESTATE,
        },
    )
    client = StdioMCPClient(
        ClientConfig(
            server_id="stdio",
            transport="stdio",
            command=["example-mcp"],
            env={"API_TOKEN": "configured"},
        )
    )

    async def exercise() -> None:
        with pytest.raises(RuntimeError, match="captured"):
            async with client._transport_context(None):
                pass

    asyncio.run(exercise())

    environment = captured["params"].env
    assert environment["API_TOKEN"] == "configured"
    assert environment["traceparent"] == CHILD_TRACEPARENT
    assert environment["TRACEPARENT"] == CHILD_TRACEPARENT
    assert environment["tracestate"] == TRACESTATE
    assert environment["TRACESTATE"] == TRACESTATE


def test_stdio_worker_opens_session_with_first_operation_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    opened_with: list[dict[str, str] | None] = []
    client = StdioMCPClient(
        ClientConfig(
            server_id="stdio",
            transport="stdio",
            command=["example-mcp"],
        )
    )

    async def fake_open_session(
        _: Any,
        forwarded_headers: dict[str, str] | None,
    ) -> object:
        opened_with.append(forwarded_headers)
        return object()

    async def fake_call_tool(
        _: object,
        __: str,
        ___: dict[str, Any],
        forwarded_headers: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        assert forwarded_headers is not None
        return {"ok": True}

    monkeypatch.setattr(client, "_open_session", fake_open_session)
    monkeypatch.setattr(client, "_call_tool_with_session", fake_call_tool)
    forwarded = {
        "traceparent": CHILD_TRACEPARENT,
        "tracestate": TRACESTATE,
    }

    async def exercise() -> dict[str, Any]:
        try:
            return await client.call_tool(
                "example",
                {"value": 1},
                forwarded_headers=forwarded,
            )
        finally:
            await client.close()

    assert asyncio.run(exercise()) == {"ok": True}
    assert opened_with == [forwarded]


def test_stdio_tool_call_uses_standard_meta_without_removing_legacy_payload() -> None:
    captured: dict[str, Any] = {}

    class FakeSession:
        async def send_request(self, request: Any, result_type: Any) -> str:
            captured["request"] = request.model_dump(
                by_alias=True,
                mode="json",
                exclude_none=True,
            )
            captured["result_type"] = result_type
            return "unchanged-result"

    client = StdioMCPClient(
        ClientConfig(
            server_id="stdio",
            transport="stdio",
            command=["example-mcp"],
        )
    )
    forwarded = {
        "traceparent": CHILD_TRACEPARENT,
        "tracestate": TRACESTATE,
        "authorization": "Bearer secret",
    }

    result = asyncio.run(
        client._call_tool_with_session(
            FakeSession(),
            "example",
            {"value": 1},
            forwarded_headers=forwarded,
        )
    )

    params = captured["request"]["params"]
    assert result == "unchanged-result"
    assert params["_meta"] == {
        "traceparent": CHILD_TRACEPARENT,
        "tracestate": TRACESTATE,
    }
    assert params["_forwarded_headers"] == forwarded
    assert params["name"] == "example"
    assert params["arguments"] == {"value": 1}


@pytest.mark.parametrize(
    "candidate",
    [
        "",
        "not-a-traceparent",
        f"00-{'0' * 32}-2222222222222222-01",
        f"00-{TRACE_ID}-{'0' * 16}-01",
        f"ff-{TRACE_ID}-2222222222222222-01",
    ],
)
def test_invalid_traceparents_are_not_propagated(candidate: str) -> None:
    assert w3c_carrier(
        {
            "traceparent": candidate,
            "tracestate": TRACESTATE,
        }
    ) == {}


def test_invalid_tracestate_is_dropped_without_losing_valid_parent() -> None:
    assert w3c_carrier(
        {
            "traceparent": PARENT_TRACEPARENT,
            "tracestate": "invalid-\N{SNOWMAN}",
        }
    ) == {"traceparent": PARENT_TRACEPARENT}
