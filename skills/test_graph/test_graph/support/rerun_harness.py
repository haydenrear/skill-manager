from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import signal
import stat
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Iterator, Mapping


REPO_ROOT = Path(__file__).resolve().parents[2]
PROJECT_GRAPH_ROOT = REPO_ROOT / "project_sdk_sources"
sys.path.insert(0, str(REPO_ROOT))
from scripts import _common as scripts_common  # noqa: E402


RUN_ID_RE = re.compile(
    r"testGraph '([^']+)' run=([0-9]{8}-[0-9]{6}(?:-[1-9][0-9]{0,3})?)(?=\s)"
)
TRACE_ID_RE = re.compile(r"^[0-9a-f]{32}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
TRACEPARENT_RE = re.compile(r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
NODE_ID_RE = re.compile(r"^[a-z0-9._-]{1,128}$")

MAX_COMMAND_OUTPUT_BYTES = 8 * 1024 * 1024
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_TRACE_CARRIER_BYTES = 4 * 1024
MAX_ENVELOPE_FILES = 10_000
MAX_AGGREGATE_ENVELOPE_BYTES = 16 * 1024 * 1024
MAX_AGGREGATE_CONTEXT_BYTES = 16 * 1024 * 1024
MAX_JSON_DEPTH = 64
MAX_JSON_VALUES = 500_000
PROCESS_POLL_SECONDS = 0.02
PROCESS_TERM_GRACE_SECONDS = 1.0
PROCESS_PIPE_DRAIN_SECONDS = 1.0
PROCESS_DIAGNOSTIC_BYTES = 4 * 1024
# Each replay node executes at most four nested commands (baseline, replay,
# negative replay, and manual report regeneration). Their combined hard limit
# must fit inside the node's 10-minute outer executor budget with a full minute
# reserved for Python/Kotlin cleanup and envelope/report publication.
INNER_COMMAND_TIMEOUT_SECONDS = 120
MAX_NESTED_COMMANDS_PER_NODE = 4
OUTER_NODE_TIMEOUT_SECONDS = 10 * 60
OUTER_CLEANUP_RESERVE_SECONDS = 60
if (
    INNER_COMMAND_TIMEOUT_SECONDS * MAX_NESTED_COMMANDS_PER_NODE
    > OUTER_NODE_TIMEOUT_SECONDS - OUTER_CLEANUP_RESERVE_SECONDS
):
    raise RuntimeError("nested replay command budgets exceed the outer node budget")
JVM_MEMORY_OVERRIDE_RE = re.compile(
    r"(?:^|[\s=,'\"])-(?:Xmx|Xms|Xmn|Xss)[^\s,'\"]*"
    r"|(?:^|[\s=,'\"])-XX:(?:MaxMetaspaceSize|MetaspaceSize|"
    r"MaxDirectMemorySize|ThreadStackSize|ReservedCodeCacheSize|"
    r"InitialCodeCacheSize|CompressedClassSpaceSize|MaxRAM|"
    r"MaxRAMPercentage|InitialRAMPercentage|MinRAMPercentage|"
    r"MaxRAMFraction|InitialRAMFraction|MinRAMFraction)=[^\s,'\"]+"
)
JVM_OPTION_FILE_PREFIXES = ("@", "-XX:Flags=", "-XX:VMOptionsFile=")
JVM_OPTION_CHANNELS = (
    "JAVA_OPTS",
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "KOTLIN_OPTS",
    "KOTLIN_DAEMON_JVM_OPTIONS",
)

SMOKE_NODE_IDS = (
    "app.running",
    "user.seeded",
    "network.pingable",
    "login.smoke",
    "rerun.disabled.probe",
    "context.snapshots.present",
)


class ReplayEvidenceError(RuntimeError):
    """Fresh replay evidence was missing, malformed, oversized, or inconsistent."""


@dataclass(frozen=True)
class RunEvidenceSnapshot:
    digests: tuple[tuple[str, str], ...]

    def digest(self, relative_path: str) -> str:
        try:
            return dict(self.digests)[relative_path]
        except KeyError as exc:
            raise ReplayEvidenceError(
                f"evidence snapshot is missing {relative_path}"
            ) from exc

    def manifest_sha256(self) -> str:
        digest = hashlib.sha256()
        for relative_path, file_digest in self.digests:
            path_bytes = relative_path.encode("utf-8", errors="strict")
            digest.update(len(path_bytes).to_bytes(4, byteorder="big"))
            digest.update(path_bytes)
            digest.update(bytes.fromhex(file_digest))
        return digest.hexdigest()

    def under(self, directory: str) -> tuple[tuple[str, str], ...]:
        prefix = f"{directory.rstrip('/')}/"
        return tuple(item for item in self.digests if item[0].startswith(prefix))


@dataclass(frozen=True)
class RunIdentity:
    run_id: str
    status: str
    graph_name: str
    mode: str
    selected_node_id: str | None
    source_build: str | None
    expected_node_ids: tuple[str, ...]
    observed_node_ids: frozenset[str]
    observed_context_node_ids: tuple[str, ...]
    trace_id: str
    complete: bool


@dataclass(frozen=True)
class SmokeBaseline:
    completed: subprocess.CompletedProcess[str]
    run_id: str
    build_dir: Path
    snapshot: RunEvidenceSnapshot
    identity: RunIdentity


@dataclass(frozen=True)
class ReplayEvidence:
    source_build_dir: Path
    target_run_id: str
    target_build_dir: Path
    trace_id: str
    source_evidence_sha256: str
    target_evidence_sha256: str
    checks: tuple[tuple[str, bool], ...]
    manual_report: subprocess.CompletedProcess[str]

    def audit_log(self) -> str:
        return (
            f"fresh replay source={self.source_build_dir} target={self.target_build_dir} "
            f"traceId={self.trace_id} sourceEvidenceSha256={self.source_evidence_sha256} "
            f"targetEvidenceSha256={self.target_evidence_sha256}"
        )


class _ReusableHTTPServer(HTTPServer):
    allow_reuse_address = True


class _FixtureHandler(BaseHTTPRequestHandler):
    def do_HEAD(self) -> None:
        if self.path == "/login":
            self.send_response(302)
            self.send_header("Location", "/dashboard")
        else:
            self.send_response(200)
        self.end_headers()

    def do_GET(self) -> None:
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt: str, *args: object) -> None:
        return


@contextmanager
def local_http_fixture() -> Iterator[None]:
    server = _ReusableHTTPServer(("127.0.0.1", 8080), _FixtureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield
    finally:
        server.shutdown()
        server.server_close()


def run_command(
    label: str,
    args: list[str],
    *,
    env: Mapping[str, str] | None = None,
    cwd: Path = REPO_ROOT,
    timeout: float = INNER_COMMAND_TIMEOUT_SECONDS,
) -> subprocess.CompletedProcess[str]:
    if timeout <= 0:
        raise ReplayEvidenceError("command timeout must be positive")
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    process = subprocess.Popen(
        args,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=merged_env,
        start_new_session=True,
    )
    if process.stdout is None or process.stderr is None:
        _terminate_process_group(process)
        raise ReplayEvidenceError(f"{label} did not expose output pipes")

    buffers = {"stdout": bytearray(), "stderr": bytearray()}
    total_bytes = 0
    lock = threading.Lock()
    output_exceeded = threading.Event()
    reader_errors: list[str] = []

    def drain(name: str, stream: Any) -> None:
        nonlocal total_bytes
        try:
            while not output_exceeded.is_set():
                chunk = os.read(stream.fileno(), 64 * 1024)
                if not chunk:
                    return
                with lock:
                    remaining = MAX_COMMAND_OUTPUT_BYTES - total_bytes
                    accepted = chunk[: max(remaining, 0)]
                    buffers[name].extend(accepted)
                    total_bytes += len(accepted)
                    if len(accepted) != len(chunk):
                        output_exceeded.set()
                        return
        except OSError as exc:
            with lock:
                reader_errors.append(f"{name}: {exc}")

    readers = [
        threading.Thread(target=drain, args=("stdout", process.stdout), daemon=True),
        threading.Thread(target=drain, args=("stderr", process.stderr), daemon=True),
    ]
    for reader in readers:
        reader.start()

    failure: str | None = None
    deadline = time.monotonic() + timeout
    try:
        while process.poll() is None:
            if output_exceeded.is_set():
                failure = (
                    f"{label} output exceeds the combined live limit of "
                    f"{MAX_COMMAND_OUTPUT_BYTES} bytes"
                )
                break
            if time.monotonic() >= deadline:
                failure = f"{label} exceeded its {timeout:g}s timeout"
                break
            time.sleep(PROCESS_POLL_SECONDS)
        if failure is not None:
            _terminate_process_group(process)

        drain_deadline = time.monotonic() + PROCESS_PIPE_DRAIN_SECONDS
        for reader in readers:
            reader.join(max(0.0, drain_deadline - time.monotonic()))
        if output_exceeded.is_set():
            failure = failure or (
                f"{label} output exceeds the combined live limit of "
                f"{MAX_COMMAND_OUTPUT_BYTES} bytes"
            )
            _terminate_process_group(process)
        if any(reader.is_alive() for reader in readers):
            failure = failure or f"{label} left descendant-held output pipes open"
            _terminate_process_group(process)
            for reader in readers:
                reader.join(PROCESS_TERM_GRACE_SECONDS)
        if any(reader.is_alive() for reader in readers):
            failure = failure or f"{label} output readers did not terminate"
        if reader_errors:
            failure = failure or f"{label} output reader failed: {reader_errors[0]}"
        if _process_group_exists(process.pid):
            failure = (
                failure or f"{label} left live descendants after its leader exited"
            )
            _terminate_process_group(process)
    except BaseException:
        _terminate_process_group(process)
        raise
    finally:
        process.stdout.close()
        process.stderr.close()

    stdout = _decode_command_output(bytes(buffers["stdout"]), f"{label} stdout")
    stderr = _decode_command_output(bytes(buffers["stderr"]), f"{label} stderr")
    if failure is not None:
        diagnostic = _command_diagnostic(stdout, stderr)
        suffix = f"; bounded output tail:\n{diagnostic}" if diagnostic else ""
        raise ReplayEvidenceError(f"{failure}{suffix}")
    return subprocess.CompletedProcess(
        args=[label, *args],
        returncode=process.returncode if process.returncode is not None else -1,
        stdout=stdout,
        stderr=stderr,
    )


def _decode_command_output(raw: bytes, label: str) -> str:
    try:
        return raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ReplayEvidenceError(f"{label} is not strict UTF-8") from exc


def _command_diagnostic(stdout: str, stderr: str) -> str:
    combined = f"stdout:\n{stdout}\nstderr:\n{stderr}".encode("utf-8", errors="replace")
    return (
        combined[-PROCESS_DIAGNOSTIC_BYTES:].decode("utf-8", errors="replace").strip()
    )


def _terminate_process_group(process: subprocess.Popen[Any]) -> None:
    process_group = process.pid
    _signal_process_group(process_group, signal.SIGTERM)
    _wait_for_process_group(process, process_group, PROCESS_TERM_GRACE_SECONDS)
    if _process_group_exists(process_group):
        _signal_process_group(process_group, signal.SIGKILL)
        _wait_for_process_group(process, process_group, PROCESS_TERM_GRACE_SECONDS)
    if _process_group_exists(process_group):
        raise ReplayEvidenceError(
            f"process group {process_group} still exists after SIGKILL"
        )
    try:
        process.wait(timeout=0)
    except subprocess.TimeoutExpired as exc:
        raise ReplayEvidenceError(
            f"could not reap process group {process_group} after SIGKILL"
        ) from exc


def _wait_for_process_group(
    process: subprocess.Popen[Any], process_group: int, timeout: float
) -> None:
    deadline = time.monotonic() + timeout
    while True:
        process.poll()
        if not _process_group_exists(process_group) or time.monotonic() >= deadline:
            return
        time.sleep(PROCESS_POLL_SECONDS)


def _signal_process_group(process_group: int, sig: signal.Signals) -> None:
    try:
        os.killpg(process_group, sig)
    except ProcessLookupError:
        return


def _process_group_exists(process_group: int) -> bool:
    try:
        os.killpg(process_group, 0)
        return True
    except ProcessLookupError:
        return False


def run_project_graph(
    graph: str,
    extra_args: list[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    args = [
        str(REPO_ROOT / "scripts/run.py"),
        graph,
        "--test-graph-root",
        str(PROJECT_GRAPH_ROOT),
    ]
    if extra_args:
        args.extend(extra_args)
    return run_command(graph, args, env=_bounded_gradle_env(env))


def run_smoke_baseline() -> SmokeBaseline:
    completed = run_project_graph("smoke")
    if completed.returncode != 0:
        raise ReplayEvidenceError(summarize("baseline_smoke", completed))
    run_id = extract_single_run_id(output_of(completed), "smoke")
    build_dir = PROJECT_GRAPH_ROOT / "build/validation-reports" / run_id
    snapshot = snapshot_run_evidence(build_dir, SMOKE_NODE_IDS)
    identity = inspect_run_identity(
        build_dir,
        graph_name="smoke",
        mode="full",
        selected_node_id=None,
        source_build=None,
        expected_node_ids=SMOKE_NODE_IDS,
    )
    return SmokeBaseline(completed, run_id, build_dir, snapshot, identity)


def extract_run_ids(output: str) -> Mapping[str, str]:
    return {graph: run_id for graph, run_id in RUN_ID_RE.findall(output)}


def extract_single_run_id(output: str, graph: str) -> str:
    matches = [
        run_id
        for found_graph, run_id in RUN_ID_RE.findall(output)
        if found_graph == graph
    ]
    if len(matches) != 1:
        raise ReplayEvidenceError(
            f"expected exactly one run id for graph {graph!r}, found {matches!r}"
        )
    return matches[0]


def snapshot_run_evidence(
    run_dir: Path,
    expected_context_node_ids: tuple[str, ...],
) -> RunEvidenceSnapshot:
    canonical_run_dir = _canonical_directory(run_dir, "run evidence directory")
    if (
        len(expected_context_node_ids) not in range(1, MAX_ENVELOPE_FILES + 1)
        or len(set(expected_context_node_ids)) != len(expected_context_node_ids)
        or any(
            NODE_ID_RE.fullmatch(node_id) is None
            for node_id in expected_context_node_ids
        )
    ):
        raise ReplayEvidenceError("expected context snapshot node ids are invalid")
    digests: dict[str, str] = {}
    required_files = {
        "execution-scope.json": MAX_JSON_BYTES,
        "attempt-closure.json": MAX_JSON_BYTES,
        "trace-context.json": MAX_TRACE_CARRIER_BYTES,
        "summary.json": MAX_JSON_BYTES,
        "report.md": MAX_REPORT_BYTES,
    }
    for relative_path, max_bytes in required_files.items():
        digests[relative_path] = _hash_bounded_file(
            canonical_run_dir / relative_path,
            max_bytes,
            relative_path,
        )

    envelope_dir = _canonical_directory(
        canonical_run_dir / "envelope",
        "envelope directory",
    )
    aggregate_bytes = 0
    envelope_count = 0
    with os.scandir(envelope_dir) as entries:
        for entry in entries:
            envelope_count += 1
            if envelope_count > MAX_ENVELOPE_FILES:
                raise ReplayEvidenceError(
                    f"envelope directory exceeds {MAX_ENVELOPE_FILES} files"
                )
            if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                raise ReplayEvidenceError(
                    f"envelope entry is not a regular file: {entry.name}"
                )
            if not entry.name.endswith(".json") or not NODE_ID_RE.fullmatch(
                entry.name[:-5]
            ):
                raise ReplayEvidenceError(
                    f"invalid canonical envelope filename: {entry.name}"
                )
            size = entry.stat(follow_symlinks=False).st_size
            if size > MAX_JSON_BYTES:
                raise ReplayEvidenceError(
                    f"envelope/{entry.name} exceeds {MAX_JSON_BYTES} bytes"
                )
            aggregate_bytes += size
            if aggregate_bytes > MAX_AGGREGATE_ENVELOPE_BYTES:
                raise ReplayEvidenceError(
                    f"aggregate envelope bytes exceed {MAX_AGGREGATE_ENVELOPE_BYTES}"
                )
            relative_path = f"envelope/{entry.name}"
            digests[relative_path] = _hash_bounded_file(
                Path(entry.path),
                MAX_JSON_BYTES,
                relative_path,
            )
    if envelope_count == 0:
        raise ReplayEvidenceError("run evidence has no canonical envelopes")

    context_dir = _canonical_directory(
        canonical_run_dir / "context",
        "context directory",
    )
    expected_context_files = {
        f"{node_id}.input.json" for node_id in expected_context_node_ids
    }
    observed_context_files: set[str] = set()
    aggregate_context_bytes = 0
    with os.scandir(context_dir) as entries:
        for entry in entries:
            if entry.name not in expected_context_files:
                raise ReplayEvidenceError(
                    f"unexpected context snapshot entry: {entry.name}"
                )
            if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                raise ReplayEvidenceError(
                    f"context snapshot is not a regular file: {entry.name}"
                )
            if entry.name in observed_context_files:
                raise ReplayEvidenceError(
                    f"duplicate context snapshot entry: {entry.name}"
                )
            observed_context_files.add(entry.name)
            size = entry.stat(follow_symlinks=False).st_size
            if size > MAX_JSON_BYTES:
                raise ReplayEvidenceError(
                    f"context/{entry.name} exceeds {MAX_JSON_BYTES} bytes"
                )
            aggregate_context_bytes += size
            if aggregate_context_bytes > MAX_AGGREGATE_CONTEXT_BYTES:
                raise ReplayEvidenceError(
                    "aggregate context snapshot bytes exceed "
                    f"{MAX_AGGREGATE_CONTEXT_BYTES}"
                )
            relative_path = f"context/{entry.name}"
            digests[relative_path] = _hash_bounded_file(
                Path(entry.path),
                MAX_JSON_BYTES,
                relative_path,
            )
    if observed_context_files != expected_context_files:
        missing = sorted(expected_context_files - observed_context_files)
        raise ReplayEvidenceError(f"missing context snapshots: {missing}")
    return RunEvidenceSnapshot(tuple(sorted(digests.items())))


def inspect_run_identity(
    run_dir: Path,
    *,
    graph_name: str,
    mode: str,
    selected_node_id: str | None,
    source_build: Path | None,
    expected_node_ids: tuple[str, ...],
) -> RunIdentity:
    canonical_run_dir = _canonical_directory(run_dir, "run directory")
    if (
        not graph_name
        or len(graph_name) > 256
        or any(ord(character) < 32 or ord(character) == 127 for character in graph_name)
    ):
        raise ReplayEvidenceError(
            "graph name is blank, oversized, or contains controls"
        )
    if mode not in {"full", "resume-from-node", "run-only-node"}:
        raise ReplayEvidenceError(f"unsupported execution mode: {mode!r}")
    if len(expected_node_ids) not in range(1, MAX_ENVELOPE_FILES + 1):
        raise ReplayEvidenceError("expected replay scope must contain 1..10,000 nodes")
    if len(set(expected_node_ids)) != len(expected_node_ids):
        raise ReplayEvidenceError("expected replay scope contains duplicate node ids")
    if any(NODE_ID_RE.fullmatch(node_id) is None for node_id in expected_node_ids):
        raise ReplayEvidenceError("expected replay scope contains an invalid node id")

    expected_source = (
        str(_canonical_directory(source_build, "source build"))
        if source_build is not None
        else None
    )
    scope = _load_bounded_json_object(
        canonical_run_dir / "execution-scope.json",
        MAX_JSON_BYTES,
        "execution-scope.json",
    )
    expected_scope_keys = {"version", "graphName", "mode", "expectedNodeIds"}
    if mode != "full":
        expected_scope_keys |= {
            "selectedNodeId",
            "sourceBuild",
            "sourceClosureSha256",
            "sourceContextSha256",
        }
    _require(set(scope) == expected_scope_keys, "execution scope has exact fields")
    _require(
        type(scope["version"]) is int and scope["version"] == 3,
        "execution scope version is 3",
    )
    _require(scope["graphName"] == graph_name, "execution scope graph name is exact")
    _require(scope["mode"] == mode, "execution scope mode matches replay")
    _require(
        _strict_string_list(scope["expectedNodeIds"], "scope expectedNodeIds")
        == expected_node_ids,
        "execution scope expected nodes are exact",
    )
    if mode == "full":
        _require(
            selected_node_id is None and expected_source is None,
            "full scope has no replay provenance",
        )
    else:
        _require(
            scope["selectedNodeId"] == selected_node_id,
            "execution scope selected node is exact",
        )
        _require(
            _canonical_path_string(scope["sourceBuild"], "execution scope sourceBuild")
            == expected_source,
            "execution scope source build is exact",
        )
        source_closure_sha256 = scope.get("sourceClosureSha256")
        source_context_sha256 = scope.get("sourceContextSha256")
        _require(
            isinstance(source_closure_sha256, str)
            and SHA256_RE.fullmatch(source_closure_sha256) is not None,
            "execution scope source closure SHA-256 is valid",
        )
        _require(
            isinstance(source_context_sha256, str)
            and SHA256_RE.fullmatch(source_context_sha256) is not None,
            "execution scope source context SHA-256 is valid",
        )
        assert source_build is not None and selected_node_id is not None
        _require(
            source_closure_sha256
            == _hash_bounded_file(
                source_build / "attempt-closure.json",
                MAX_JSON_BYTES,
                "source attempt-closure.json",
            ),
            "execution scope source closure digest is exact",
        )
        _require(
            source_context_sha256
            == _hash_bounded_file(
                source_build / "context" / f"{selected_node_id}.input.json",
                MAX_JSON_BYTES,
                "source selected context",
            ),
            "execution scope source context digest is exact",
        )

    closure = _load_bounded_json_object(
        canonical_run_dir / "attempt-closure.json",
        MAX_JSON_BYTES,
        "attempt-closure.json",
    )
    _require(
        set(closure)
        == {
            "version",
            "runId",
            "traceId",
            "scope",
            "finalizerNodeIds",
            "scopeSha256",
            "carrierSha256",
            "contextSha256",
            "envelopeSha256",
        },
        "attempt closure has exact fields",
    )
    _require(
        type(closure["version"]) is int and closure["version"] == 3,
        "attempt closure version is 3",
    )
    _require(
        closure["runId"] == canonical_run_dir.name,
        "attempt closure run id matches directory",
    )
    closure_trace_id = closure.get("traceId")
    _require(
        isinstance(closure_trace_id, str) and _valid_trace_id(closure_trace_id),
        "attempt closure trace id is valid",
    )
    _require(closure.get("scope") == scope, "attempt closure scope is exact")
    finalizer_node_ids = closure.get("finalizerNodeIds")
    _require(
        isinstance(finalizer_node_ids, list)
        and all(isinstance(node_id, str) for node_id in finalizer_node_ids)
        and len(finalizer_node_ids) == len(set(finalizer_node_ids))
        and all(node_id in expected_node_ids for node_id in finalizer_node_ids)
        and finalizer_node_ids
        == [
            node_id
            for node_id in expected_node_ids
            if node_id in set(finalizer_node_ids)
        ],
        "attempt closure finalizer node ids are unique, scoped, and ordered",
    )
    _require(
        closure.get("scopeSha256")
        == _hash_bounded_file(
            canonical_run_dir / "execution-scope.json",
            MAX_JSON_BYTES,
            "execution-scope.json",
        ),
        "attempt closure binds exact raw execution scope",
    )
    _require(
        closure.get("carrierSha256")
        == _hash_bounded_file(
            canonical_run_dir / "trace-context.json",
            MAX_TRACE_CARRIER_BYTES,
            "trace-context.json",
        ),
        "attempt closure binds exact raw trace carrier",
    )
    context_sha256 = _strict_digest_map(
        closure.get("contextSha256"),
        "attempt closure contextSha256",
    )
    envelope_sha256 = _strict_digest_map(
        closure.get("envelopeSha256"),
        "attempt closure envelopeSha256",
    )
    expected_context_sha256 = {
        f"context/{node_id}.input.json": _hash_bounded_file(
            canonical_run_dir / "context" / f"{node_id}.input.json",
            MAX_JSON_BYTES,
            f"context/{node_id}.input.json",
        )
        for node_id in expected_node_ids
    }
    expected_envelope_sha256 = {
        f"envelope/{node_id}.json": _hash_bounded_file(
            canonical_run_dir / "envelope" / f"{node_id}.json",
            MAX_JSON_BYTES,
            f"envelope/{node_id}.json",
        )
        for node_id in expected_node_ids
    }
    _require(
        context_sha256 == expected_context_sha256,
        "attempt closure context path/digest map is exact",
    )
    _require(
        envelope_sha256 == expected_envelope_sha256,
        "attempt closure envelope path/digest map is exact",
    )

    summary = _load_bounded_json_object(
        canonical_run_dir / "summary.json",
        MAX_JSON_BYTES,
        "summary.json",
    )
    _require(
        summary.get("runId") == canonical_run_dir.name,
        "summary run id matches directory",
    )
    _require(summary.get("status") == "passed", "summary status is PASSED")
    trace_id = summary.get("traceId")
    _require(
        isinstance(trace_id, str) and _valid_trace_id(trace_id),
        "summary trace id is valid",
    )
    _require(
        closure_trace_id == trace_id,
        "attempt closure trace matches summary",
    )
    execution = summary.get("execution")
    _require(isinstance(execution, dict), "summary execution is an object")
    _require(execution.get("graphName") == graph_name, "summary graph name is exact")
    _require(execution.get("mode") == mode, "summary execution mode is exact")
    _require(execution.get("complete") is True, "summary execution is complete")
    summary_expected = _strict_string_list(
        execution.get("expectedNodeIds"),
        "summary expectedNodeIds",
    )
    observed = _strict_string_list(
        execution.get("observedNodeIds"),
        "summary observedNodeIds",
    )
    _require(summary_expected == expected_node_ids, "summary expected nodes are exact")
    _require(len(set(observed)) == len(observed), "summary observed nodes are unique")
    _require(
        set(observed) == set(expected_node_ids), "summary observed node set is exact"
    )
    observed_context = _strict_string_list(
        execution.get("observedContextNodeIds"),
        "summary observedContextNodeIds",
    )
    _require(
        observed_context == expected_node_ids,
        "summary observed context node identities are exact",
    )
    if mode == "full":
        _require("selectedNodeId" not in execution, "full summary omits selected node")
        _require("sourceBuild" not in execution, "full summary omits source build")
    else:
        _require(
            execution.get("selectedNodeId") == selected_node_id,
            "summary selected node is exact",
        )
        _require(
            _canonical_path_string(execution.get("sourceBuild"), "summary sourceBuild")
            == expected_source,
            "summary source build is exact",
        )

    for field in (
        "missingNodeIds",
        "invalidEnvelopeFiles",
        "duplicateNodeIds",
        "unexpectedNodeIds",
        "unknownStatusNodeIds",
        "missingTraceNodeIds",
        "invalidTraceNodeIds",
        "mismatchedTraceNodeIds",
        "missingContextNodeIds",
        "invalidContextFiles",
        "unexpectedContextNodeIds",
        "contextProvenanceViolationNodeIds",
        "replaySourceContextMismatchNodeIds",
    ):
        _require(execution.get(field) == [], f"summary {field} is empty")
    for field in (
        "envelopeFileCountExceeded",
        "aggregateEnvelopeBytesExceeded",
        "aggregateJsonStructureExceeded",
        "contextFileCountExceeded",
        "aggregateContextBytesExceeded",
        "unknownExecutionScope",
    ):
        _require(execution.get(field) is False, f"summary {field} is false")
    _require(
        "attemptClosureIntegrityError" not in execution,
        "summary attempt closure integrity is current",
    )

    nodes = summary.get("nodes")
    _require(isinstance(nodes, list), "summary nodes is an array")
    _require(
        len(nodes) == len(expected_node_ids), "summary has one node per expected id"
    )
    summary_node_ids: list[str] = []
    for node in nodes:
        _require(isinstance(node, dict), "summary node is an object")
        node_id = node.get("nodeId")
        _require(isinstance(node_id, str), "summary node id is a string")
        summary_node_ids.append(node_id)
        _require(node.get("status") == "passed", f"summary node {node_id} passed")
        _require(
            node.get("traceId") == trace_id, f"summary node {node_id} trace matches"
        )
    _require(
        len(set(summary_node_ids)) == len(summary_node_ids)
        and set(summary_node_ids) == set(expected_node_ids),
        "summary node identities are exact",
    )

    carrier = _load_bounded_json_object(
        canonical_run_dir / "trace-context.json",
        MAX_TRACE_CARRIER_BYTES,
        "trace-context.json",
    )
    _require(
        _trace_id_from_carrier(carrier) == trace_id, "carrier trace matches summary"
    )
    for node_id in expected_node_ids:
        envelope = _load_bounded_json_object(
            canonical_run_dir / "envelope" / f"{node_id}.json",
            MAX_JSON_BYTES,
            f"envelope/{node_id}.json",
        )
        _require(
            envelope.get("nodeId") == node_id, f"envelope {node_id} identity is exact"
        )
        _require(
            envelope.get("envelopeVersion") == 1,
            f"envelope {node_id} uses canonical schema v1",
        )
        _require(envelope.get("status") == "passed", f"envelope {node_id} passed")
        _require(
            envelope.get("traceId") == trace_id, f"envelope {node_id} trace matches"
        )
        _require(
            envelope.get("capturedStdoutLog")
            == f"node-logs/{node_id}.stdout.log",
            f"envelope {node_id} stdout pointer is canonical",
        )
        _require(
            envelope.get("inputContextFile") == f"context/{node_id}.input.json",
            f"envelope {node_id} context pointer is canonical",
        )
        assertions = envelope.get("assertions")
        _require(
            isinstance(assertions, list)
            and all(
                isinstance(assertion, dict)
                and set(assertion) == {"name", "status"}
                and assertion.get("status") == "passed"
                for assertion in assertions
            ),
            f"envelope {node_id} has no failed assertion under passed status",
        )
        context_snapshot = _load_bounded_json_object(
            canonical_run_dir / "context" / f"{node_id}.input.json",
            MAX_JSON_BYTES,
            f"context/{node_id}.input.json",
        )
        _require(
            set(context_snapshot) == {"items"},
            f"context snapshot {node_id} has exact root fields",
        )
        items = context_snapshot.get("items")
        _require(
            isinstance(items, list) and len(items) <= MAX_ENVELOPE_FILES,
            f"context snapshot {node_id} has a bounded items array",
        )
        context_node_ids: list[str] = []
        for item in items:
            _require(
                isinstance(item, dict) and set(item) == {"nodeId", "data"},
                f"context snapshot {node_id} item has exact fields",
            )
            upstream_node_id = item.get("nodeId")
            data = item.get("data")
            _require(
                isinstance(upstream_node_id, str)
                and NODE_ID_RE.fullmatch(upstream_node_id) is not None,
                f"context snapshot {node_id} has a valid upstream node id",
            )
            _require(
                isinstance(data, dict)
                and all(
                    isinstance(key, str) and isinstance(value, str)
                    for key, value in data.items()
                ),
                f"context snapshot {node_id} data is a string map",
            )
            context_node_ids.append(upstream_node_id)
        _require(
            len(context_node_ids) == len(set(context_node_ids)),
            f"context snapshot {node_id} upstream ids are unique",
        )

    return RunIdentity(
        run_id=canonical_run_dir.name,
        status="passed",
        graph_name=graph_name,
        mode=mode,
        selected_node_id=selected_node_id,
        source_build=expected_source,
        expected_node_ids=expected_node_ids,
        observed_node_ids=frozenset(observed),
        observed_context_node_ids=observed_context,
        trace_id=trace_id,
        complete=True,
    )


def verify_fresh_replay(
    baseline: SmokeBaseline,
    replay: subprocess.CompletedProcess[str],
    *,
    graph: str,
    mode: str,
    selected_node_id: str,
    expected_node_ids: tuple[str, ...],
) -> ReplayEvidence:
    if replay.returncode != 0:
        raise ReplayEvidenceError(summarize(f"{mode}_{selected_node_id}", replay))
    target_run_id = extract_single_run_id(output_of(replay), graph)
    target_build_dir = PROJECT_GRAPH_ROOT / "build/validation-reports" / target_run_id
    source_dir = _canonical_directory(baseline.build_dir, "source build")
    target_dir = _canonical_directory(target_build_dir, "target build")
    _require(target_run_id != baseline.run_id, "replay target run id is fresh")
    _require(target_dir != source_dir, "replay target path differs from source")
    _require(
        target_dir.parent == source_dir.parent, "replay target is a sibling report"
    )

    source_after_replay = snapshot_run_evidence(source_dir, SMOKE_NODE_IDS)
    target_before_regeneration = snapshot_run_evidence(target_dir, expected_node_ids)
    target_identity = inspect_run_identity(
        target_dir,
        graph_name=graph,
        mode=mode,
        selected_node_id=selected_node_id,
        source_build=source_dir,
        expected_node_ids=expected_node_ids,
    )
    _require(
        target_before_regeneration.digest("trace-context.json")
        == baseline.snapshot.digest("trace-context.json"),
        "replay carrier bytes equal source carrier bytes",
    )
    _require(
        target_identity.trace_id == baseline.identity.trace_id,
        "replay trace id equals source trace id",
    )

    manual_report = run_manual_report(target_run_id)
    source_after_regeneration = snapshot_run_evidence(source_dir, SMOKE_NODE_IDS)
    target_after_regeneration = snapshot_run_evidence(
        target_dir,
        expected_node_ids,
    )
    source_identity_after = inspect_run_identity(
        source_dir,
        graph_name=graph,
        mode="full",
        selected_node_id=None,
        source_build=None,
        expected_node_ids=SMOKE_NODE_IDS,
    )
    target_identity_after = inspect_run_identity(
        target_dir,
        graph_name=graph,
        mode=mode,
        selected_node_id=selected_node_id,
        source_build=source_dir,
        expected_node_ids=expected_node_ids,
    )

    checks = (
        ("replay_target_run_id_found", bool(target_run_id)),
        ("replay_target_is_fresh", target_dir != source_dir),
        (
            "source_evidence_unchanged_after_replay",
            source_after_replay == baseline.snapshot,
        ),
        (
            "source_context_unchanged_after_replay",
            source_after_replay.under("context") == baseline.snapshot.under("context"),
        ),
        ("target_graph_identity_exact", target_identity.graph_name == graph),
        (
            "target_execution_scope_exact",
            target_identity.expected_node_ids == expected_node_ids,
        ),
        (
            "target_summary_passed_and_complete",
            target_identity.status == "passed" and target_identity.complete,
        ),
        (
            "target_expected_observed_node_sets_exact",
            target_identity.observed_node_ids == frozenset(expected_node_ids),
        ),
        (
            "target_context_snapshots_exact",
            target_identity.observed_context_node_ids == expected_node_ids
            and len(target_before_regeneration.under("context"))
            == len(expected_node_ids),
        ),
        (
            "source_target_trace_equal",
            target_identity.trace_id == baseline.identity.trace_id,
        ),
        ("manual_report_regeneration_passed", manual_report.returncode == 0),
        (
            "source_evidence_unchanged_after_regeneration",
            source_after_regeneration == baseline.snapshot,
        ),
        (
            "source_context_unchanged_after_regeneration",
            source_after_regeneration.under("context")
            == baseline.snapshot.under("context"),
        ),
        (
            "source_verdict_scope_trace_unchanged",
            source_identity_after == baseline.identity,
        ),
        (
            "target_evidence_unchanged_after_regeneration",
            target_after_regeneration == target_before_regeneration,
        ),
        (
            "target_context_unchanged_after_regeneration",
            target_after_regeneration.under("context")
            == target_before_regeneration.under("context"),
        ),
        (
            "target_verdict_scope_trace_unchanged",
            target_identity_after == target_identity,
        ),
    )
    return ReplayEvidence(
        source_build_dir=source_dir,
        target_run_id=target_run_id,
        target_build_dir=target_dir,
        trace_id=target_identity.trace_id,
        source_evidence_sha256=baseline.snapshot.manifest_sha256(),
        target_evidence_sha256=target_before_regeneration.manifest_sha256(),
        checks=checks,
        manual_report=manual_report,
    )


def run_manual_report(run_id: str) -> subprocess.CompletedProcess[str]:
    return run_command(
        "validationReport",
        [
            str(PROJECT_GRAPH_ROOT / "gradlew"),
            "--no-daemon",
            "--max-workers=1",
            "--console=plain",
            "-Pkotlin.compiler.execution.strategy=in-process",
            "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m",
            "validationReport",
            "--run-id",
            run_id,
        ],
        env=_bounded_gradle_env(),
        cwd=PROJECT_GRAPH_ROOT,
    )


def _bounded_gradle_env(env: Mapping[str, str] | None = None) -> dict[str, str]:
    configured = os.environ.copy()
    configured.update(env or {})
    option_channels = ("GRADLE_OPTS", *JVM_OPTION_CHANNELS)
    for name in option_channels:
        value = configured.get(name)
        if value is None:
            continue
        tokens = _parse_jvm_option_channel(name, value)
        _reject_indirect_jvm_options(name, tokens)
    try:
        configured = scripts_common.gradle_env_with_daemon_disabled(configured)
    except ValueError as exc:
        raise ReplayEvidenceError(
            "Gradle/JVM option environment is not valid shell syntax"
        ) from exc

    # Kotlin's standalone launchers have two additional JVM channels that the
    # shared Gradle sanitizer does not consume. Strip sizing flags there too,
    # then verify every channel is clean so future sanitizer drift fails closed.
    for name in ("KOTLIN_OPTS", "KOTLIN_DAEMON_JVM_OPTIONS"):
        value = configured.get(name)
        if value is None:
            continue
        tokens = _parse_jvm_option_channel(name, value)
        configured[name] = shlex.join(
            token for token in tokens if JVM_MEMORY_OVERRIDE_RE.search(token) is None
        )
    for name in option_channels:
        value = configured.get(name)
        if value is None:
            continue
        _reject_jvm_memory_overrides(name, _parse_jvm_option_channel(name, value))
    return configured


def _parse_jvm_option_channel(name: str, value: str) -> list[str]:
    try:
        return shlex.split(value)
    except ValueError as exc:
        raise ReplayEvidenceError(f"{name} is not valid shell syntax") from exc


def _reject_indirect_jvm_options(name: str, tokens: list[str]) -> None:
    for token in tokens:
        if token.startswith(JVM_OPTION_FILE_PREFIXES):
            raise ReplayEvidenceError(
                f"{name} contains an indirect JVM option file: {token!r}"
            )


def _reject_jvm_memory_overrides(name: str, tokens: list[str]) -> None:
    for token in tokens:
        if JVM_MEMORY_OVERRIDE_RE.search(token):
            raise ReplayEvidenceError(
                f"{name} contains a JVM memory override: {token!r}"
            )


def _canonical_directory(path: Path | None, label: str) -> Path:
    if path is None:
        raise ReplayEvidenceError(f"{label} is missing")
    if path.is_symlink():
        raise ReplayEvidenceError(f"{label} must not be a symlink: {path}")
    try:
        canonical = path.resolve(strict=True)
    except OSError as exc:
        raise ReplayEvidenceError(f"{label} does not exist: {path}") from exc
    if not canonical.is_dir() or canonical.is_symlink():
        raise ReplayEvidenceError(f"{label} is not a regular directory: {path}")
    return canonical


def _hash_bounded_file(path: Path, max_bytes: int, label: str) -> str:
    try:
        before = path.lstat()
    except OSError as exc:
        raise ReplayEvidenceError(f"missing evidence file: {label}") from exc
    if not stat.S_ISREG(before.st_mode):
        raise ReplayEvidenceError(f"evidence file is not regular: {label}")
    if before.st_size > max_bytes:
        raise ReplayEvidenceError(f"{label} exceeds {max_bytes} bytes")
    digest = hashlib.sha256()
    total = 0
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(min(64 * 1024, max_bytes - total + 1))
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise ReplayEvidenceError(f"{label} exceeds {max_bytes} bytes")
                digest.update(chunk)
    except OSError as exc:
        raise ReplayEvidenceError(f"could not read evidence file: {label}") from exc
    try:
        after = path.lstat()
    except OSError as exc:
        raise ReplayEvidenceError(
            f"evidence file disappeared while hashing: {label}"
        ) from exc
    if (
        not stat.S_ISREG(after.st_mode)
        or total != before.st_size
        or before.st_size != after.st_size
        or before.st_mtime_ns != after.st_mtime_ns
    ):
        raise ReplayEvidenceError(f"evidence file changed while hashing: {label}")
    return digest.hexdigest()


def _read_bounded_file(path: Path, max_bytes: int, label: str) -> bytes:
    try:
        info = path.lstat()
    except OSError as exc:
        raise ReplayEvidenceError(f"missing evidence file: {label}") from exc
    if not stat.S_ISREG(info.st_mode):
        raise ReplayEvidenceError(f"evidence file is not regular: {label}")
    if info.st_size > max_bytes:
        raise ReplayEvidenceError(f"{label} exceeds {max_bytes} bytes")
    try:
        with path.open("rb") as handle:
            raw = handle.read(max_bytes + 1)
    except OSError as exc:
        raise ReplayEvidenceError(f"could not read evidence file: {label}") from exc
    if len(raw) > max_bytes:
        raise ReplayEvidenceError(f"{label} exceeds {max_bytes} bytes")
    try:
        after = path.lstat()
    except OSError as exc:
        raise ReplayEvidenceError(f"{label} disappeared while reading") from exc
    if (
        not stat.S_ISREG(after.st_mode)
        or len(raw) != info.st_size
        or info.st_size != after.st_size
        or info.st_mtime_ns != after.st_mtime_ns
    ):
        raise ReplayEvidenceError(f"{label} changed while reading")
    return raw


def _load_bounded_json_object(path: Path, max_bytes: int, label: str) -> dict[str, Any]:
    raw = _read_bounded_file(path, max_bytes, label)
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ReplayEvidenceError(f"{label} is not strict UTF-8") from exc
    try:
        parsed = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite_number,
        )
    except (ValueError, RecursionError) as exc:
        raise ReplayEvidenceError(f"{label} is not strict JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ReplayEvidenceError(f"{label} must contain one JSON object")
    _validate_json_bounds(parsed, label)
    return parsed


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite_number(value: str) -> Any:
    raise ValueError(f"non-finite JSON number {value!r}")


def _validate_json_bounds(value: Any, label: str) -> None:
    stack: list[tuple[Any, int]] = [(value, 1)]
    values_seen = 0
    while stack:
        current, depth = stack.pop()
        values_seen += 1
        if values_seen > MAX_JSON_VALUES:
            raise ReplayEvidenceError(f"{label} exceeds {MAX_JSON_VALUES} JSON values")
        if depth > MAX_JSON_DEPTH:
            raise ReplayEvidenceError(f"{label} exceeds JSON depth {MAX_JSON_DEPTH}")
        if isinstance(current, dict):
            stack.extend((item, depth + 1) for item in current.values())
        elif isinstance(current, list):
            stack.extend((item, depth + 1) for item in current)


def _strict_string_list(value: Any, label: str) -> tuple[str, ...]:
    if not isinstance(value, list) or len(value) > MAX_ENVELOPE_FILES:
        raise ReplayEvidenceError(f"{label} must be a bounded array")
    if any(not isinstance(item, str) for item in value):
        raise ReplayEvidenceError(f"{label} entries must be strings")
    return tuple(value)


def _strict_digest_map(value: Any, label: str) -> dict[str, str]:
    if not isinstance(value, dict) or len(value) > MAX_ENVELOPE_FILES:
        raise ReplayEvidenceError(f"{label} must be a bounded object")
    if list(value) != sorted(value):
        raise ReplayEvidenceError(f"{label} paths must be serialized in sorted order")
    result: dict[str, str] = {}
    for path, digest in value.items():
        if not isinstance(path, str) or not isinstance(digest, str):
            raise ReplayEvidenceError(f"{label} must contain string/string entries")
        if SHA256_RE.fullmatch(digest) is None:
            raise ReplayEvidenceError(f"{label} contains an invalid SHA-256 digest")
        result[path] = digest
    return result


def _canonical_path_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise ReplayEvidenceError(f"{label} must be a non-empty string")
    return str(_canonical_directory(Path(value), label))


def _trace_id_from_carrier(carrier: Mapping[str, Any]) -> str:
    traceparent = carrier.get("traceparent")
    if not isinstance(traceparent, str):
        raise ReplayEvidenceError("trace carrier is missing string traceparent")
    match = TRACEPARENT_RE.fullmatch(traceparent)
    if (
        match is None
        or not _valid_trace_id(match.group(1))
        or match.group(2) == "0" * 16
    ):
        raise ReplayEvidenceError("trace carrier has an invalid traceparent")
    return match.group(1)


def _valid_trace_id(trace_id: str) -> bool:
    return trace_id != "0" * 32 and TRACE_ID_RE.fullmatch(trace_id) is not None


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReplayEvidenceError(message)


def output_of(completed: subprocess.CompletedProcess[str]) -> str:
    return completed.stdout + completed.stderr


def summarize(label: str, completed: subprocess.CompletedProcess[str]) -> str:
    text = (completed.stdout + "\n" + completed.stderr).strip()
    tail = "\n".join(text.splitlines()[-25:])
    return f"{label} exited {completed.returncode}\n{tail}"
