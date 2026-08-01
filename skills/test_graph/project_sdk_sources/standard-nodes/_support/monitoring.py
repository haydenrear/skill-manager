"""Public-CLI support shared by the standard monitoring prerequisite pair.

This module deliberately knows only the installed ``monitoring`` command and
its JSON contract.  It does not import deploy-cdc, inspect its worktree, or
provide a second cluster/Helm/OpenTofu implementation.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
from typing import Any, Mapping

from testgraphsdk import NodeResult, ProcessRecord
from testgraphsdk import procs


STATUS_SCHEMA = "deploy-cdc.monitoring-status.v1"
ENSURE_NODE_ID = "monitoring.cluster.ensure"
ASSERT_READY_NODE_ID = "monitoring.cluster.assert.ready"
LEGACY_ENVIRONMENT_ID = "monitoringEnvironmentLifecycle__shared__local-preview__local"

OUTPUT_NAMES = (
    "EnvironmentId",
    "KUBECONFIG",
    "KUBECONTEXT",
    "EnvironmentRepositoryReused",
    "otelEndpoint",
    "otelGrpcEndpoint",
    "inClusterOtlpEndpoint",
    "prometheusUrl",
    "grafanaUrl",
    "lokiUrl",
    "jaegerUrl",
    "alertmanagerUrl",
    "monitoringNamespace",
    "monitoringRelease",
    "monitoringKubeContext",
    "monitoringReused",
)

PUBLIC_ENDPOINTS = {
    "otelEndpoint": "http://localhost:4318",
    "otelGrpcEndpoint": "http://localhost:4317",
    "inClusterOtlpEndpoint": "http://host.k3d.internal:4318",
    "prometheusUrl": "http://localhost:9090",
    "grafanaUrl": "http://localhost:3000",
    "lokiUrl": "http://localhost:3100",
    "jaegerUrl": "http://localhost:16686",
    "alertmanagerUrl": "http://localhost:9093",
}

HEALTH_ENDPOINTS = {
    "prometheus": f"{PUBLIC_ENDPOINTS['prometheusUrl']}/-/ready",
    "grafana": f"{PUBLIC_ENDPOINTS['grafanaUrl']}/api/health",
    "loki": f"{PUBLIC_ENDPOINTS['lokiUrl']}/ready",
    "jaeger": f"{PUBLIC_ENDPOINTS['jaegerUrl']}/",
    "otlp_http": PUBLIC_ENDPOINTS["otelEndpoint"],
}


@dataclass(frozen=True)
class MonitoringInvocation:
    record: ProcessRecord
    payload: dict[str, Any] | None
    stdout: str
    stderr: str
    error: str | None
    artifact: Path | None


def resolve_monitoring_cli(source_file: Path | None = None) -> Path:
    """Resolve the installed launcher without consulting ambient ``PATH``."""

    override = os.environ.get("TEST_GRAPH_MONITORING_CLI")
    if override:
        candidate = Path(override).expanduser()
        if not candidate.is_absolute():
            raise RuntimeError("TEST_GRAPH_MONITORING_CLI must be an absolute path")
        return _require_launcher(candidate, "TEST_GRAPH_MONITORING_CLI")

    candidates: list[tuple[Path, str]] = []
    configured_home = os.environ.get("SKILL_MANAGER_HOME")
    if configured_home:
        home = Path(configured_home).expanduser()
        if not home.is_absolute():
            raise RuntimeError("SKILL_MANAGER_HOME must be an absolute path")
        return _require_launcher(home / "bin/cli/monitoring", "SKILL_MANAGER_HOME")

    inferred_home = _infer_skill_manager_home(
        (source_file or Path(__file__)).expanduser().resolve(strict=False)
    )
    if inferred_home is not None:
        candidates.append((inferred_home / "bin/cli/monitoring", "installed node path"))

    candidates.append(
        (Path.home() / ".skill-manager/bin/cli/monitoring", "default Skill Manager home")
    )

    seen: set[Path] = set()
    for candidate, origin in candidates:
        resolved = candidate.resolve(strict=False)
        if resolved in seen:
            continue
        seen.add(resolved)
        if resolved.is_file() and os.access(resolved, os.X_OK):
            return resolved

    rendered = ", ".join(str(path) for path, _origin in candidates)
    raise RuntimeError(
        "installed monitoring CLI was not found at any deterministic candidate "
        f"({rendered}); install github:haydenrear/deploy-cdc with skill-manager "
        "or set TEST_GRAPH_MONITORING_CLI to its absolute launcher"
    )


def _require_launcher(candidate: Path, origin: str) -> Path:
    resolved = candidate.resolve(strict=False)
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise RuntimeError(
            f"{origin} resolved monitoring to {resolved}, which is not an executable file"
        )
    return resolved


def _infer_skill_manager_home(source_file: Path) -> Path | None:
    parts = source_file.parts
    for index in range(len(parts) - 1):
        if parts[index : index + 2] == ("skills", "test-graph"):
            prefix = parts[:index]
            return Path(*prefix) if prefix else Path("/")
    return None


def monitoring_kubeconfig() -> Path:
    override = os.environ.get("TEST_GRAPH_MONITORING_KUBECONFIG")
    if override:
        path = Path(override).expanduser()
        if not path.is_absolute():
            raise RuntimeError("TEST_GRAPH_MONITORING_KUBECONFIG must be absolute")
        return path.resolve(strict=False)

    configured = os.environ.get("MONITORING_KUBECONFIG_PATH")
    if configured:
        path = Path(configured).expanduser()
        if not path.is_absolute():
            raise RuntimeError("MONITORING_KUBECONFIG_PATH must be absolute")
        return path.resolve(strict=False)

    if _truthy(os.environ.get("GITHUB_ACTIONS")):
        runner_temp = Path(os.environ.get("RUNNER_TEMP") or "/tmp")
        return (runner_temp / "deploy-cdc-monitoring/kubeconfig").resolve(strict=False)

    config_home = Path(
        os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config"
    ).expanduser()
    return (config_home / "deploy-cdc/monitoring/kubeconfig").resolve(strict=False)


def monitoring_kubecontext() -> str:
    return os.environ.get("MONITORING_KUBE_CONTEXT") or f"k3d-{monitoring_cluster_name()}"


def monitoring_cluster_name() -> str:
    return os.environ.get("MONITORING_CLUSTER_NAME") or "monitoring"


def monitoring_namespace() -> str:
    return os.environ.get("MONITORING_NAMESPACE") or "monitoring"


def monitoring_release_name() -> str:
    return os.environ.get("MONITORING_RELEASE") or "monitoring"


def monitoring_environment(kubeconfig: Path, kubecontext: str) -> dict[str, str]:
    env = dict(os.environ)
    env["MONITORING_KUBECONFIG_PATH"] = str(kubeconfig)
    env["MONITORING_KUBE_CONTEXT"] = kubecontext
    return env


def invoke_monitoring(
    ctx: Any,
    *,
    operation: str,
    arguments: list[str],
    timeout_seconds: float,
    kubeconfig: Path,
    kubecontext: str,
) -> MonitoringInvocation:
    """Run one installed CLI command with a child deadline and full evidence."""

    argv = ["<installed-monitoring-cli>", operation, *arguments]
    label = operation.replace("-", "_")
    started = datetime.now(timezone.utc)
    stdout = ""
    stderr = ""
    error: str | None = None
    pid: int | None = None
    exit_code = -1

    try:
        log_path = procs.log_file(ctx, label)
    except OSError as exc:
        record = ProcessRecord(
            label=label,
            command=argv,
            started_at=started,
            ended_at=datetime.now(timezone.utc),
            error=f"could not allocate monitoring log file: {exc}",
        )
        return MonitoringInvocation(record, None, "", "", record.error, None)

    try:
        launcher = resolve_monitoring_cli()
        argv[0] = str(launcher)
    except (OSError, RuntimeError) as exc:
        error = f"failed to resolve installed monitoring CLI: {exc}"
        log_error = _write_process_log(log_path, argv, stdout, stderr, error)
        if log_error:
            error = f"{error}; {log_error}"
        record = ProcessRecord(
            label=label,
            command=argv,
            started_at=started,
            ended_at=datetime.now(timezone.utc),
            exit_code=exit_code,
            log_path=_relative_to_report(ctx.report_dir, log_path),
            error=error,
        )
        return MonitoringInvocation(record, None, stdout, stderr, error, None)

    try:
        process = subprocess.Popen(
            argv,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=monitoring_environment(kubeconfig, kubecontext),
            start_new_session=True,
        )
        pid = process.pid
        try:
            stdout, stderr = process.communicate(timeout=timeout_seconds)
            exit_code = process.returncode
        except subprocess.TimeoutExpired:
            stdout, stderr = _terminate_process_group(process)
            exit_code = process.returncode
            error = f"monitoring {operation} exceeded {timeout_seconds:g} seconds and was killed"
    except OSError as exc:
        error = f"failed to launch installed monitoring CLI: {exc}"

    ended = datetime.now(timezone.utc)
    log_error = _write_process_log(log_path, argv, stdout, stderr, error)
    if log_error:
        error = f"{error}; {log_error}" if error else log_error
    record = ProcessRecord(
        label=label,
        command=argv,
        started_at=started,
        ended_at=ended,
        exit_code=exit_code,
        pid=pid,
        log_path=_relative_to_report(ctx.report_dir, log_path),
        error=error,
    )

    payload: dict[str, Any] | None = None
    if error is None:
        try:
            decoded = json.loads(stdout)
            if not isinstance(decoded, dict):
                raise ValueError("top-level JSON value is not an object")
            payload = decoded
        except (json.JSONDecodeError, ValueError) as exc:
            error = f"monitoring {operation} emitted invalid JSON: {exc}"
            record.error = error

    artifact: Path | None = None
    if payload is not None:
        artifact = ctx.report_dir / "monitoring" / f"{ctx.node_id}.{operation}.json"
        try:
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_text(
                json.dumps(payload, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
        except OSError as exc:
            error = f"could not record monitoring {operation} JSON: {exc}"
            record.error = error
            artifact = None

    return MonitoringInvocation(record, payload, stdout, stderr, error, artifact)


def _write_process_log(
    path: Path,
    argv: list[str],
    stdout: str,
    stderr: str,
    error: str | None,
) -> str | None:
    sections = [f"command: {shlex.join(argv)}", "", "stdout:", stdout or "<empty>"]
    sections.extend(["", "stderr:", stderr or "<empty>"])
    if error:
        sections.extend(["", "error:", error])
    try:
        path.write_text("\n".join(sections) + "\n", encoding="utf-8")
    except OSError as exc:
        return f"could not write monitoring process log {path}: {exc}"
    return None


def _terminate_process_group(
    process: subprocess.Popen[str],
) -> tuple[str, str]:
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        return process.communicate(timeout=2)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return process.communicate()


def _relative_to_report(report_dir: Path, path: Path) -> str:
    try:
        return str(path.resolve().relative_to(report_dir.resolve()))
    except ValueError:
        return str(path)


def report_checks(
    payload: Mapping[str, Any] | None,
    *,
    expected_operation: str,
    expected_context: str,
    require_chart_changed: bool,
) -> dict[str, bool]:
    """Return named assertions for the installed public status contract."""

    if payload is None:
        return {"monitoring_status_json_present": False}

    cluster = _mapping(payload.get("cluster"))
    release = _mapping(payload.get("release"))
    storage = _mapping(payload.get("storage"))
    workloads = _mapping(payload.get("workloads"))
    endpoints = _mapping(payload.get("endpoints"))
    checks = {
        "monitoring_status_schema": payload.get("schema_version") == STATUS_SCHEMA,
        "monitoring_operation": payload.get("operation") == expected_operation,
        "monitoring_cli_success": payload.get("success") is True,
        "monitoring_ready": payload.get("ready") is True,
        "monitoring_errors_empty": payload.get("errors") == [],
        "monitoring_cluster_exists": cluster.get("exists") is True,
        "monitoring_cluster_running": cluster.get("running") is True,
        "monitoring_cluster_name": cluster.get("name") == monitoring_cluster_name(),
        "monitoring_cluster_context": cluster.get("context") == expected_context,
        "monitoring_release_deployed": release.get("deployed") is True,
        "monitoring_release_name": release.get("name") == monitoring_release_name(),
        "monitoring_release_namespace": release.get("namespace")
        == monitoring_namespace(),
        "monitoring_release_status": release.get("status") == "deployed",
        "monitoring_release_revision": type(release.get("revision")) is int
        and int(release.get("revision")) > 0,
        "monitoring_storage_mounted": storage.get("mounted") is True,
        "monitoring_storage_layout_compatible": storage.get("layout_compatible") is True,
        "monitoring_storage_migration_not_required": storage.get("migration_required") is False,
        "monitoring_workloads_ready": workloads.get("ready") is True,
    }
    if require_chart_changed:
        checks["monitoring_chart_changed_reported"] = isinstance(
            payload.get("chart_changed"), bool
        )
    for name, expected_url in HEALTH_ENDPOINTS.items():
        endpoint = _mapping(endpoints.get(name))
        checks[f"monitoring_{name}_endpoint_ready"] = endpoint.get("ready") is True
        checks[f"monitoring_{name}_endpoint_url"] = endpoint.get("url") == expected_url
    return checks


def add_checks(result: NodeResult, checks: Mapping[str, bool]) -> NodeResult:
    for name, passed in checks.items():
        result.assertion(name, passed)
    return result


def public_outputs(
    payload: Mapping[str, Any],
    *,
    kubeconfig: Path,
    kubecontext: str,
    reused: bool,
) -> dict[str, str]:
    cluster = _mapping(payload.get("cluster"))
    release = _mapping(payload.get("release"))
    observed_context = str(cluster.get("context") or kubecontext)
    values = {
        "EnvironmentId": LEGACY_ENVIRONMENT_ID,
        "KUBECONFIG": str(kubeconfig),
        "KUBECONTEXT": observed_context,
        "EnvironmentRepositoryReused": _bool_text(reused),
        **PUBLIC_ENDPOINTS,
        "monitoringNamespace": str(release.get("namespace") or monitoring_namespace()),
        "monitoringRelease": str(release.get("name") or monitoring_release_name()),
        "monitoringKubeContext": observed_context,
        "monitoringReused": _bool_text(reused),
    }
    missing = [name for name in OUTPUT_NAMES if not values.get(name)]
    if missing:
        raise RuntimeError(f"monitoring output projection omitted: {', '.join(missing)}")
    return values


def upstream_outputs(ctx: Any) -> dict[str, str]:
    return {
        name: str(ctx.get(ENSURE_NODE_ID, name) or "")
        for name in OUTPUT_NAMES
    }


def publish_outputs(result: NodeResult, values: Mapping[str, str]) -> NodeResult:
    for name in OUTPUT_NAMES:
        result.publish(name, values[name])
    return result


def invocation_failure(
    invocation: MonitoringInvocation, *check_sets: Mapping[str, bool]
) -> str:
    if invocation.error:
        return invocation.error
    payload = invocation.payload or {}
    errors = payload.get("errors")
    if isinstance(errors, list) and errors:
        return "; ".join(str(item) for item in errors)
    failed = [
        name
        for checks in check_sets
        for name, passed in checks.items()
        if not passed
    ]
    if failed:
        return "strict monitoring checks failed: " + ", ".join(failed)
    return f"installed monitoring command exited {invocation.record.exit_code} without strict readiness"


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _bool_text(value: bool) -> str:
    return "true" if value else "false"


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}
