from __future__ import annotations

import json
from pathlib import Path
import runpy
import subprocess
import sys
from types import SimpleNamespace

import pytest


PROJECT_ROOT = Path(__file__).resolve().parents[3]
STANDARD_NODES = PROJECT_ROOT / "standard-nodes"

# The shipped helpers intentionally live with the standard nodes, not in the
# provider-neutral SDK package.

sys.path.insert(0, str(STANDARD_NODES))
from _support import monitoring  # noqa: E402


def _payload(*, operation: str = "status", chart_changed: bool | None = None) -> dict:
    payload = {
        "schema_version": monitoring.STATUS_SCHEMA,
        "operation": operation,
        "success": True,
        "ready": True,
        "errors": [],
        "cluster": {
            "name": "monitoring",
            "context": "k3d-monitoring",
            "exists": True,
            "running": True,
        },
        "release": {
            "name": "monitoring",
            "namespace": "monitoring",
            "status": "deployed",
            "deployed": True,
            "revision": 1,
        },
        "storage": {
            "mounted": True,
            "layout_compatible": True,
            "migration_required": False,
        },
        "workloads": {"ready": True},
        "endpoints": {
            name: {"url": url, "ready": True}
            for name, url in monitoring.HEALTH_ENDPOINTS.items()
        },
    }
    if chart_changed is not None:
        payload["chart_changed"] = chart_changed
    return payload


def _write_launcher(path: Path, stdout: str, *, exit_code: int = 0) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "#!/bin/sh\n"
        "printf '%s\\n' " + repr(stdout) + "\n"
        f"exit {exit_code}\n",
        encoding="utf-8",
    )
    path.chmod(0o755)
    return path


def _spec(path: str) -> dict:
    namespace = runpy.run_path(str(STANDARD_NODES / path))
    return json.loads(namespace["SPEC"].to_json())


def test_standard_monitoring_specs_are_exact_and_assertion_is_pure() -> None:
    ensure = _spec("monitoring_cluster_ensure.py")
    asserted = _spec("monitoring_cluster_assert_ready.py")

    assert ensure["id"] == monitoring.ENSURE_NODE_ID
    assert ensure["kind"] == "testbed"
    assert ensure["dependsOn"] == []
    assert ensure["timeout"] == "360s"
    assert ensure["retries"] == 0
    assert ensure["sideEffects"] == ["environment:provision"]
    assert "environmentRepository" not in ensure
    assert list(ensure["outputs"]) == list(monitoring.OUTPUT_NAMES)

    assert asserted["id"] == monitoring.ASSERT_READY_NODE_ID
    assert asserted["kind"] == "assertion"
    assert asserted["dependsOn"] == [monitoring.ENSURE_NODE_ID]
    assert asserted["timeout"] == "30s"
    assert asserted["retries"] == 0
    assert asserted["sideEffects"] == []
    assert "environmentRepository" not in asserted
    assert list(asserted["outputs"]) == list(monitoring.OUTPUT_NAMES)


def test_consumer_scaffold_keeps_monitoring_graph_opt_in(tmp_path) -> None:
    repo = tmp_path / "consumer"
    completed = subprocess.run(
        [sys.executable, str(PROJECT_ROOT.parent / "scripts/scaffold.py"), str(repo)],
        text=True,
        capture_output=True,
        check=False,
    )

    assert completed.returncode == 0, completed.stderr
    build = (repo / "test_graph/build.gradle.kts").read_text(encoding="utf-8")
    assert "standardMonitoringReadiness" not in build
    assert "TEST-GRAPH-PROVIDER-VALIDATION" not in build
    assert (repo / "test_graph/standard-nodes").is_dir()


def test_launcher_override_is_absolute_and_never_uses_path(monkeypatch, tmp_path) -> None:
    launcher = _write_launcher(tmp_path / "monitoring", "{}")
    monkeypatch.setenv("PATH", "")
    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", str(launcher))

    assert monitoring.resolve_monitoring_cli() == launcher.resolve()

    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", "relative/monitoring")
    with pytest.raises(RuntimeError, match="must be an absolute path"):
        monitoring.resolve_monitoring_cli()


def test_launcher_resolves_custom_and_inferred_skill_manager_home(monkeypatch, tmp_path) -> None:
    custom_home = tmp_path / "custom-home"
    custom = _write_launcher(custom_home / "bin/cli/monitoring", "{}")
    monkeypatch.delenv("TEST_GRAPH_MONITORING_CLI", raising=False)
    monkeypatch.setenv("SKILL_MANAGER_HOME", str(custom_home))
    assert monitoring.resolve_monitoring_cli() == custom.resolve()

    monkeypatch.delenv("SKILL_MANAGER_HOME")
    inferred_home = tmp_path / "inferred-home"
    inferred = _write_launcher(inferred_home / "bin/cli/monitoring", "{}")
    installed_node = (
        inferred_home
        / "skills/test-graph/project_sdk_sources/standard-nodes/monitoring_cluster_ensure.py"
    )
    installed_node.parent.mkdir(parents=True)
    installed_node.write_text("# fixture\n", encoding="utf-8")
    assert monitoring.resolve_monitoring_cli(installed_node) == inferred.resolve()


def test_explicit_skill_manager_home_never_falls_back_to_host_home(
    monkeypatch, tmp_path
) -> None:
    empty_home = tmp_path / "isolated-home"
    empty_home.mkdir()
    monkeypatch.delenv("TEST_GRAPH_MONITORING_CLI", raising=False)
    monkeypatch.setenv("SKILL_MANAGER_HOME", str(empty_home))

    with pytest.raises(RuntimeError, match="SKILL_MANAGER_HOME resolved monitoring"):
        monitoring.resolve_monitoring_cli()


def test_ready_report_and_publication_projection_preserve_contract(tmp_path) -> None:
    payload = _payload(operation="up", chart_changed=False)
    checks = monitoring.report_checks(
        payload,
        expected_operation="up",
        expected_context="k3d-monitoring",
        require_chart_changed=True,
    )
    assert checks and all(checks.values())

    kubeconfig = tmp_path / "kubeconfig"
    kubeconfig.write_text("apiVersion: v1\n", encoding="utf-8")
    outputs = monitoring.public_outputs(
        payload,
        kubeconfig=kubeconfig,
        kubecontext="k3d-monitoring",
        reused=True,
    )
    assert tuple(outputs) == monitoring.OUTPUT_NAMES
    assert outputs["KUBECONFIG"] == str(kubeconfig)
    assert outputs["KUBECONTEXT"] == "k3d-monitoring"
    assert outputs["monitoringReused"] == "true"
    assert outputs["EnvironmentRepositoryReused"] == "true"
    assert outputs["otelEndpoint"] == "http://localhost:4318"


@pytest.mark.parametrize(
    ("mutation", "failed_check"),
    [
        (lambda payload: payload.update(ready=False), "monitoring_ready"),
        (
            lambda payload: payload["storage"].update(layout_compatible=False),
            "monitoring_storage_layout_compatible",
        ),
        (
            lambda payload: payload["storage"].update(migration_required=True),
            "monitoring_storage_migration_not_required",
        ),
        (
            lambda payload: payload["endpoints"]["loki"].update(ready=False),
            "monitoring_loki_endpoint_ready",
        ),
        (
            lambda payload: payload["cluster"].update(name="not-monitoring"),
            "monitoring_cluster_name",
        ),
        (
            lambda payload: payload["release"].update(namespace="other"),
            "monitoring_release_namespace",
        ),
        (
            lambda payload: payload["release"].update(revision=True),
            "monitoring_release_revision",
        ),
    ],
)
def test_nonready_or_nondurable_report_fails_named_check(mutation, failed_check) -> None:
    payload = _payload()
    mutation(payload)
    checks = monitoring.report_checks(
        payload,
        expected_operation="status",
        expected_context="k3d-monitoring",
        require_chart_changed=False,
    )
    assert checks[failed_check] is False


def test_invocation_records_valid_json_without_ambient_path(monkeypatch, tmp_path) -> None:
    payload = _payload()
    launcher = _write_launcher(tmp_path / "bin/monitoring", json.dumps(payload))
    monkeypatch.setenv("PATH", "")
    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", str(launcher))
    ctx = SimpleNamespace(report_dir=tmp_path / "report", node_id="monitoring.test")

    invocation = monitoring.invoke_monitoring(
        ctx,
        operation="status",
        arguments=["--json", "--require-ready"],
        timeout_seconds=2,
        kubeconfig=tmp_path / "kubeconfig",
        kubecontext="k3d-monitoring",
    )

    assert invocation.error is None
    assert invocation.record.exit_code == 0
    assert invocation.payload == payload
    assert invocation.artifact is not None and invocation.artifact.is_file()
    assert Path(ctx.report_dir, invocation.record.log_path).is_file()


def test_invocation_rejects_malformed_json_with_process_evidence(monkeypatch, tmp_path) -> None:
    launcher = _write_launcher(tmp_path / "bin/monitoring", "not-json")
    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", str(launcher))
    ctx = SimpleNamespace(report_dir=tmp_path / "report", node_id="monitoring.test")

    invocation = monitoring.invoke_monitoring(
        ctx,
        operation="status",
        arguments=["--json", "--require-ready"],
        timeout_seconds=2,
        kubeconfig=tmp_path / "kubeconfig",
        kubecontext="k3d-monitoring",
    )

    assert invocation.record.exit_code == 0
    assert invocation.payload is None
    assert "invalid JSON" in (invocation.error or "")
    assert invocation.record.log_path is not None


def test_invocation_records_missing_installed_launcher(monkeypatch, tmp_path) -> None:
    missing = tmp_path / "missing/monitoring"
    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", str(missing))
    ctx = SimpleNamespace(report_dir=tmp_path / "report", node_id="monitoring.test")

    invocation = monitoring.invoke_monitoring(
        ctx,
        operation="status",
        arguments=["--json", "--require-ready"],
        timeout_seconds=2,
        kubeconfig=tmp_path / "kubeconfig",
        kubecontext="k3d-monitoring",
    )

    assert invocation.payload is None
    assert invocation.record.exit_code == -1
    assert "failed to resolve installed monitoring CLI" in (invocation.error or "")
    assert Path(ctx.report_dir, invocation.record.log_path).is_file()


def test_invocation_kills_the_monitoring_process_group_on_timeout(
    monkeypatch, tmp_path
) -> None:
    launcher = tmp_path / "bin/monitoring"
    launcher.parent.mkdir(parents=True)
    launcher.write_text("#!/bin/sh\nsleep 30\n", encoding="utf-8")
    launcher.chmod(0o755)
    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", str(launcher))
    ctx = SimpleNamespace(report_dir=tmp_path / "report", node_id="monitoring.test")

    invocation = monitoring.invoke_monitoring(
        ctx,
        operation="up",
        arguments=["--json"],
        timeout_seconds=0.05,
        kubeconfig=tmp_path / "kubeconfig",
        kubecontext="k3d-monitoring",
    )

    assert "exceeded 0.05 seconds and was killed" in (invocation.error or "")
    assert invocation.record.exit_code is not None
    assert invocation.record.log_path is not None


def test_invocation_fails_when_process_log_cannot_be_persisted(
    monkeypatch, tmp_path
) -> None:
    payload = _payload()
    launcher = _write_launcher(tmp_path / "bin/monitoring", json.dumps(payload))
    monkeypatch.setenv("TEST_GRAPH_MONITORING_CLI", str(launcher))
    monkeypatch.setattr(
        monitoring,
        "_write_process_log",
        lambda *_args: "could not write monitoring process log",
    )
    ctx = SimpleNamespace(report_dir=tmp_path / "report", node_id="monitoring.test")

    invocation = monitoring.invoke_monitoring(
        ctx,
        operation="status",
        arguments=["--json", "--require-ready"],
        timeout_seconds=2,
        kubeconfig=tmp_path / "kubeconfig",
        kubecontext="k3d-monitoring",
    )

    assert invocation.payload is None
    assert invocation.record.exit_code == 0
    assert invocation.record.error == "could not write monitoring process log"
