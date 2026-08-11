"""Repository-local adapters for TestGraph program-model cases.

Most legacy boundaries remain documented extension points. Fresh replay has a
bounded production evidence gate: once per generated-case batch it runs the
Kotlin selection, trace-continuity, and report-integrity tests under strict
local resource bounds. It is intentionally not an executable transition
refinement adapter and does not echo a TLC-derived after-state as if production
had independently observed it.
"""

from __future__ import annotations

import os
import signal
import subprocess
import threading
import time
from pathlib import Path
from typing import Any

from scripts._common import gradle_env_with_daemon_disabled


_PRODUCTION_REFINEMENT_TIMEOUT_SECONDS = 120
_PROCESS_GROUP_TERM_GRACE_SECONDS = 5
_OUTPUT_DRAIN_GRACE_SECONDS = 5
_MAX_REFINEMENT_EVIDENCE_BYTES = 128 * 1024
_OUTPUT_READ_CHUNK_BYTES = 16 * 1024


class _BoundedProcessTimeout(TimeoutError):
    """Raised only after the timed-out process group has been reaped."""


class _BoundedTailEvidence:
    """Stream a process transcript into a fixed-size on-disk tail ring.

    The file never grows beyond ``limit_bytes``. While the child runs its
    bytes are a circular buffer; ``finish`` rewrites that bounded buffer into
    chronological order so the final evidence file is directly readable.
    """

    def __init__(self, path: Path, limit_bytes: int) -> None:
        if limit_bytes <= 0:
            raise ValueError("evidence limit must be positive")
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.limit_bytes = limit_bytes
        self._file = path.open("w+b")
        self._size = 0
        self._write_position = 0
        self._finished = False

    def write(self, chunk: bytes) -> None:
        if self._finished:
            raise RuntimeError("cannot write finished evidence")
        if not chunk:
            return
        if len(chunk) >= self.limit_bytes:
            tail = chunk[-self.limit_bytes :]
            self._file.seek(0)
            self._file.write(tail)
            self._file.truncate(self.limit_bytes)
            self._file.flush()
            self._size = self.limit_bytes
            self._write_position = 0
            return

        offset = 0
        while offset < len(chunk):
            writable = min(
                len(chunk) - offset,
                self.limit_bytes - self._write_position,
            )
            self._file.seek(self._write_position)
            self._file.write(chunk[offset : offset + writable])
            self._write_position = (
                self._write_position + writable
            ) % self.limit_bytes
            self._size = min(self.limit_bytes, self._size + writable)
            offset += writable
        self._file.flush()

    def finish(self) -> None:
        if self._finished:
            return
        try:
            self._file.flush()
            if self._size == self.limit_bytes:
                self._file.seek(self._write_position)
                newer = self._file.read(self.limit_bytes - self._write_position)
                self._file.seek(0)
                older = self._file.read(self._write_position)
                chronological = newer + older
                self._file.seek(0)
                self._file.write(chronological)
            self._file.truncate(self._size)
            self._file.flush()
            os.fsync(self._file.fileno())
        finally:
            self._finished = True
            self._file.close()


def _signal_process_group(process_group_id: int, sig: int) -> None:
    try:
        os.killpg(process_group_id, sig)
    except ProcessLookupError:
        # The whole isolated process group is already gone.
        pass


def _process_group_exists(process_group_id: int) -> bool:
    try:
        os.killpg(process_group_id, 0)
    except ProcessLookupError:
        return False
    return True


def _wait_for_process_group_exit(process_group_id: int, timeout_seconds: int) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while _process_group_exists(process_group_id):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(0.05, remaining))
    return True


def _terminate_process_group(
    process: subprocess.Popen[bytes],
) -> None:
    _signal_process_group(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=_PROCESS_GROUP_TERM_GRACE_SECONDS)
    except subprocess.TimeoutExpired:
        pass

    # Always target the entire group with SIGKILL after the TERM wait. The
    # launcher can exit promptly while a descendant ignores TERM and even
    # closes stdout, which would otherwise make both wait() and the collector
    # look clean while leaving that JVM alive.
    _signal_process_group(process.pid, signal.SIGKILL)
    try:
        process.wait(timeout=_PROCESS_GROUP_TERM_GRACE_SECONDS)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(
            "production refinement launcher did not exit after SIGKILL"
        ) from exc
    if not _wait_for_process_group_exit(
        process.pid,
        _PROCESS_GROUP_TERM_GRACE_SECONDS,
    ):
        raise RuntimeError(
            "production refinement process group still exists after SIGKILL"
        )


def _drain_process_output(
    read_fd: int,
    sink: _BoundedTailEvidence,
    errors: list[BaseException],
) -> None:
    try:
        while True:
            chunk = os.read(read_fd, _OUTPUT_READ_CHUNK_BYTES)
            if not chunk:
                return
            sink.write(chunk)
    except BaseException as exc:  # surfaced on the controlling thread below
        errors.append(exc)
    finally:
        os.close(read_fd)


def _join_output_collector(
    collector: threading.Thread,
    process_group_id: int | None,
) -> None:
    collector.join(timeout=_OUTPUT_DRAIN_GRACE_SECONDS)
    if collector.is_alive() and process_group_id is not None:
        # A launcher can exit while a descendant still owns stdout. Kill the
        # isolated group so collection cannot hang forever on that descriptor.
        _signal_process_group(process_group_id, signal.SIGTERM)
        collector.join(timeout=_PROCESS_GROUP_TERM_GRACE_SECONDS)
    if collector.is_alive() and process_group_id is not None:
        _signal_process_group(process_group_id, signal.SIGKILL)
        collector.join(timeout=_PROCESS_GROUP_TERM_GRACE_SECONDS)
        if _process_group_exists(process_group_id) and not _wait_for_process_group_exit(
            process_group_id,
            _PROCESS_GROUP_TERM_GRACE_SECONDS,
        ):
            raise RuntimeError(
                "production refinement process group still exists after output-drain SIGKILL"
            )
    if collector.is_alive():
        raise RuntimeError("production refinement output collector did not stop")


def _run_bounded_process(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str],
    evidence: Path,
    timeout_seconds: int = _PRODUCTION_REFINEMENT_TIMEOUT_SECONDS,
    evidence_limit_bytes: int = _MAX_REFINEMENT_EVIDENCE_BYTES,
) -> int:
    """Run one isolated process group with bounded, continuously drained output."""

    sink = _BoundedTailEvidence(evidence, evidence_limit_bytes)
    read_fd, write_fd = os.pipe()
    collector_errors: list[BaseException] = []
    collector = threading.Thread(
        target=_drain_process_output,
        args=(read_fd, sink, collector_errors),
        name="fresh-replay-refinement-output",
        daemon=True,
    )
    collector.start()
    process: subprocess.Popen[bytes] | None = None
    timed_out = False
    try:
        try:
            process = subprocess.Popen(
                command,
                cwd=cwd,
                env=env,
                stdout=write_fd,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        finally:
            # Popen duplicates this descriptor into the child. The parent must
            # not retain a writer or the collector would never observe EOF.
            os.close(write_fd)
    except BaseException:
        try:
            _join_output_collector(collector, None)
        finally:
            sink.finish()
        raise

    try:
        try:
            return_code = process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            _terminate_process_group(process)
            return_code = process.returncode if process.returncode is not None else -1
        except BaseException as wait_failure:
            # Cancellation, SIGINT/KeyboardInterrupt, and unexpected wait
            # failures do not transfer ownership of the isolated process
            # group. Reap it before propagating the controlling failure.
            try:
                _terminate_process_group(process)
            except BaseException as cleanup_failure:
                raise cleanup_failure from wait_failure
            raise
    finally:
        try:
            _join_output_collector(collector, process.pid)
        finally:
            sink.finish()

    # A launcher can return successfully after a descendant has daemonized,
    # closed the shared output descriptor, and remained alive in the isolated
    # process group. Collector failure is likewise not permission to abandon
    # ownership: check and reap the group before propagating *any* terminal
    # outcome.
    # The timeout path already performed and verified a full group cleanup
    # before collection joined. Normal completion still needs this post-wait
    # ownership check, especially when the collector itself failed.
    live_group_after_wait = not timed_out and _process_group_exists(process.pid)
    if live_group_after_wait:
        _terminate_process_group(process)

    if collector_errors:
        raise RuntimeError("failed to collect production refinement output") from collector_errors[0]
    if timed_out:
        raise _BoundedProcessTimeout(
            f"production refinement exceeded {timeout_seconds} seconds"
        )
    if live_group_after_wait:
        raise RuntimeError(
            "production refinement launcher exited with live descendants"
        )
    return return_code


def _evidence_tail(path: Path, limit_bytes: int = 8 * 1024) -> str:
    return path.read_bytes()[-limit_bytes:].decode("utf-8", errors="replace")


class _DocumentedBoundaryAdapter:
    """Shared conservative adapter shape for onboarding."""

    boundary = "unassigned"
    files: tuple[str, ...] = ()

    def can_run(self, case):
        del case
        return False, (
            f"{self.boundary} is documented in specs/program_model/spec_manifest.yaml; "
            "wire an executable refinement adapter in a future behavior ticket"
        )


class ScaffoldProjectAdapter(_DocumentedBoundaryAdapter):
    boundary = "scaffold_project"
    files = ("scripts/scaffold.py", "project_sdk_sources/")


class GraphPlanningAdapter(_DocumentedBoundaryAdapter):
    boundary = "graph_planning"
    files = (
        "scripts/discover.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/GraphAssembler.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/GraphModel.kt",
    )


class GraphRunAdapter(_DocumentedBoundaryAdapter):
    boundary = "graph_run"
    files = (
        "scripts/run.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
    )


class ReportAdapter(_DocumentedBoundaryAdapter):
    boundary = "reports"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/ValidationReportTask.kt",
    )


class InputContextSnapshotAdapter(_DocumentedBoundaryAdapter):
    boundary = "input_context_snapshots"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt",
        "project_sdk_sources/sources/ContextSnapshotsPresent.py",
    )


class RerunMetadataAdapter(_DocumentedBoundaryAdapter):
    boundary = "rerun_metadata"
    files = (
        "project_sdk_sources/sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/NodeSpec.java",
        "project_sdk_sources/sdk/python/src/testgraphsdk/node_spec.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/ValidationNodeSpec.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/NodeDescribeLoader.kt",
        "project_sdk_sources/sources/RerunDisabledProbe.py",
    )


class ResumeRunFromBuildAdapter(_DocumentedBoundaryAdapter):
    boundary = "resume_from_build"
    files = (
        "scripts/run.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt",
    )


class RunOnlyNodeFromBuildAdapter(_DocumentedBoundaryAdapter):
    boundary = "run_only_node_from_build"
    files = (
        "scripts/run.py",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt",
        "test_graph/sources/RunOnlyJbang.py",
        "test_graph/sources/RunOnlyUv.py",
    )


class RerunGuidanceAdapter(_DocumentedBoundaryAdapter):
    boundary = "rerun_guidance"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt",
        "project_sdk_sources/sources/RerunGuidanceFailure.py",
        "references/workflows.md",
        "references/reference.md",
    )


class FreshReplayEvidenceGateAdapter:
    """Execute the production replay integrity suite once per evidence batch.

    The Kotlin tests exercise the real plan selector, immutable fresh report
    allocation, source-carrier continuation, persisted execution scope, and
    inline/manual report regeneration. This class is deliberately not mapped
    to individual TLA actions: it provides production evidence but does not
    expose the transition-specific production state projection required for a
    refinement adapter.
    """

    boundary = "fresh_replay_attempt"
    files = (
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/GraphObservability.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt",
        "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt",
    )
    _test_filters = (
        "com.hayden.testgraphsdk.exec.ContextSerdeTest",
        "com.hayden.testgraphsdk.exec.PlanExecutorResultIntegrityTest",
        "com.hayden.testgraphsdk.exec.PlanExecutorResumeHarnessTest",
        "com.hayden.testgraphsdk.exec.GraphObservabilityTest",
        "com.hayden.testgraphsdk.exec.ExecutorsProcessTreeTest",
        "com.hayden.testgraphsdk.tasks.RunReportWriterTraceTest",
    )

    def setup_all(self, context: Any) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        sdk_root = repo_root / "project_sdk_sources"
        command = [
            str(sdk_root / "gradlew"),
            "--no-daemon",
            "--max-workers=1",
            "-Pkotlin.compiler.execution.strategy=in-process",
            "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m",
            ":build-logic:test",
            "--rerun-tasks",
        ]
        for test_filter in self._test_filters:
            command.extend(("--tests", test_filter))
        environment = gradle_env_with_daemon_disabled(os.environ.copy())
        launcher_memory_guard = "-Xmx768m -XX:MaxMetaspaceSize=384m"
        environment["JAVA_OPTS"] = " ".join(
            part
            for part in (environment.get("JAVA_OPTS", ""), launcher_memory_guard)
            if part
        )
        # The shared sanitizer also replaces every Gradle project-property
        # override with these same guarded values and removes Kotlin daemon
        # JVM arguments. Keep those defense-in-depth environment guards in
        # addition to the explicit CLI options above.
        evidence = context.work_dir / "fresh-replay-production-refinement.txt"
        try:
            return_code = _run_bounded_process(
                command,
                cwd=sdk_root,
                env=environment,
                evidence=evidence,
                timeout_seconds=_PRODUCTION_REFINEMENT_TIMEOUT_SECONDS,
                evidence_limit_bytes=_MAX_REFINEMENT_EVIDENCE_BYTES,
            )
        except _BoundedProcessTimeout as exc:
            raise AssertionError(
                "production fresh-replay refinement tests timed out after "
                f"{_PRODUCTION_REFINEMENT_TIMEOUT_SECONDS} seconds; "
                f"the isolated process group was terminated; see {evidence}:\n"
                f"{_evidence_tail(evidence)}"
            ) from exc
        if return_code != 0:
            raise AssertionError(
                "production fresh-replay refinement tests failed; "
                f"see {evidence}:\n{_evidence_tail(evidence)}"
            )
        context.shared["fresh_replay_refinement_evidence"] = str(evidence)
        self._evidence = evidence

    def can_run(self, case: Any) -> tuple[bool, str | None]:
        del case
        return True, None

    def run(self, case: Any, work_dir: Path | None = None) -> dict[str, Any]:
        del case
        evidence = getattr(self, "_evidence", None)
        if evidence is None:
            raise AssertionError("fresh replay refinement setup did not run")
        if work_dir is not None:
            pointer = work_dir / "production-refinement-evidence.txt"
            pointer.write_text(str(evidence), encoding="utf-8")
        return {"semantic_output": {"evidence": str(evidence)}}
