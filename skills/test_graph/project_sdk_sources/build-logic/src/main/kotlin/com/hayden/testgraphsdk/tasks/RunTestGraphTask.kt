package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.GraphAssembler
import com.hayden.testgraphsdk.TestGraphSpec
import com.hayden.testgraphsdk.Toolchain
import com.hayden.testgraphsdk.exec.ExecutorRegistry
import com.hayden.testgraphsdk.exec.PlanExecutor
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal object RunIds {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    fun next(): String = fmt.format(Instant.now())
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

    init {
        group = "validation"
        description = "Execute this test graph."
    }

    @TaskAction
    fun run() {
        val projDir = projectDirectory.get().asFile
        val tools = Toolchain.resolve(project)
        val plan = GraphAssembler.plan(graphSpec, sourcesDirsProvider(), projDir, tools)
        val runId = RunIds.next()
        val reportDir = reportRoot.dir(runId).get()
        reportDir.asFile.mkdirs()

        logger.lifecycle("testGraph '${graphSpec.name}' run=$runId steps=${plan.size}")
        for ((i, n) in plan.withIndex()) {
            logger.lifecycle(
                "  plan[${i + 1}/${plan.size}] ${n.id}  [${n.kind.name.lowercase()}, ${n.runtime.name}]  ${n.runtime.entryFile}"
            )
        }

        PlanExecutor(
            ExecutorRegistry.defaults(tools),
            projectDirectory.get(), reportDir, runId, logger,
        ).run(plan)

        logger.lifecycle("testGraph '${graphSpec.name}' done. reports: ${reportDir.asFile.absolutePath}")
    }
}
