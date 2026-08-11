from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from specs.program_model import production_adapters


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


REPLAY_ACTIONS = [
    "PrepareReplayGraph",
    "StartFullAttempt",
    "StartReplayAttempt",
    "RunAttemptNodePass",
    "RunAttemptNodeTerminal",
    "FinishAttemptSuccess",
    "PublishAttemptClosure",
    "AcquireReplaySource",
    "TamperClosedAttemptEvidence",
    "RejectTamperedReplaySource",
    "WriteInlineAttemptReport",
    "RegenerateAttemptReport",
]

REPLAY_INVARIANTS = [
    "AttemptIdentityIsGraphScoped",
    "ReplayScopeMatchesMode",
    "ReplaySourceAttemptsAreClosed",
    "ReplayTraceCarrierContinuity",
    "CarrierIdentifiesOneTrace",
    "FullAttemptsMintIndependentTraceCarriers",
    "AttemptEvidenceIsScoped",
    "AttemptContextIsAttemptLocal",
    "AttemptClosuresBindExactEvidence",
    "TamperedUnacquiredSourcesFailValidation",
    "AcquiredReplaySnapshotsAreClosureBound",
    "AttemptEnvelopeTraceContinuity",
    "AttemptReportsAreTruthful",
    "IncompleteAttemptReportsNeverPass",
]


def test_replay_model_has_exact_context_closure_and_snapshot_semantics() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")

    for action in REPLAY_ACTIONS:
        assert f"@command {action}" in tla
    for invariant in REPLAY_INVARIANTS:
        assert f"@invariant {invariant}" in tla

    assert "attempt_graph" in tla
    assert "attempt_source" in tla
    assert "attempt_plan" in tla
    assert "attempt_input_context" in tla
    assert "attempt_envelope_trace" in tla
    assert "attempt_report_last_writer" in tla
    assert "ContextItemFingerprint" in tla
    assert "exactOrderedItems" in tla
    assert "CanonicalAttemptEvidenceFingerprint" in tla
    assert "contextSha256" in tla
    assert "envelopeSha256" in tla
    assert "ClosureMatchesCurrentEvidence" in tla
    assert "ReportIsComplete(attempt) ==\n  /\\ ClosureMatchesCurrentEvidence(attempt)" in tla
    assert '/\\ attempt_mode[source] = "full"' in tla
    assert "/\\ attempt_plan[source] = GraphPlan" in tla
    assert "/\\ sourceContext = ExpectedNodeInputContext(source, selected)" in tla
    assert "an existing report is" in tla
    assert 'ELSE "errored"]' in tla
    inline_action = tla.split("WriteInlineAttemptReport(attempt) ==", 1)[1].split(
        "RegenerateAttemptReport(attempt) ==", 1
    )[0]
    regenerated_action = tla.split("RegenerateAttemptReport(attempt) ==", 1)[1].split(
        "NoOp ==", 1
    )[0]
    assert "attempt \\in attempt_closed" not in inline_action
    assert "attempt \\in attempt_closed" not in regenerated_action
    assert "attempt \\in allocated_attempts \\ active_attempts" in inline_action
    assert "attempt \\in allocated_attempts \\ active_attempts" in regenerated_action
    assert 'attempt_report_status[attempt] \\in {"errored", currentStatus}' in tla
    assert "acquired_replay_context" in tla
    assert "InlineReportStatus" in tla
    assert "@property ClosedEvidenceBindingsAndAcquiredSnapshotsAreImmutable" in tla
    assert "[][ReplayEvidenceBindingStep]_vars" in tla
    assert "change before acquisition fails validation" in tla
    assert "change after acquisition cannot alter" in tla


def test_replay_configuration_is_bounded_and_has_a_three_node_graph() -> None:
    cfg = (SPEC_ROOT / "Replay.cfg").read_text(encoding="utf-8")

    assert "SPECIFICATION ReplaySpec" in cfg
    assert "Nodes = {NodeA, NodeB, NodeC}" in cfg
    assert "SourceNodes = {NodeA, NodeB, NodeC}" in cfg
    assert "RunAttempts = {AttemptA, AttemptB}" in cfg
    assert "PlanLength = 3" in cfg
    assert "PlanFirstNode = NodeA" in cfg
    assert "PlanSecondNode = NodeB" in cfg
    assert "PlanThirdNode = NodeC" in cfg
    assert "AttemptClosuresBindExactEvidence" in cfg
    assert "TamperedUnacquiredSourcesFailValidation" in cfg
    assert "AcquiredReplaySnapshotsAreClosureBound" in cfg
    assert "ClosedEvidenceBindingsAndAcquiredSnapshotsAreImmutable" in cfg
    assert "source \\in FullAttempts" in (SPEC_ROOT / "TestGraph.tla").read_text(
        encoding="utf-8"
    )
    assert "StartResumeAttempt(source, attempt)" in (
        SPEC_ROOT / "TestGraph.tla"
    ).read_text(encoding="utf-8")
    assert "StartRunOnlyAttempt(source, attempt)" in (
        SPEC_ROOT / "TestGraph.tla"
    ).read_text(encoding="utf-8")


def test_replay_actions_are_not_falsely_mapped_to_the_batch_evidence_gate() -> None:
    mappings = (SPEC_ROOT / "case_adapters.toml").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")

    for action in REPLAY_ACTIONS:
        assert f"[adapters.{action}]" not in mappings
    assert "FreshReplayEvidenceGateAdapter" not in mappings
    assert "fresh_replay_production_batch_evidence_gate_enabled" in manifest
    assert "transition_refinement_pending" in manifest
    assert "blocked_on_report_context_completeness" not in manifest
    assert "bounded_configuration: Replay.cfg" in manifest
    assert "report_context_completeness" in manifest
    assert "status: implementation_complete_focused_validation_passed" in manifest
    assert "passed 89/89 tests" in manifest
    assert "12,700 states generated" in manifest
    assert "context/<nodeId>.input.json" in manifest
    assert "self.rerun.graph.jbang" in manifest
    assert "self.rerun.graph.uv" in manifest
    assert "self.run.only.jbang" in manifest
    assert "self.run.only.uv" in manifest
    assert "authorized_canary: pending fresh post-closure-v2 execution" in manifest
    assert "No previous count, RSS figure, or report path" not in manifest
    assert "20260722-081549" not in manifest
    assert "99f83357f31f0d3c597c7a4edaf9149a" not in manifest


def test_refinement_adapter_runs_production_tests_with_memory_guards(
    monkeypatch,
    tmp_path: Path,
) -> None:
    observed: dict[str, object] = {}
    monkeypatch.setenv(
        "GRADLE_OPTS",
        "-Dkeep=true -Xmx6g '-Dorg.gradle.jvmargs=-Xmx5g'",
    )
    monkeypatch.setenv(
        "JAVA_OPTS",
        "-Djava.keep=true -Xmx4g -XX:MaxMetaspaceSize=1g",
    )
    monkeypatch.setenv("JAVA_TOOL_OPTIONS", "-Dtool.keep=true -Xms3g")
    monkeypatch.setenv("_JAVA_OPTIONS", "-Dunderscore.keep=true -XX:MaxRAMPercentage=95")
    monkeypatch.setenv("JDK_JAVA_OPTIONS", "-Djdk.keep=true -XX:MaxDirectMemorySize=8g")
    monkeypatch.setenv("ORG_GRADLE_PROJECT_org.gradle.jvmargs", "-Xmx9g")
    monkeypatch.setenv("ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs", "-Xmx9g")

    def fake_bounded_process(command, **kwargs):
        observed["command"] = command
        observed["kwargs"] = kwargs
        kwargs["evidence"].write_text("BUILD SUCCESSFUL\n", encoding="utf-8")
        return 0

    monkeypatch.setattr(
        production_adapters,
        "_run_bounded_process",
        fake_bounded_process,
    )
    context = SimpleNamespace(work_dir=tmp_path, shared={})
    adapter = production_adapters.FreshReplayEvidenceGateAdapter()

    adapter.setup_all(context)

    command = observed["command"]
    kwargs = observed["kwargs"]
    assert "--no-daemon" in command
    assert "--max-workers=1" in command
    assert "-Pkotlin.compiler.execution.strategy=in-process" in command
    assert "-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m" in command
    for test_filter in (
        "com.hayden.testgraphsdk.exec.ContextSerdeTest",
        "com.hayden.testgraphsdk.exec.PlanExecutorResultIntegrityTest",
        "com.hayden.testgraphsdk.exec.PlanExecutorResumeHarnessTest",
        "com.hayden.testgraphsdk.exec.GraphObservabilityTest",
        "com.hayden.testgraphsdk.exec.ExecutorsProcessTreeTest",
        "com.hayden.testgraphsdk.tasks.RunReportWriterTraceTest",
    ):
        assert test_filter in command
    assert kwargs["timeout_seconds"] == 120
    assert kwargs["evidence_limit_bytes"] == 128 * 1024
    environment = kwargs["env"]
    assert environment[
        "ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy"
    ] == "in-process"
    assert environment["ORG_GRADLE_PROJECT_org.gradle.workers.max"] == "1"
    assert environment["ORG_GRADLE_PROJECT_org.gradle.daemon"] == "false"
    assert environment["ORG_GRADLE_PROJECT_org.gradle.jvmargs"] == (
        "-Xmx768m -XX:MaxMetaspaceSize=384m"
    )
    assert "ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs" not in environment
    assert environment["GRADLE_OPTS"] == "-Dkeep=true -Dorg.gradle.daemon=false"
    assert environment["JAVA_OPTS"] == (
        "-Djava.keep=true -Xmx768m -XX:MaxMetaspaceSize=384m"
    )
    assert environment["JAVA_TOOL_OPTIONS"] == "-Dtool.keep=true"
    assert environment["_JAVA_OPTIONS"] == "-Dunderscore.keep=true"
    assert environment["JDK_JAVA_OPTIONS"] == "-Djdk.keep=true"
    assert Path(context.shared["fresh_replay_refinement_evidence"]).is_file()

    case_dir = tmp_path / "case"
    case_dir.mkdir()
    result = adapter.run(SimpleNamespace(), work_dir=case_dir)
    assert result["semantic_output"]["evidence"] == context.shared[
        "fresh_replay_refinement_evidence"
    ]
    assert (case_dir / "production-refinement-evidence.txt").is_file()


def test_bounded_process_streams_only_the_fixed_size_output_tail(
    monkeypatch,
    tmp_path: Path,
) -> None:
    observed: dict[str, object] = {}
    evidence_limit = 1_024
    payload = b"discarded-prefix\n" + (b"x" * 4_096) + b"\nFINAL-MARKER\n"

    class FakeProcess:
        pid = 41_001
        returncode = 0

        def wait(self, timeout):
            observed["wait_timeout"] = timeout
            return self.returncode

    def fake_popen(command, **kwargs):
        observed["command"] = command
        observed["popen_kwargs"] = kwargs
        remaining = memoryview(payload)
        while remaining:
            written = os.write(kwargs["stdout"], remaining)
            remaining = remaining[written:]
        return FakeProcess()

    monkeypatch.setattr(production_adapters.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(
        production_adapters,
        "_process_group_exists",
        lambda process_group_id: False,
    )
    evidence = tmp_path / "bounded-evidence.txt"

    return_code = production_adapters._run_bounded_process(
        ["fake-gradle"],
        cwd=tmp_path,
        env={},
        evidence=evidence,
        timeout_seconds=17,
        evidence_limit_bytes=evidence_limit,
    )

    assert return_code == 0
    assert observed["wait_timeout"] == 17
    popen_kwargs = observed["popen_kwargs"]
    assert popen_kwargs["stdout"] != subprocess.PIPE
    assert isinstance(popen_kwargs["stdout"], int)
    assert popen_kwargs["stderr"] == subprocess.STDOUT
    assert popen_kwargs["start_new_session"] is True
    retained = evidence.read_bytes()
    assert len(retained) == evidence_limit
    assert retained.endswith(b"FINAL-MARKER\n")
    assert b"discarded-prefix" not in retained


def test_bounded_process_timeout_kills_descendants_after_launcher_exits_on_term(
    monkeypatch,
    tmp_path: Path,
) -> None:
    observed_signals: list[tuple[int, signal.Signals]] = []
    verified_groups: list[tuple[int, int]] = []

    class FakeTimedOutProcess:
        pid = 42_002
        returncode = None

        def __init__(self) -> None:
            self.wait_timeouts: list[int] = []

        def wait(self, timeout):
            self.wait_timeouts.append(timeout)
            if len(self.wait_timeouts) == 1:
                raise subprocess.TimeoutExpired("fake-gradle", timeout)
            if len(self.wait_timeouts) == 2:
                # The launcher exits on TERM, but a same-group descendant is
                # modeled as surviving until the unconditional SIGKILL.
                self.returncode = -signal.SIGTERM
            return self.returncode

    process = FakeTimedOutProcess()

    def fake_popen(command, **kwargs):
        del command
        os.write(kwargs["stdout"], b"timeout-evidence\n")
        return process

    def fake_killpg(process_group_id, sig):
        observed_signals.append((process_group_id, sig))

    def fake_wait_for_process_group_exit(process_group_id, timeout_seconds):
        verified_groups.append((process_group_id, timeout_seconds))
        return True

    monkeypatch.setattr(production_adapters.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(production_adapters.os, "killpg", fake_killpg)
    monkeypatch.setattr(
        production_adapters,
        "_wait_for_process_group_exit",
        fake_wait_for_process_group_exit,
    )
    evidence = tmp_path / "timeout-evidence.txt"

    with pytest.raises(production_adapters._BoundedProcessTimeout):
        production_adapters._run_bounded_process(
            ["fake-gradle"],
            cwd=tmp_path,
            env={},
            evidence=evidence,
            timeout_seconds=3,
            evidence_limit_bytes=128,
        )

    assert process.wait_timeouts == [3, 5, 5]
    assert observed_signals == [
        (process.pid, signal.SIGTERM),
        (process.pid, signal.SIGKILL),
    ]
    assert verified_groups == [(process.pid, 5)]
    assert evidence.read_bytes() == b"timeout-evidence\n"


def test_bounded_process_reaps_closed_pipe_descendant_after_launcher_success(
    monkeypatch,
    tmp_path: Path,
) -> None:
    observed: dict[str, object] = {"group_exists": True}
    terminated: list[int] = []

    class FakeSuccessfulLauncher:
        pid = 43_003
        returncode = 0

        def wait(self, timeout):
            del timeout
            return self.returncode

    process = FakeSuccessfulLauncher()

    def fake_popen(command, **kwargs):
        del command
        os.write(kwargs["stdout"], b"launcher-complete\n")
        return process

    def fake_group_exists(process_group_id: int) -> bool:
        assert process_group_id == process.pid
        return bool(observed["group_exists"])

    def fake_terminate(candidate) -> None:
        assert candidate is process
        terminated.append(candidate.pid)
        observed["group_exists"] = False

    monkeypatch.setattr(production_adapters.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(
        production_adapters,
        "_process_group_exists",
        fake_group_exists,
    )
    monkeypatch.setattr(
        production_adapters,
        "_terminate_process_group",
        fake_terminate,
    )
    evidence = tmp_path / "leaked-descendant-evidence.txt"

    with pytest.raises(
        RuntimeError,
        match="launcher exited with live descendants",
    ):
        production_adapters._run_bounded_process(
            ["fake-gradle"],
            cwd=tmp_path,
            env={},
            evidence=evidence,
            timeout_seconds=3,
            evidence_limit_bytes=128,
        )

    assert terminated == [process.pid]
    assert observed["group_exists"] is False
    assert evidence.read_bytes() == b"launcher-complete\n"


def test_bounded_process_reaps_live_group_before_propagating_collector_failure(
    monkeypatch,
    tmp_path: Path,
) -> None:
    terminated: list[int] = []

    class FakeSuccessfulLauncher:
        pid = 43_104
        returncode = 0

        def wait(self, timeout):
            del timeout
            return self.returncode

    process = FakeSuccessfulLauncher()

    def fake_popen(command, **kwargs):
        del command, kwargs
        return process

    def fail_collector(read_fd, sink, errors) -> None:
        del sink
        errors.append(RuntimeError("fixture collector failure"))
        os.close(read_fd)

    def fake_terminate(candidate) -> None:
        assert candidate is process
        terminated.append(candidate.pid)

    monkeypatch.setattr(production_adapters.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(
        production_adapters,
        "_drain_process_output",
        fail_collector,
    )
    monkeypatch.setattr(
        production_adapters,
        "_process_group_exists",
        lambda process_group_id: process_group_id == process.pid,
    )
    monkeypatch.setattr(
        production_adapters,
        "_terminate_process_group",
        fake_terminate,
    )

    with pytest.raises(RuntimeError, match="failed to collect") as failure:
        production_adapters._run_bounded_process(
            ["fake-gradle"],
            cwd=tmp_path,
            env={},
            evidence=tmp_path / "collector-failure.txt",
            timeout_seconds=3,
            evidence_limit_bytes=128,
        )

    assert isinstance(failure.value.__cause__, RuntimeError)
    assert "fixture collector failure" in str(failure.value.__cause__)
    assert terminated == [process.pid]


@pytest.mark.skipif(os.name != "posix", reason="requires POSIX process groups")
def test_real_interrupt_reaps_the_isolated_process_group(tmp_path: Path) -> None:
    child_pid = tmp_path / "interrupt-child.pid"
    evidence = tmp_path / "interrupt-evidence.txt"
    helper = """
import sys
from pathlib import Path
from specs.program_model.production_adapters import _run_bounded_process

try:
    _run_bounded_process(
        ["/bin/sh", "-c", "sleep 30 & echo $! > \\\"$1\\\"; wait", "sh", sys.argv[1]],
        cwd=Path(sys.argv[2]),
        env={},
        evidence=Path(sys.argv[3]),
        timeout_seconds=25,
        evidence_limit_bytes=1024,
    )
except KeyboardInterrupt:
    raise SystemExit(130)
"""
    controller = subprocess.Popen(
        [
            sys.executable,
            "-c",
            helper,
            str(child_pid),
            str(REPO_ROOT),
            str(evidence),
        ],
        cwd=REPO_ROOT,
        start_new_session=True,
    )
    try:
        deadline = time.monotonic() + 5
        while not child_pid.is_file() and time.monotonic() < deadline:
            time.sleep(0.01)
        assert child_pid.is_file(), "fixture child did not start"
        descendant_pid = int(child_pid.read_text(encoding="utf-8").strip())

        os.kill(controller.pid, signal.SIGINT)
        assert controller.wait(timeout=10) == 130

        deadline = time.monotonic() + 5
        while _pid_is_alive(descendant_pid) and time.monotonic() < deadline:
            time.sleep(0.01)
        assert not _pid_is_alive(descendant_pid), (
            f"interrupted adapter left descendant {descendant_pid} alive"
        )
    finally:
        if controller.poll() is None:
            os.killpg(controller.pid, signal.SIGKILL)
            controller.wait(timeout=5)


def _pid_is_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


def test_production_boundaries_allocate_fresh_attempt_and_preserve_replay_identity() -> None:
    task = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunTestGraphTask.kt"
    ).read_text(encoding="utf-8")
    reports = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt"
    ).read_text(encoding="utf-8")
    traces = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/GraphObservability.kt"
    ).read_text(encoding="utf-8")

    assert "RunIds.allocate" in task
    assert "selection.executionPlan.map { it.id }" in task
    assert "val replaySourceSnapshot = resume?.let" in task
    assert "replaySourceSnapshot = replaySourceSnapshot" in task
    assert "persistExecutionScope" in reports
    assert 'RESUME_FROM_NODE("resume-from-node")' in reports
    assert 'RUN_ONLY_NODE("run-only-node")' in reports
    assert "sourceBuild" in reports
    assert "ReplaySourceSnapshot" in traces
    assert "snapshot.carrierJson" in traces
    assert "resumeCarrierSource" not in traces
    assert "replayCarrier" in traces


def test_closure_v2_binds_exact_evidence_and_replay_uses_one_capture() -> None:
    reports = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/tasks/RunReportWriter.kt"
    ).read_text(encoding="utf-8")
    contexts = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/Context.kt"
    ).read_text(encoding="utf-8")
    executor = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/PlanExecutor.kt"
    ).read_text(encoding="utf-8")
    canonical_envelope = (
        REPO_ROOT
        / "project_sdk_sources/build-logic/src/main/kotlin/com/hayden/testgraphsdk/exec/CanonicalEnvelope.kt"
    ).read_text(encoding="utf-8")

    assert 'ATTEMPT_CLOSURE_VERSION = 2' in reports
    assert 'EXECUTION_SCOPE_VERSION = 3' in reports
    for field in (
        '"scopeSha256"',
        '"carrierSha256"',
        '"contextSha256"',
        '"envelopeSha256"',
    ):
        assert field in reports
    assert "captureAndValidateClosedEvidence" in reports
    assert "requireEvidenceNamesUnchanged" in reports
    assert "evidence.contextSha256 == closure.contextSha256" in reports
    assert "evidence.envelopeSha256 == closure.envelopeSha256" in reports
    assert "collectEvidenceInventory" not in reports
    assert "FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)" in contexts
    assert "writeCapturedInputContextSnapshot" in executor
    assert "readInputContextSnapshot(resumeFromBuild.buildDir" not in executor
    assert "inputContextSnapshotFile(replay.sourceBuild" not in reports
    assert "CanonicalEnvelopeValidator.validate(" in executor
    assert "CanonicalEnvelopeValidator.validate(" in reports
    assert "const val VERSION = 1" in canonical_envelope
    assert "cannot be passed while an assertion is failed" in canonical_envelope
    assert "fields outside canonical envelope" in canonical_envelope
