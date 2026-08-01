package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ToolPaths
import com.hayden.testgraphsdk.ValidationNodeSpec
import org.gradle.api.file.Directory
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * Everything an executor needs to dispatch one node invocation.
 *
 * [contextArg] is the already-encoded value for the single {@code --context}
 * CLI flag — either an inline JSON blob or an {@code @<path>} reference.
 * Executors stay runtime-focused; serialization lives in PlanExecutor so
 * one threshold policy applies across runtimes.
 *
 * [resultOut] is where the SDK writes its NodeResult JSON. It's
 * intentionally distinct from the canonical envelope path — PlanExecutor
 * post-processes this file (stamps executor-measured fields, validates
 * shape, synthesizes a fallback for missing/malformed cases) before
 * writing the final {@code envelope/<nodeId>.json}. So even when a node
 * crashes mid-write, every planned node ends up with exactly one
 * well-formed envelope.
 *
 * [stdoutLog] is the file the executor redirects the spawned
 * node-process's merged stdout+stderr into. It's the "node crashed
 * before populating result-out" forensics channel — and the path is
 * also stamped onto the canonical envelope as a top-level
 * {@code capturedStdoutLog} field so the report renderer can show it.
 */
data class NodeInvocation(
    val spec: ValidationNodeSpec,
    val projectDir: Directory,
    val reportDir: Directory,
    val runId: String,
    val contextArg: String? = null,
    val resultOut: File,
    val stdoutLog: File,
    val environment: Map<String, String> = emptyMap(),
    /**
     * Wall-clock budget for one attempt, parsed once from
     * [ValidationNodeSpec.timeout] by `PlanExecutor`. Executors enforce
     * this via `process.waitFor(timeoutMillis, MILLISECONDS)` and
     * `destroyForcibly()` on miss — so a wedged jbang resolve / hung
     * subprocess can't stall the graph past this bound.
     */
    val timeoutMillis: Long,
)

/**
 * Outcome of one execution attempt. Kept as a sealed type so
 * `PlanExecutor` can distinguish "timed out → maybe retry" from
 * "completed (any exit code) → final result; build envelope from
 * --result-out". A non-zero exit code is NOT a retry trigger — only a
 * timeout is.
 */
sealed class ExecutionOutcome {
    data class Completed(val exitCode: Int) : ExecutionOutcome()
    data class ProcessContractViolation(
        val exitCode: Int,
        val reason: String,
    ) : ExecutionOutcome()
    /** Process didn't return within [NodeInvocation.timeoutMillis]; `destroyForcibly()` was called. */
    object TimedOut : ExecutionOutcome()
}

/** Cleanup could not prove that every process owned by one node has exited. */
internal class ProcessOwnershipUncertainException(message: String) :
    IllegalStateException(message)

/**
 * Runtime adapter — knows how to spawn one node invocation.
 *
 * Implementations own the command line for their runtime. Task code
 * stays runtime-agnostic.
 */
interface ValidationExecutor {
    val runtimeName: String
    fun execute(invocation: NodeInvocation): ExecutionOutcome
}

class ExecutorRegistry(private val executors: Map<String, ValidationExecutor>) {
    fun forNode(spec: ValidationNodeSpec): ValidationExecutor =
        executors[spec.runtime.name]
            ?: error("no executor registered for runtime '${spec.runtime.name}' (node ${spec.id})")

    companion object {
        fun defaults(tools: ToolPaths): ExecutorRegistry = ExecutorRegistry(
            mapOf(
                "jbang" to JBangExecutor(tools.jbang),
                "uv"    to UvExecutor(tools.uv),
            )
        )
    }
}

/**
 * Wait for [process] up to [timeoutMillis] while retaining a bounded set of
 * every descendant observed during its lifetime. On timeout the complete
 * observed tree receives TERM and then unconditional KILL. A launcher that
 * exits successfully while one of its descendants remains alive is also a
 * contract failure: it is reaped before this method returns.
 *
 * Timeouts <= 0 are treated as "no bound" and degrade to a plain
 * `waitFor()`; the spec parser doesn't emit those today, but the helper
 * stays well-defined for hand-built invocations.
 */
internal fun awaitWithTimeout(
    process: Process,
    timeoutMillis: Long,
    managedCommand: PosixProcessGroupController.ManagedCommand? = null,
    maxTrackedDescendants: Int = MAX_TRACKED_DESCENDANTS,
    visitDescendants: (ProcessHandle, (ProcessHandle) -> Unit) -> Unit =
        ::visitProcessDescendants,
): ExecutionOutcome {
    require(maxTrackedDescendants > 0) {
        "maximum tracked descendant count must be positive"
    }
    val root = process.toHandle()
    val descendants = LinkedHashMap<Long, ProcessHandle>()
    val startedNanos = System.nanoTime()
    val timeoutNanos = if (timeoutMillis <= 0L) null else
        TimeUnit.MILLISECONDS.toNanos(timeoutMillis).coerceAtMost(Long.MAX_VALUE / 2)

    fun observeDescendants(): Boolean {
        var overflow = false
        try {
            visitDescendants(root) { handle ->
                if (handle.pid() !in descendants) {
                    if (descendants.size >= maxTrackedDescendants) {
                        // Fail-before-retention: do not grow a collection from an
                        // adversarial fork tree. Kill overflow members as they are
                        // observed; the bounded retained prefix is cleaned below.
                        handle.destroyForcibly()
                        overflow = true
                    } else {
                        descendants[handle.pid()] = handle
                    }
                }
            }
        } catch (failure: RuntimeException) {
            if (
                managedCommand != null &&
                failure.message.orEmpty().contains(
                    "Cannot allocate memory",
                    ignoreCase = true,
                )
            ) {
                // macOS ProcessHandle.descendants() can transiently fail with
                // ENOMEM after repeated Metal workloads have filled swap.
                // The POSIX supervisor remains the ownership authority: it
                // reaps the complete child process group before reporting a
                // terminal status, so a failed advisory inventory must not
                // orphan the node or bypass graph finalizers.
                return false
            }
            throw failure
        }
        return overflow
    }

    fun liveTracked(): List<ProcessHandle> = descendants.values.filter { it.isAlive }

    fun terminateObservedTree(ownershipAlreadyUncertain: Boolean = false) {
        var ownershipUncertain = ownershipAlreadyUncertain || observeDescendants()
        // Descendants first prevents a launcher from intentionally leaving a
        // child behind when it handles TERM and exits promptly.
        liveTracked().asReversed().forEach { it.destroy() }
        if (root.isAlive) root.destroy()
        waitForHandlesToExit(root, descendants.values, PROCESS_TERM_GRACE_MILLIS)

        // KILL is unconditional after the TERM grace. A descendant may ignore
        // TERM or close inherited output, so launcher/pipe state is not proof
        // that the owned process tree is gone.
        ownershipUncertain = observeDescendants() || ownershipUncertain
        liveTracked().asReversed().forEach { it.destroyForcibly() }
        if (root.isAlive) root.destroyForcibly()
        waitForHandlesToExit(root, descendants.values, PROCESS_KILL_GRACE_MILLIS)
        val lingering = liveTracked()
        if (root.isAlive || lingering.isNotEmpty()) {
            throw ProcessOwnershipUncertainException(
                "node process tree still has live members after forced cleanup: " +
                        lingering.take(8).joinToString(",") { it.pid().toString() }
            )
        }
        if (ownershipUncertain) {
            throw ProcessOwnershipUncertainException(
                "node process tree exceeded the bounded descendant limit of " +
                        maxTrackedDescendants +
                        "; cleanup of the unretained suffix cannot be proven"
            )
        }
    }

    try {
        while (process.isAlive) {
            if (observeDescendants()) {
                terminateObservedTree(ownershipAlreadyUncertain = true)
            }
            val elapsedNanos = System.nanoTime() - startedNanos
            if (timeoutNanos != null && elapsedNanos >= timeoutNanos) {
                terminateObservedTree()
                if (managedCommand != null) {
                    requireSupervisorTerminalStatus(process, managedCommand)
                }
                return ExecutionOutcome.TimedOut
            }
            val remainingMillis = if (timeoutNanos == null) {
                PROCESS_POLL_MILLIS
            } else {
                TimeUnit.NANOSECONDS.toMillis(timeoutNanos - elapsedNanos)
                    .coerceAtLeast(1L)
                    .coerceAtMost(PROCESS_POLL_MILLIS)
            }
            process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)
        }
        if (observeDescendants()) {
            terminateObservedTree(ownershipAlreadyUncertain = true)
        }
        val leaked = liveTracked()
        if (leaked.isNotEmpty()) {
            terminateObservedTree()
            return ExecutionOutcome.ProcessContractViolation(
                exitCode = process.exitValue(),
                reason = "node launcher exited with live descendants: " +
                        leaked.take(8).joinToString(",") { it.pid().toString() },
            )
        }
        val exitCode = process.exitValue()
        if (managedCommand == null) return ExecutionOutcome.Completed(exitCode)
        val terminalStatus = requireSupervisorTerminalStatus(process, managedCommand)
        return when (terminalStatus) {
            is PosixProcessGroupController.TerminalStatus.ChildExit -> {
                ExecutionOutcome.Completed(terminalStatus.exitCode)
            }
            is PosixProcessGroupController.TerminalStatus.SupervisorSignal -> {
                ExecutionOutcome.Completed(terminalStatus.exitCode)
            }
            PosixProcessGroupController.TerminalStatus.ProcessGroupCleanupFailed ->
                error("cleanup failure must be rejected while reading supervisor status")
            PosixProcessGroupController.TerminalStatus.OrphanedGroupReaped -> {
                ExecutionOutcome.ProcessContractViolation(
                    exitCode = exitCode,
                    reason = "node process-group leader exited with surviving members; " +
                            "the supervisor reaped the group",
                )
            }
        }
    } catch (interrupted: InterruptedException) {
        try {
            terminateObservedTree()
            if (managedCommand != null) {
                requireSupervisorTerminalStatus(process, managedCommand)
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== interrupted) cleanupFailure.addSuppressed(interrupted)
            Thread.currentThread().interrupt()
            throw cleanupFailure
        }
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (failure: Throwable) {
        try {
            terminateAfterExecutorFailure(process, managedCommand)
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) cleanupFailure.addSuppressed(failure)
            throw cleanupFailure
        }
        throw failure
    } finally {
        managedCommand?.discardStatus()
    }
}

private fun visitProcessDescendants(
    root: ProcessHandle,
    visit: (ProcessHandle) -> Unit,
) {
    root.descendants().use { stream ->
        val iterator = stream.iterator()
        while (iterator.hasNext()) visit(iterator.next())
    }
}

private fun terminateAfterExecutorFailure(
    process: Process,
    managedCommand: PosixProcessGroupController.ManagedCommand?,
) {
    if (process.isAlive) process.destroy()
    if (!process.waitFor(PROCESS_TERM_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        process.waitFor(PROCESS_KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    }
    if (process.isAlive) {
        throw ProcessOwnershipUncertainException(
            "node process-group supervisor remained alive after executor failure"
        )
    }
    if (managedCommand != null) {
        requireSupervisorTerminalStatus(process, managedCommand)
    }
}

private fun requireSupervisorTerminalStatus(
    process: Process,
    managedCommand: PosixProcessGroupController.ManagedCommand,
): PosixProcessGroupController.TerminalStatus {
    val terminalStatus = try {
        managedCommand.terminalStatus()
    } catch (failure: Exception) {
        throw ProcessOwnershipUncertainException(
            "node process-group supervisor terminal status could not be proven: " +
                    (failure.message ?: failure.javaClass.name)
        ).also { it.addSuppressed(failure) }
    }
    val expectedExitCode = when (terminalStatus) {
        is PosixProcessGroupController.TerminalStatus.ChildExit -> terminalStatus.exitCode
        is PosixProcessGroupController.TerminalStatus.SupervisorSignal -> terminalStatus.exitCode
        PosixProcessGroupController.TerminalStatus.OrphanedGroupReaped ->
            PosixProcessGroupController.ORPHANED_GROUP_REAPED_EXIT_CODE
        PosixProcessGroupController.TerminalStatus.ProcessGroupCleanupFailed ->
            PosixProcessGroupController.PROCESS_GROUP_CLEANUP_FAILED_EXIT_CODE
    }
    requireSupervisorExitMatches(process.exitValue(), expectedExitCode)
    if (terminalStatus ==
        PosixProcessGroupController.TerminalStatus.ProcessGroupCleanupFailed
    ) {
        throw ProcessOwnershipUncertainException(
            "node process-group supervisor could not prove complete cleanup"
        )
    }
    return terminalStatus
}

private fun requireSupervisorExitMatches(actual: Int, reported: Int) {
    if (actual != reported) {
        throw ProcessOwnershipUncertainException(
            "node process-group supervisor exit mismatch: process=$actual status=$reported"
        )
    }
}

private fun waitForHandlesToExit(
    root: ProcessHandle,
    descendants: Collection<ProcessHandle>,
    timeoutMillis: Long,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (root.isAlive || descendants.any { it.isAlive }) {
        if (System.nanoTime() >= deadline) return
        Thread.sleep(PROCESS_CLEANUP_POLL_MILLIS)
    }
}

private const val MAX_TRACKED_DESCENDANTS = 4_096
private const val PROCESS_POLL_MILLIS = 100L
private const val PROCESS_CLEANUP_POLL_MILLIS = 10L
private const val PROCESS_TERM_GRACE_MILLIS = 1_000L
private const val PROCESS_KILL_GRACE_MILLIS = 2_000L

/**
 * Standard CLI args every executor appends. Keeping these in one place
 * means node scripts share a single contract regardless of runtime.
 *
 * The {@code --result-out} arg moves envelope authorship out of the
 * SDK: nodes write their NodeResult JSON to that path, and the executor
 * post-processes it into the canonical {@code envelope/<nodeId>.json}.
 * The SDK no longer owns the envelope filename, the timing stamps, or
 * the exit-code-from-status policy — that's centralized in
 * {@code PlanExecutor}, single Kotlin implementation, no per-runtime
 * duplication.
 */
internal fun standardArgs(invocation: NodeInvocation): List<String> {
    val args = mutableListOf<String>()
    args += "--nodeId=${invocation.spec.id}"
    args += "--runId=${invocation.runId}"
    args += "--reportDir=${invocation.reportDir.asFile.absolutePath}"
    args += "--result-out=${invocation.resultOut.absolutePath}"
    if (invocation.contextArg != null) {
        args += "--context=${invocation.contextArg}"
    }
    return args
}
