package com.hayden.testgraphsdk

import com.hayden.testgraphsdk.tasks.RunTestGraphTask
import org.gradle.api.Project
import java.io.File

/**
 * DSL entry point: `validationGraph { ... }` in a build script.
 *
 *   validationGraph {
 *       sourcesDir("sources")           // where to find transitive scripts
 *
 *       testGraph("smoke") {
 *           node("sources/user_seeded.py")
 *           node("sources/LoginSmoke.java")
 *               .dependsOn("user.seeded")
 *               .tags("regression")
 *       }
 *
 *       testGraph("quick") { ... }       // multiple graphs, each a Gradle task
 *   }
 *
 * Each `testGraph("X") { ... }` immediately registers a Gradle task named
 * `X` in the "validation" group. `./gradlew X` runs that graph.
 */
open class ValidationGraphExtension(internal val project: Project) {

    internal val sourcesDirs = mutableListOf<File>()
    internal val graphs = linkedMapOf<String, TestGraphSpec>()

    /**
     * Register a directory scanned for transitive-dep resolution.
     * Multiple directories allowed; earlier calls win on id collisions.
     */
    fun sourcesDir(path: String) {
        val d = project.file(path)
        require(d.isDirectory) { "sourcesDir not found or not a directory: ${d.path}" }
        sourcesDirs += d
    }

    /**
     * Define a test graph and register it as a Gradle task named `name`.
     *
     * The DSL body uses {@link TestGraphBuilder}:
     *
     *   testGraph("smoke") {
     *       node("sources/Foo.java").dependsOn("bar.baz").tags("x")
     *       node("sources/bar_baz.py")
     *   }
     */
    fun testGraph(name: String, block: TestGraphBuilder.() -> Unit) {
        if (graphs.containsKey(name)) error("test graph '$name' is already defined")
        val spec = TestGraphBuilder(project, name).apply(block).build()
        graphs[name] = spec

        val outerExt = this
        project.tasks.register(name, RunTestGraphTask::class.java).configure {
            graphSpec = spec
            sourcesDirsProvider = { outerExt.sourcesDirs.toList() }
            projectDirectory.set(project.layout.projectDirectory)
            reportRoot.set(project.layout.buildDirectory.dir("validation-reports"))
            // Always emit report.md + summary.json after a graph runs,
            // even when invoked directly as `./gradlew <graph>`. Without
            // this, only run.py / validationRunAll trigger the rollup —
            // surprising for a developer poking at one graph.
            finalizedBy("validationReport")
        }
    }
}
