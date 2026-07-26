package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.GraphAssembler
import com.hayden.testgraphsdk.TestGraphSpec
import com.hayden.testgraphsdk.Toolchain
import com.hayden.testgraphsdk.isFinalizerNode
import com.hayden.testgraphsdk.exec.ExecutorRegistry
import com.hayden.testgraphsdk.exec.GraphObservability
import com.hayden.testgraphsdk.exec.PlanExecutor
import com.hayden.testgraphsdk.exec.ProcessOwnershipUncertainException
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object RunIds {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    private const val MAX_COLLISION_SUFFIX = 9_999

    fun allocate(reportRoot: File): File {
        try {
            Files.createDirectories(reportRoot.toPath())
        } catch (e: Exception) {
            throw IllegalStateException(
                "could not create test graph report root: ${reportRoot.absolutePath}",
                e,
            )
        }
        if (!Files.isDirectory(reportRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException(
                "test graph report root is not a directory: ${reportRoot.absolutePath}"
            )
        }
        val base = fmt.format(Instant.now())
        for (suffix in 0..MAX_COLLISION_SUFFIX) {
            val runId = if (suffix == 0) base else "$base-$suffix"
            val candidate = reportRoot.resolve(runId)
            try {
                return Files.createDirectory(candidate.toPath()).toFile()
            } catch (_: FileAlreadyExistsException) {
                // A full run or replay already owns this id. Never append new
                // evidence to it; allocate another immutable attempt directory.
            }
        }
        throw IllegalStateException(
            "could not allocate a unique test graph run under ${reportRoot.absolutePath}"
        )
    }
}

internal data class RunTerminalOutcome(
    val failure: Throwable?,
) {
    val observabilityStatus: String
        get() = if (failure == null) "passed" else "failed"
}

internal fun resolveRunTerminalOutcome(
    executionFailure: Throwable?,
    reportOutcome: RunReportWriter.Outcome?,
    reportWriteFailure: Throwable?,
): RunTerminalOutcome {
    if (executionFailure != null) {
        if (reportWriteFailure != null && reportWriteFailure !== executionFailure) {
            executionFailure.addSuppressed(reportWriteFailure)
        }
        return RunTerminalOutcome(executionFailure)
    }
    if (reportWriteFailure != null) return RunTerminalOutcome(reportWriteFailure)
    if (reportOutcome?.passed == true) return RunTerminalOutcome(null)

    val reportStatus = reportOutcome?.status ?: "unwritten"
    return RunTerminalOutcome(
        IllegalStateException(
            "test graph report did not pass: status=$reportStatus " +
                    "complete=${reportOutcome?.complete ?: false}"
        )
    )
}

internal fun requireExecutablePlanSize(planSize: Int) {
    require(planSize in 1..RunReportWriter.MAX_ENVELOPE_FILES) {
        "test graph plan must contain 1..${RunReportWriter.MAX_ENVELOPE_FILES} nodes; found " +
                planSize + ". The absolute limit is " +
                RunReportWriter.MAX_ENVELOPE_FILES
    }
}

internal fun requireReplaySourceInReportRoot(reportRoot: File, requestedSource: File): File {
    val rootPath = reportRoot.toPath().toAbsolutePath().normalize()
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        "test graph report root must be a real directory, not a symlink: $rootPath"
    }

    val sourcePath = requestedSource.toPath().let {
        if (it.isAbsolute) it else it.toAbsolutePath()
    }.normalize()
    require(sourcePath.parent == rootPath) {
        "--resume-from-build must be a direct child of the configured report root: $rootPath"
    }
    require(Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
        "--resume-from-build must point at a real run directory, not a symlink: $sourcePath"
    }

    // Lexical membership rejects traversal before resolution. Canonical parent
    // equality then proves that shared filesystem aliases did not redirect the
    // source outside the configured report root.
    val canonicalRoot = rootPath.toFile().canonicalFile
    val canonicalSource = sourcePath.toFile().canonicalFile
    require(canonicalSource.parentFile == canonicalRoot) {
        "--resume-from-build resolves outside the configured report root: $rootPath"
    }
    return canonicalSource
}

/**
 * Executes one test graph. Registered by the extension as a task named
 * after the graph (so `testGraph("smoke")` ⇒ `./gradlew smoke`).
 *
 * Non-Property internal fields are set at configuration time by the
 * extension. This avoids reshaping the task's Property<T> surface for
 * every new field while we iterate on the DSL.
 */
abstract class RunTestGraphTask : DefaultTask() {

    @get:Internal lateinit var graphSpec: TestGraphSpec

    /** Late-bound so `sourcesDir(...)` calls after `testGraph(...)` still count. */
    @get:Internal lateinit var sourcesDirsProvider: () -> List<File>

    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:Internal abstract val reportRoot: DirectoryProperty
    @get:Internal var resumeFromBuildPath: String? = null
    @get:Internal var resumeFromNodeId: String? = null
    @get:Internal var runOnlyNodeId: String? = null

    init {
        group = "validation"
        description = "Execute this test graph."
    }

    @Option(
        option = "resume-from-build",
        description = "Direct non-symlink build/validation-reports/<runId> child to resume from.",
    )
    fun setResumeFromBuild(path: String) {
        resumeFromBuildPath = path
    }

    @Option(
        option = "resume-from-node",
        description = "Node id whose saved input context should seed resumed graph execution.",
    )
    fun setResumeFromNode(nodeId: String) {
        resumeFromNodeId = nodeId
    }

    @Option(
        option = "run-only-node",
        description = "Node id to run by itself from saved build input context.",
    )
    fun setRunOnlyNode(nodeId: String) {
        runOnlyNodeId = nodeId
    }

    @TaskAction
    fun run() {
        val projDir = projectDirectory.get().asFile
        val tools = Toolchain.resolve(project)
        val plan = GraphAssembler.plan(graphSpec, sourcesDirsProvider(), projDir, tools)
        requireExecutablePlanSize(plan.size)
        val resume = resumeRequest()
        val selection = PlanExecutor.selectExecutionPlan(plan, resume)
        val expectedNodeIds = selection.executionPlan.map { it.id }
        val replaySourceSnapshot = resume?.let {
            RunReportWriter.requireReplaySource(
                sourceDir = it.buildDir,
                graphName = graphSpec.name,
                selectedNodeId = it.nodeId,
            )
        }
        val replayMetadata = resume?.let {
            val snapshot = requireNotNull(replaySourceSnapshot)
            RunReportWriter.ReplayMetadata(
                mode = when (it.mode) {
                    PlanExecutor.BuildReplayMode.RESUME_GRAPH ->
                        RunReportWriter.ReplayMode.RESUME_FROM_NODE
                    PlanExecutor.BuildReplayMode.RUN_ONLY_NODE ->
                        RunReportWriter.ReplayMode.RUN_ONLY_NODE
                },
                selectedNodeId = it.nodeId,
                sourceBuild = snapshot.sourceBuild,
                sourceClosureSha256 = snapshot.closureSha256,
                sourceContextSha256 = snapshot.selectedContextSha256,
            )
        }
        val reportDirFile = RunIds.allocate(reportRoot.get().asFile)
        val runId = reportDirFile.name
        val reportDir = project.layout.dir(project.provider { reportDirFile }).get()
        RunReportWriter.persistExecutionScope(
            runDir = reportDirFile,
            graphName = graphSpec.name,
            expectedNodeIds = expectedNodeIds,
            replay = replayMetadata,
        )
        val observability = GraphObservability.open(
            reportDir.asFile,
            graphSpec.name,
            replaySourceSnapshot = replaySourceSnapshot,
        )

        logger.lifecycle(
            "testGraph '${graphSpec.name}' run=$runId steps=${selection.executionPlan.size} " +
                    "fullPlanSteps=${plan.size} traceId=${observability.traceId}"
        )
        if (resume != null) {
            logger.lifecycle(
                "  replay=${resume.mode.name.lowercase()} selected=${resume.nodeId} " +
                        "source=${resume.buildDir.absolutePath}"
            )
        }
        for ((i, n) in selection.executionPlan.withIndex()) {
            logger.lifecycle(
                "  plan[${i + 1}/${selection.executionPlan.size}] ${n.id}  " +
                        "[${n.kind.name.lowercase()}, ${n.runtime.name}]  ${n.runtime.entryFile}"
            )
        }

        var executionFailure: Throwable? = null
        try {
            PlanExecutor(
                ExecutorRegistry.defaults(tools),
                projectDirectory.get(), reportDir, runId, logger, graphSpec.name, observability,
            ).run(plan, resume, replaySourceSnapshot)
        } catch (t: Throwable) {
            executionFailure = t
        }

        // The immutable closure artifact is the durable transition from an
        // allocated/active attempt to a replay-eligible closed attempt. It is
        // published only after node execution has ended, and before derived
        // views are generated. Failed attempts are still closed attempts;
        // their envelope/context evidence remains available for a safe rerun.
        if (executionFailure !is ProcessOwnershipUncertainException) {
            try {
                RunReportWriter.persistAttemptClosure(
                    runDir = reportDir.asFile,
                    graphName = graphSpec.name,
                    expectedNodeIds = expectedNodeIds,
                    finalizerNodeIds = selection.executionPlan
                        .filter { it.isFinalizerNode() }
                        .mapTo(linkedSetOf()) { it.id },
                    traceId = observability.traceId,
                    replay = replayMetadata,
                )
            } catch (closureFailure: Throwable) {
                if (executionFailure == null) {
                    executionFailure = closureFailure
                } else if (closureFailure !== executionFailure) {
                    executionFailure.addSuppressed(closureFailure)
                }
            }
        } else {
            logger.error(
                "attempt closure withheld because process ownership could not be proven"
            )
        }

        // Roll this graph's per-node envelopes into summary.json + report.md
        // right here, while we still own this run dir. Doing it inline avoids
        // the Gradle finalizer dedup that left some run dirs without a
        // report when one `validationReport` finalizer was shared across
        // multiple graph tasks (smoke, sponsored, ...) inside a single
        // `validationRunAll` invocation.
        var reportOutcome: RunReportWriter.Outcome? = null
        var reportWriteFailure: Throwable? = null
        try {
            reportOutcome = RunReportWriter.writeRunReport(
                    runDir = reportDir.asFile,
                    graphName = graphSpec.name,
                    expectedNodeIds = expectedNodeIds,
                    executionFailure = executionFailure,
                    expectedTraceId = observability.traceId,
                    replay = replayMetadata,
                    replaySourceSnapshot = replaySourceSnapshot,
                )
            if (reportOutcome.written) {
                logger.lifecycle(
                    "wrote ${File(reportDir.asFile, "summary.json").absolutePath} + " +
                    "${File(reportDir.asFile, "report.md").absolutePath}"
                )
            }
        } catch (t: Throwable) {
            reportWriteFailure = t
        }
        val terminalOutcome = resolveRunTerminalOutcome(
            executionFailure = executionFailure,
            reportOutcome = reportOutcome,
            reportWriteFailure = reportWriteFailure,
        )
        observability.finish(terminalOutcome.observabilityStatus)
        terminalOutcome.failure?.let { throw it }

        logger.lifecycle(
            "testGraph '${graphSpec.name}' done. traceId=${observability.traceId} " +
                    "reports: ${reportDir.asFile.absolutePath}"
        )
    }

    private fun resumeRequest(): PlanExecutor.ResumeFromBuild? {
        val buildPath = resumeFromBuildPath
        val resumeNode = resumeFromNodeId
        val runOnlyNode = runOnlyNodeId
        val replayNodeCount = listOf(resumeNode, runOnlyNode).count { it != null }
        if (buildPath == null && replayNodeCount > 0) {
            throw IllegalArgumentException(
                "--resume-from-build is required with --resume-from-node or --run-only-node"
            )
        }
        if (buildPath != null && replayNodeCount != 1) {
            throw IllegalArgumentException(
                "--resume-from-build requires exactly one of --resume-from-node or --run-only-node"
            )
        }
        if (buildPath == null) return null

        val requestedBuildDir = File(buildPath).let {
            if (it.isAbsolute) it else File(project.projectDir, buildPath)
        }
        val buildDir = requireReplaySourceInReportRoot(
            reportRoot = reportRoot.get().asFile,
            requestedSource = requestedBuildDir,
        )
        val mode = if (runOnlyNode != null) {
            PlanExecutor.BuildReplayMode.RUN_ONLY_NODE
        } else {
            PlanExecutor.BuildReplayMode.RESUME_GRAPH
        }
        return PlanExecutor.ResumeFromBuild(
            buildDir = buildDir,
            nodeId = resumeNode ?: runOnlyNode!!,
            mode = mode,
        )
    }

}
