from __future__ import annotations

import os
import subprocess
import sys
import time
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import Mock

import pytest

from testgraphsdk import NodeResult, NodeSpec
from testgraphsdk import runner


@pytest.fixture(autouse=True)
def _replace_terminal_process_exit(monkeypatch):
    def exit_process(status: int):
        raise SystemExit(status)

    monkeypatch.setattr(runner, "_exit_process", exit_process)


def _spec() -> NodeSpec:
    return NodeSpec("probe").kind("evidence").timeout("30s")


def test_describe_mode_is_telemetry_free(monkeypatch, tmp_path):
    configured = Mock()
    monkeypatch.setattr(runner, "configure_observability", configured)
    monkeypatch.setattr(
        runner.sys,
        "argv",
        ["probe.py", f"--describe-out={tmp_path / 'describe.json'}"],
    )

    wrapped = runner.node(_spec())(lambda ctx: NodeResult.pass_(ctx.node_id))
    with pytest.raises(SystemExit) as exit_info:
        wrapped()

    assert exit_info.value.code == 0
    configured.assert_not_called()


def test_execution_extracts_w3c_context_emits_bounded_events_and_flushes_once(
    monkeypatch,
    tmp_path,
):
    result_out = tmp_path / "result.json"
    monkeypatch.setattr(
        runner.sys,
        "argv",
        [
            "probe.py",
            "--nodeId=probe",
            "--runId=run-1",
            f"--reportDir={tmp_path}",
            f"--result-out={result_out}",
        ],
    )
    monkeypatch.setenv(
        "traceparent",
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
    )
    extracted = object()
    token = object()
    extract = Mock(return_value=extracted)
    attach = Mock(return_value=token)
    detach = Mock()
    flush = Mock(return_value=True)
    spans: list[str] = []
    configured = Mock()
    monkeypatch.setattr(runner, "configure_observability", configured)
    monkeypatch.setattr(runner, "extract_trace_context", extract)
    monkeypatch.setattr(runner, "attach", attach)
    monkeypatch.setattr(runner, "detach", detach)
    monkeypatch.setattr(runner, "flush_observability", flush)
    monkeypatch.setattr(
        runner,
        "span",
        lambda name, **attributes: spans.append(name) or nullcontext(),
    )
    meter = Mock()
    monkeypatch.setattr(runner, "get_meter", Mock(return_value=meter))
    monkeypatch.setattr(runner, "get_logger", Mock(return_value=Mock()))

    wrapped = runner.node(_spec())(lambda ctx: NodeResult.pass_(ctx.node_id))
    with pytest.raises(SystemExit) as exit_info:
        wrapped()

    assert exit_info.value.code == 0
    configured.assert_called_once_with(
        service_name="test-graph-node-python",
        service_version="0.1.0",
        otlp_endpoint="http://localhost:4318",
        log_mode="otlp-only",
    )
    extract.assert_called_once_with(
        {
            "traceparent":
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
        }
    )
    attach.assert_called_once_with(extracted)
    detach.assert_called_once_with(token)
    assert spans == ["test_graph.node.start", "test_graph.node.result"]
    flush.assert_called_once_with(timeout_millis=5_000)
    assert '"status": "passed"' in result_out.read_text()


def test_telemetry_failures_do_not_replace_the_node_result(monkeypatch, tmp_path):
    result_out = tmp_path / "result.json"
    monkeypatch.setattr(
        runner.sys,
        "argv",
        [
            "probe.py",
            "--nodeId=probe",
            "--runId=run-1",
            f"--reportDir={tmp_path}",
            f"--result-out={result_out}",
        ],
    )
    monkeypatch.setattr(
        runner,
        "configure_observability",
        Mock(side_effect=RuntimeError("collector unavailable")),
    )
    monkeypatch.setattr(runner, "get_logger", Mock(return_value=Mock()))
    monkeypatch.setattr(
        runner,
        "flush_observability",
        Mock(side_effect=RuntimeError("flush unavailable")),
    )

    wrapped = runner.node(_spec())(lambda ctx: NodeResult.pass_(ctx.node_id))
    with pytest.raises(SystemExit) as exit_info:
        wrapped()

    assert exit_info.value.code == 0
    assert '"status": "passed"' in result_out.read_text()


def test_finite_node_process_exits_when_otlp_is_unavailable(tmp_path):
    result_out = tmp_path / "result.json"
    script = tmp_path / "probe.py"
    script.write_text(
        """\
from testgraphsdk import NodeResult, NodeSpec, node


@node(NodeSpec("probe").kind("evidence").timeout("30s"))
def run(ctx):
    return NodeResult.pass_(ctx.node_id)


run()
"""
    )
    sdk_src = Path(__file__).parents[1] / "src"
    env = os.environ.copy()
    env.update(
        {
            "OTEL_EXPORTER_OTLP_ENDPOINT": "http://127.0.0.1:1",
            "PYTHONPATH": os.pathsep.join(
                value
                for value in (str(sdk_src), env.get("PYTHONPATH"))
                if value
            ),
            "PYTHONUNBUFFERED": "1",
        }
    )

    started = time.monotonic()
    completed = subprocess.run(
        [
            sys.executable,
            str(script),
            "--nodeId=probe",
            "--runId=run-1",
            f"--reportDir={tmp_path}",
            f"--result-out={result_out}",
        ],
        capture_output=True,
        env=env,
        text=True,
        timeout=9,
    )
    elapsed = time.monotonic() - started

    assert completed.returncode == 0, completed.stderr
    assert elapsed < 7.5
    assert '"status": "passed"' in result_out.read_text()
