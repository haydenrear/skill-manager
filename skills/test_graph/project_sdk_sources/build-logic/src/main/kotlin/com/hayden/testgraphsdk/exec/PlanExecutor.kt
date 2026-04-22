package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Runs a topo-sorted plan node-by-node, building a cumulative
 * {@code List<ContextItem>} as it goes.
 *
 * After each node completes, we read its envelope's {@code published}
 * block and append one ContextItem to the running list. Before the
 * next node runs we serialize the list and pass it as a single
 * {@code --context} arg (inline JSON if small enough, otherwise a
 * file reference at {@code @<path>}).
 *
 * The envelope remains the durable record; --context is the data
 * wire between nodes.
 */
class PlanExecutor(
    private val registry: ExecutorRegistry,
    private val projectDir: Directory,
    private val reportDir: Directory,
    private val runId: String,
    private val logger: Logger,
) {
    fun run(plan: List<ValidationNodeSpec>) {
        val cumulative = mutableListOf<ContextItem>()
        val reportRoot = reportDir.asFile

        for ((i, spec) in plan.withIndex()) {
            logger.lifecycle("  [${i + 1}/${plan.size}] ${spec.id} (${spec.runtime.name})")

            val contextArg = if (cumulative.isEmpty()) null
                             else encodeContextArg(cumulative, reportRoot, i)

            val invocation = NodeInvocation(
                spec = spec,
                projectDir = projectDir,
                reportDir = reportDir,
                runId = runId,
                contextArg = contextArg,
            )
            val code = registry.forNode(spec).execute(invocation)
            if (code != 0) throw RuntimeException("node ${spec.id} exited with code $code")

            cumulative += readContextItem(spec.id)
        }
    }

    private fun readContextItem(nodeId: String): ContextItem {
        val envelope = File(reportDir.asFile, "envelope/$nodeId.json")
        val data = if (envelope.isFile) ContextSerde.extractPublished(envelope.readText())
                   else emptyMap()
        return ContextItem(nodeId, data)
    }
}
