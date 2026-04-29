package com.hayden.testgraphsdk.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

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
 * run dir that carries an envelope/ subdir and rewrites the artifacts.
 */
abstract class ValidationReportTask : DefaultTask() {
    @get:Internal abstract val reportRoot: DirectoryProperty

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
        val runDirs = root.listFiles { f -> f.isDirectory && File(f, "envelope").isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (runDirs.isEmpty()) {
            logger.lifecycle("no runs found under ${root.absolutePath}")
            return
        }
        for (runDir in runDirs) {
            if (RunReportWriter.writeRunReport(runDir)) {
                logger.lifecycle(
                    "rewrote ${File(runDir, "summary.json").absolutePath} + " +
                            "${File(runDir, "report.md").absolutePath}"
                )
            }
        }
    }
}
