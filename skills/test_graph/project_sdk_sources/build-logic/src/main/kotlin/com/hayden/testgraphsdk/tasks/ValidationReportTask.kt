package com.hayden.testgraphsdk.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal const val MAX_REPORT_RUN_DIRECTORIES = 10_000
private val REPORT_RUN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

internal fun selectReportRunDirectory(root: File, runId: String): File {
    require(REPORT_RUN_ID.matches(runId)) {
        "validation report run id must match ${REPORT_RUN_ID.pattern}"
    }
    val rootPath = root.toPath().toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(rootPath)) {
        "validation report root must not be a symlink: ${root.absolutePath}"
    }
    require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
        "validation report root is not a real directory: ${root.absolutePath}"
    }
    val selected = rootPath.resolve(runId).normalize()
    require(selected.parent == rootPath && !Files.isSymbolicLink(selected)) {
        "validation report run directory must be a direct, non-symlink child: $selected"
    }
    require(Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) {
        "validation report run directory does not exist: $selected"
    }
    val scope = selected.resolve("execution-scope.json")
    val envelope = selected.resolve("envelope")
    require(!Files.isSymbolicLink(scope) && !Files.isSymbolicLink(envelope)) {
        "validation report evidence paths must not be symlinks: $selected"
    }
    require(
        Files.exists(scope, LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(envelope, LinkOption.NOFOLLOW_LINKS)
    ) {
        "validation report run directory contains no attempt evidence: $selected"
    }
    return selected.toFile()
}

internal fun scanReportRunDirectories(
    root: File,
    maxRuns: Int = MAX_REPORT_RUN_DIRECTORIES,
): List<File> {
    require(maxRuns in 0..MAX_REPORT_RUN_DIRECTORIES) {
        "report run-directory limit must be between 0 and $MAX_REPORT_RUN_DIRECTORIES"
    }
    val rootPath = root.toPath()
    require(!Files.isSymbolicLink(rootPath)) {
        "validation report root must not be a symlink: ${root.absolutePath}"
    }
    if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) return emptyList()

    val retained = ArrayList<File>(maxRuns.coerceAtMost(1_024) + 1)
    Files.newDirectoryStream(rootPath).use { entries ->
        for (entry in entries) {
            if (Files.isSymbolicLink(entry)) {
                if (Files.isDirectory(entry)) {
                    throw IllegalArgumentException(
                        "validation report run directory must not be a symlink: $entry"
                    )
                }
                continue
            }
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue

            val scope = entry.resolve("execution-scope.json")
            val envelope = entry.resolve("envelope")
            if (Files.isSymbolicLink(scope)) {
                throw IllegalArgumentException("execution scope must not be a symlink: $scope")
            }
            if (Files.isSymbolicLink(envelope)) {
                throw IllegalArgumentException("envelope directory must not be a symlink: $envelope")
            }
            val isAttempt = Files.exists(scope, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isDirectory(envelope, LinkOption.NOFOLLOW_LINKS)
            if (!isAttempt) continue

            retained += entry.toFile()
            if (retained.size > maxRuns) {
                throw IllegalStateException(
                    "validation report root exceeds the bounded limit of $maxRuns run directories; " +
                            "archive or prune old runs before regenerating reports"
                )
            }
        }
    }
    retained.sortBy { it.name }
    return retained
}

/**
 * Manual "regenerate every existing run's summary.json + report.md".
 *
 * <p>The per-graph rollup is no longer wired through this task —
 * {@link RunTestGraphTask} writes its own summary + report inline at
 * the end of plan execution, which avoids the Gradle finalizer
 * deduplication that used to leave some run dirs without a report when
 * fanning out across multiple graphs in one invocation.
 *
 * <p>This task remains useful when an envelope under
 * {@code build/validation-reports/<runId>/envelope/} has been edited
 * by hand or imported from another machine and the operator wants a
 * refreshed markdown report without rerunning the graph. It walks every
 * run dir that carries an execution scope or envelope/ subdir and rewrites
 * the artifacts.
 */
abstract class ValidationReportTask : DefaultTask() {
    @get:Internal abstract val reportRoot: DirectoryProperty
    private var reportRunId: String? = null

    @Option(
        option = "run-id",
        description = "Re-render exactly one direct child of validation-reports.",
    )
    fun setReportRunId(value: String) {
        reportRunId = value
    }

    init {
        group = "validation"
        description = "Re-render summary.json + report.md for every existing run dir under validation-reports/."
    }

    @TaskAction
    fun report() {
        val root = reportRoot.get().asFile
        if (!root.isDirectory) {
            logger.lifecycle("no reports dir at ${root.absolutePath}")
            return
        }
        val runDirs = reportRunId?.let {
            listOf(selectReportRunDirectory(root, it))
        } ?: scanReportRunDirectories(root)
        if (runDirs.isEmpty()) {
            logger.lifecycle("no runs found under ${root.absolutePath}")
            return
        }
        for (runDir in runDirs) {
            if (RunReportWriter.writeRunReport(runDir).written) {
                logger.lifecycle(
                    "rewrote ${File(runDir, "summary.json").absolutePath} + " +
                            "${File(runDir, "report.md").absolutePath}"
                )
            }
        }
    }
}
