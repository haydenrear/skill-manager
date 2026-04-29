package com.hayden.testgraphsdk

import com.hayden.testgraphsdk.tasks.ValidationGraphDotTask
import com.hayden.testgraphsdk.tasks.ValidationListGraphsTask
import com.hayden.testgraphsdk.tasks.ValidationPlanGraphTask
import com.hayden.testgraphsdk.tasks.ValidationReportTask
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Wires the ValidationGraph DSL and registers the utility tasks.
 *
 * Per-graph run tasks are registered by the extension itself as
 * `testGraph("X") { ... }` calls execute during configuration — one
 * Gradle task per test graph, named after the graph. The aggregate
 * `validationRunAll` task fans out to every registered graph and chains
 * them serially (each graph waits for the previous one's
 * `mustRunAfter`) so testbeds — postgres, registries, gateways — don't
 * compete for shared local resources when Gradle's worker pool is
 * larger than 1.
 *
 * `clean` comes from the `base` plugin we apply here; it removes
 * `build/`, which is also where validation reports default to so a
 * fresh run starts clean.
 */
class ValidationGraphPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // The `base` plugin contributes the `clean` lifecycle task
        // (delete build/) and gives us the `build` group, both of which
        // we want without forcing the caller to add `plugins { base }`
        // to their build.gradle.kts.
        project.pluginManager.apply("base")

        val ext = project.extensions.create(
            "validationGraph",
            ValidationGraphExtension::class.java,
            project,
        )

        val reports = project.layout.buildDirectory.dir("validation-reports")
        val projDir = project.layout.projectDirectory

        project.tasks.register("validationListGraphs", ValidationListGraphsTask::class.java)
            .configure {
                graphsProvider = { ext.graphs.toMap() }
                group = "validation"
            }

        project.tasks.register("validationPlanGraph", ValidationPlanGraphTask::class.java)
            .configure {
                graphsProvider = { ext.graphs.toMap() }
                sourcesDirsProvider = { ext.sourcesDirs.toList() }
                projectDirectory.set(projDir)
                group = "validation"
            }

        project.tasks.register("validationGraphDot", ValidationGraphDotTask::class.java)
            .configure {
                graphsProvider = { ext.graphs.toMap() }
                sourcesDirsProvider = { ext.sourcesDirs.toList() }
                projectDirectory.set(projDir)
                group = "validation"
            }

        project.tasks.register("validationReport", ValidationReportTask::class.java)
            .configure {
                reportRoot.set(reports)
                group = "validation"
            }

        // Aggregate task: run every test graph the build script declared,
        // serialized in declaration order. Configure-time `dependsOn` is
        // wired from `afterEvaluate` so any `testGraph(...)` call lower
        // in the build script is included.
        val runAll = project.tasks.register("validationRunAll", DefaultTask::class.java) {
            group = "validation"
            description = "Run every registered test graph sequentially."
            // No `finalizedBy("validationReport")` — each RunTestGraphTask
            // emits its own summary.json + report.md inline at the end of
            // plan execution. A finalizer at this layer plus the same
            // finalizer on every graph task historically deduped to a
            // single execution against one run dir, leaving the others
            // without a report under multi-graph invocations.
        }
        project.afterEvaluate {
            val graphTasks = ext.graphs.keys.mapNotNull { project.tasks.findByName(it) }
            if (graphTasks.isEmpty()) return@afterEvaluate
            runAll.configure { dependsOn(graphTasks) }
            // Force serial execution: graph N+1 mustRunAfter graph N. This
            // doesn't make Gradle run them inside `validationRunAll`'s
            // body — it just constrains the scheduler so when the user
            // invokes the aggregate (or just `./gradlew smoke sponsored`)
            // postgres doesn't fight a second postgres.
            for (i in 1 until graphTasks.size) {
                graphTasks[i].mustRunAfter(graphTasks[i - 1])
            }
        }
    }
}
