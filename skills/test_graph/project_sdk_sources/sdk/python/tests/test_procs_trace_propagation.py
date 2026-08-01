"""The node -> subprocess half of `test-graph-subprocess-w3c-propagation`.

A node process receives the graph's W3C context in its own environment. These
tests pin what happens to the *next* hop: everything the node itself launches
through :mod:`testgraphsdk.procs` must be able to join the same trace, including
when the caller supplies its own environment mapping.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import pytest
from opentelemetry.context import attach, detach
from tracing_skill_observability import extract_trace_context

from testgraphsdk import NodeContext, procs

TRACE_ID = "0af7651916cd43dd8448eb211c80319c"
TRACEPARENT = f"00-{TRACE_ID}-b7ad6b7169203331-01"

# The consumer's package __init__ pulls in the whole ACP server stack, so the
# shipped observability module is loaded directly from its own file. It is the
# same source the ACP process imports; only the package import is bypassed.
REPORT_CHILD = (
    "import json, os, importlib.util as u;"
    "s = u.spec_from_file_location('acp_observability', os.environ['ACP_OBSERVABILITY']);"
    "m = u.module_from_spec(s); s.loader.exec_module(m);"
    "carrier = m.environment_carrier();"
    "print(json.dumps({'carrier': carrier,"
    " 'trace_id': m.trace_id_from_carrier(carrier)}))"
)

ECHO_CHILD = (
    "import json, os;"
    "print(json.dumps({k: os.environ.get(k)"
    " for k in ('traceparent', 'tracestate')}))"
)


@pytest.fixture
def node_context(tmp_path: Path) -> NodeContext:
    return NodeContext(node_id="probe", run_id="run-1", report_dir=tmp_path)


@pytest.fixture
def active_graph_context(monkeypatch):
    """Reproduce what the executor hands a node: a W3C carrier in the
    environment, activated by the runner before the node body runs."""
    monkeypatch.setenv("traceparent", TRACEPARENT)
    token = attach(extract_trace_context({"traceparent": TRACEPARENT}))
    try:
        yield
    finally:
        detach(token)


def _child_payload(ctx: NodeContext, label: str) -> dict:
    return json.loads(procs.log_file(ctx, label).read_text().strip().splitlines()[-1])


def test_inherited_environment_reaches_the_child(node_context, active_graph_context):
    record = procs.run(
        node_context,
        "inherited",
        [sys.executable, "-c", ECHO_CHILD],
    )

    assert record.exit_code == 0
    assert _child_payload(node_context, "inherited")["traceparent"] == TRACEPARENT


def test_caller_supplied_environment_still_reaches_the_child(
    node_context,
    active_graph_context,
):
    """A node that builds its own environment must not silently drop the trace.

    This is the one hop plain process inheritance does not cover.
    """
    record = procs.run(
        node_context,
        "explicit-env",
        [sys.executable, "-c", ECHO_CHILD],
        env={"PATH": os.environ.get("PATH", "")},
    )

    assert record.exit_code == 0
    assert _child_payload(node_context, "explicit-env")["traceparent"] == TRACEPARENT


def test_an_instrumented_consumer_joins_the_same_trace_id(
    node_context,
    active_graph_context,
):
    """The consumer side of the contract, read through ACP's own reader.

    `acp_process.observability.environment_carrier()` is the shipped consumer
    entry point; it must resolve to exactly the node's trace.
    """
    consumer = _acp_observability_module()
    if consumer is None:
        pytest.skip("acp-cdc-ai-python sources are not present in this checkout")

    record = procs.run(
        node_context,
        "acp-consumer",
        [sys.executable, "-c", REPORT_CHILD],
        env={
            "PATH": os.environ.get("PATH", ""),
            "ACP_OBSERVABILITY": str(consumer),
        },
    )

    assert record.exit_code == 0
    payload = _child_payload(node_context, "acp-consumer")
    assert payload["carrier"]["traceparent"] == TRACEPARENT
    assert payload["trace_id"] == TRACE_ID


def test_context_injection_failure_never_breaks_the_child(
    node_context,
    active_graph_context,
    monkeypatch,
):
    def explode(_carrier=None):
        raise RuntimeError("propagator unavailable")

    monkeypatch.setattr(procs, "inject_trace_context", explode)

    record = procs.run(
        node_context,
        "fail-open",
        [sys.executable, "-c", ECHO_CHILD],
    )

    assert record.exit_code == 0
    assert record.error is None


def _acp_observability_module() -> Path | None:
    relative = Path(
        "constituents/acp-cdc-ai-python/scripts/sources/acp_process/observability.py"
    )
    for parent in Path(__file__).resolve().parents:
        candidate = parent / relative
        if candidate.is_file():
            return candidate
    return None
