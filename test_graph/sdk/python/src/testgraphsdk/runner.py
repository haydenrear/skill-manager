"""Decorator that wires a Python function into the validation graph.

Mirrors the Java :class:`Node` runner. The same script handles two modes:

- ``--describe-out=<path>`` : serialize the spec JSON to ``<path>`` and
  exit 0 (no body, no context).
- ``--result-out=<path>``  : parse :class:`NodeContext`, invoke the
  body, write the resulting :class:`NodeResult` JSON to ``<path>``, and
  exit 0.

The envelope under ``reportDir/envelope/`` is no longer this script's
responsibility — the build-logic ``PlanExecutor`` post-processes the
result-out file into the canonical envelope, stamping executor-
measured timing, recording the captured-stdout-log path, and
synthesizing a fallback envelope when this script never gets to write
its result. Same shape as the Java SDK, by design.
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from opentelemetry.context import attach, detach
from tracing_skill_observability import (
    configure_observability,
    extract_trace_context,
    flush_observability,
    get_logger,
    get_meter,
    span,
)

from .context import NodeContext
from .node_spec import NodeSpec
from .result import NodeResult


def node(spec: NodeSpec) -> Callable[[Callable[[NodeContext], NodeResult]], Callable[[], None]]:
    def decorate(body: Callable[[NodeContext], NodeResult]) -> Callable[[], None]:
        def wrapper() -> None:
            describe_out = _find_arg(sys.argv[1:], "--describe-out=")
            if describe_out is not None:
                out = Path(describe_out)
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_text(spec.to_json())
                sys.exit(0)

            parent_token, instruments = _configure_node_observability(spec)
            try:
                ctx = NodeContext.parse()
                if ctx.node_id != spec.id:
                    raise RuntimeError(
                        f"spec/runtime id mismatch: spec={spec.id!r}, arg={ctx.node_id!r}"
                    )

                _record_node_event(
                    "test_graph.node.start",
                    spec,
                    instruments,
                    counter="started",
                )
                started = datetime.now(timezone.utc)
                started_monotonic = time.monotonic()
                try:
                    result = body(ctx)
                except BaseException as exc:
                    result = NodeResult.error(spec.id, exc)
                ended = datetime.now(timezone.utc)
                result._stamp(started, ended)

                _record_node_event(
                    "test_graph.node.result",
                    spec,
                    instruments,
                    counter="completed",
                    status=result.status.value,
                    duration_ms=(time.monotonic() - started_monotonic) * 1_000,
                )
                result_out = _find_arg(sys.argv[1:], "--result-out=")
                if result_out is not None:
                    _write_result_out(result_out, result)
            finally:
                if parent_token is not None:
                    try:
                        detach(parent_token)
                    except Exception:
                        pass
                # One bounded terminal request per finite node process.
                try:
                    flush_observability(timeout_millis=5_000)
                except Exception:
                    pass
            # Exit 0 regardless of status: the executor decides pass/fail
            # from the parsed envelope's status field. Mirrors the Java
            # SDK's policy.
            sys.exit(0)

        wrapper.__wrapped__ = body  # type: ignore[attr-defined]
        return wrapper

    return decorate


def _configure_node_observability(spec: NodeSpec) -> tuple[object | None, dict[str, Any]]:
    token = None
    try:
        configure_observability(
            service_name="test-graph-node-python",
            service_version="0.1.0",
            otlp_endpoint=os.environ.get(
                "OTEL_EXPORTER_OTLP_ENDPOINT",
                "http://localhost:4318",
            ),
            log_mode="otlp-only",
        )
        carrier = {
            key: value
            for key in ("traceparent", "tracestate")
            if (value := os.environ.get(key))
        }
        token = attach(extract_trace_context(carrier))
        meter = get_meter("testgraphsdk.python", "0.1.0")
        return token, {
            "started": meter.create_counter("test_graph.node.started"),
            "completed": meter.create_counter("test_graph.node.completed"),
            "duration": meter.create_histogram(
                "test_graph.node.duration",
                unit="ms",
            ),
        }
    except Exception:
        get_logger(__name__).exception("test_graph.node.observability_configure_failed")
        return token, {}


def _record_node_event(
    name: str,
    spec: NodeSpec,
    instruments: dict[str, Any],
    *,
    counter: str,
    status: str | None = None,
    duration_ms: float | None = None,
) -> None:
    attributes = {
        "test_graph.node.id": spec.id,
        "test_graph.node.runtime": "uv",
    }
    if status is not None:
        attributes["test_graph.node.status"] = status
    try:
        with span(name, **attributes):
            selected_counter = instruments.get(counter)
            if selected_counter is not None:
                selected_counter.add(1, attributes)
            duration = instruments.get("duration")
            if duration is not None and duration_ms is not None:
                duration.record(duration_ms, attributes)
            get_logger(__name__).info(name, extra=attributes)
    except Exception:
        get_logger(__name__).exception("test_graph.node.observability_emit_failed")


def _write_result_out(path: str, result: NodeResult) -> None:
    try:
        out = Path(path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result.to_dict(), indent=2))
    except OSError as exc:
        # The executor detects a missing / empty result-out and
        # synthesizes an error envelope, so a write failure here
        # downgrades the run to a synthesized envelope — never loses it.
        sys.stderr.write(f"failed to write --result-out: {exc}\n")


def _find_arg(argv: list[str], prefix: str) -> str | None:
    for a in argv:
        if a.startswith(prefix):
            return a[len(prefix):]
    return None
