package com.hayden.testgraphsdk

import com.hayden.testgraphsdk.tasks.ValidationGraphDotTask
import com.hayden.testgraphsdk.tasks.ValidationListGraphsTask
import com.hayden.testgraphsdk.tasks.ValidationPlanGraphTask
import com.hayden.testgraphsdk.tasks.ValidationReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Wires the ValidationGraph DSL and registers the utility tasks.
 *
 * Per-graph run tasks are registered by the extension itself as
 * `testGraph("X") { ... }` calls execute during configuration — one
 * Gradle task per test graph, named after the graph.
 *
 * Reports default to `build/validation-reports/` so `./gradlew clean`
 * wipes them.
 */
class ValidationGraphPlugin : Plugin<Project> {

    override fun apply(project: Project) {
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
            }

        project.tasks.register("validationPlanGraph", ValidationPlanGraphTask::class.java)
            .configure {
                graphsProvider = { ext.graphs.toMap() }
                sourcesDirsProvider = { ext.sourcesDirs.toList() }
                projectDirectory.set(projDir)
            }

        project.tasks.register("validationGraphDot", ValidationGraphDotTask::class.java)
            .configure {
                graphsProvider = { ext.graphs.toMap() }
                sourcesDirsProvider = { ext.sourcesDirs.toList() }
                projectDirectory.set(projDir)
            }

        project.tasks.register("validationReport", ValidationReportTask::class.java)
            .configure { reportRoot.set(reports) }
    }
}
