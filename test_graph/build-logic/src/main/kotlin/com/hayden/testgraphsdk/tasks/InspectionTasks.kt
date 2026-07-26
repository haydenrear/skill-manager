package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.GraphAssembler
import com.hayden.testgraphsdk.TestGraphSpec
import com.hayden.testgraphsdk.Toolchain
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File

/**
 * List all registered test graphs and their explicit node files.
 * Does NOT describe the scripts — fast, purely DSL-state readout.
 */
abstract class ValidationListGraphsTask : DefaultTask() {

    @get:Internal lateinit var graphsProvider: () -> Map<String, TestGraphSpec>

    init {
        group = "validation"
        description = "List registered test graphs and their explicitly-declared nodes."
    }

    @TaskAction
    fun list() {
        val graphs = graphsProvider()
        if (graphs.isEmpty()) {
            println("no test graphs registered")
            println("(add one in build.gradle.kts:  testGraph(\"name\") { node(...) })")
            return
        }
        for ((name, spec) in graphs) {
            println("graph: $name")
            if (spec.explicitNodes.isEmpty()) {
                println("  (no explicit nodes)")
            } else {
                for (file in spec.explicitNodes.keys) {
                    println("  - ${file.path}")
                }
            }
        }
    }
}

/**
 * Describe one test graph: the topo-ordered plan of everything that
 * would run (explicit + transitive), with kind, runtime, and entry path.
 *
 * Eagerly describes every script needed for the graph, so it's a real
 * dry run of the assembly logic — same errors, same order, no execution.
 */
abstract class ValidationPlanGraphTask : DefaultTask() {

    @get:Internal lateinit var graphsProvider: () -> Map<String, TestGraphSpec>
    @get:Internal lateinit var sourcesDirsProvider: () -> List<File>
    @get:Internal abstract val projectDirectory: DirectoryProperty

    @get:Input
    @get:Option(option = "name", description = "Test graph name to plan")
    abstract val target: Property<String>

    init {
        group = "validation"
        description = "Print the topo-ordered plan for a test graph, including entry paths."
    }

    @TaskAction
    fun plan() {
        val graphs = graphsProvider()
        val spec = graphs[target.get()] ?: error(
            "no test graph '${target.get()}' — available: ${graphs.keys.ifEmpty { setOf("(none)") }}"
        )
        val tools = Toolchain.resolve(project)
        val plan = GraphAssembler.plan(spec, sourcesDirsProvider(), projectDirectory.get().asFile, tools)
        val idW = (plan.maxOfOrNull { it.id.length } ?: 4).coerceAtLeast(4)

        // 1. Execution plan (topo-ordered).
        println("plan for test graph '${spec.name}' (${plan.size} step${if (plan.size == 1) "" else "s"}):")
        val rowFmt = "  %-3s %-${idW}s  %-10s  %-6s  %s"
        println(String.format(rowFmt, "#", "id", "kind", "rt", "entry"))
        plan.forEachIndexed { i, n ->
            println(
                String.format(
                    rowFmt,
                    (i + 1).toString() + ".",
                    n.id,
                    n.kind.name.lowercase(),
                    n.runtime.name,
                    n.runtime.entryFile,
                )
            )
        }

        // 2. Dependency adjacency — makes fan-in/fan-out visible at a glance.
        println()
        println("dependencies:")
        val depFmt = "  %-${idW}s  %s"
        for (n in plan) {
            val upstream = if (n.dependsOn.isEmpty()) "(root)"
                           else "<- ${n.dependsOn.joinToString(", ")}"
            println(String.format(depFmt, n.id, upstream))
        }
    }
}

/**
 * Emit graphviz DOT for a test graph. Only DOT on stdout, nothing else,
 * so `discover.py` can pipe directly into `dot -Tpng` or capture to a file.
 */
abstract class ValidationGraphDotTask : DefaultTask() {

    @get:Internal lateinit var graphsProvider: () -> Map<String, TestGraphSpec>
    @get:Internal lateinit var sourcesDirsProvider: () -> List<File>
    @get:Internal abstract val projectDirectory: DirectoryProperty

    @get:Input
    @get:Option(option = "name", description = "Test graph name")
    abstract val target: Property<String>

    init {
        group = "validation"
        description = "Emit graphviz DOT for a test graph (stdout only)."
    }

    @TaskAction
    fun dot() {
        val graphs = graphsProvider()
        val spec = graphs[target.get()] ?: error(
            "no test graph '${target.get()}' — available: ${graphs.keys.ifEmpty { setOf("(none)") }}"
        )
        val tools = Toolchain.resolve(project)
        val plan = GraphAssembler.plan(spec, sourcesDirsProvider(), projectDirectory.get().asFile, tools)

        println("digraph \"${spec.name}\" {")
        println("  rankdir=LR;")
        println("  node [shape=box,fontname=\"Courier\"];")
        for (n in plan) {
            val label = "${n.id}\\n(${n.kind.name.lowercase()}, ${n.runtime.name})"
            println("  \"${n.id}\" [label=\"$label\"];")
        }
        for (n in plan) {
            for (dep in n.dependsOn) {
                println("  \"$dep\" -> \"${n.id}\";")
            }
        }
        println("}")
    }
}
