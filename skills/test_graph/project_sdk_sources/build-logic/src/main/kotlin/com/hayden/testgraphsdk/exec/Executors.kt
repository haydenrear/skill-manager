package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ToolPaths
import com.hayden.testgraphsdk.ValidationNodeSpec
import org.gradle.api.file.Directory

/**
 * Everything an executor needs to dispatch one node invocation.
 *
 * [contextArg] is the already-encoded value for the single {@code --context}
 * CLI flag — either an inline JSON blob or an {@code @<path>} reference.
 * Executors stay runtime-focused; serialization lives in PlanExecutor so
 * one threshold policy applies across runtimes.
 */
data class NodeInvocation(
    val spec: ValidationNodeSpec,
    val projectDir: Directory,
    val reportDir: Directory,
    val runId: String,
    val contextArg: String? = null,
)

/**
 * Runtime adapter — knows how to spawn one node invocation.
 *
 * Implementations own the command line for their runtime. Task code
 * stays runtime-agnostic.
 */
interface ValidationExecutor {
    val runtimeName: String
    fun execute(invocation: NodeInvocation): Int
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
 * Standard CLI args every executor appends. Keeping these in one place
 * means node scripts share a single contract regardless of runtime.
 */
internal fun standardArgs(invocation: NodeInvocation): List<String> {
    val args = mutableListOf<String>()
    args += "--nodeId=${invocation.spec.id}"
    args += "--runId=${invocation.runId}"
    args += "--reportDir=${invocation.reportDir.asFile.absolutePath}"
    if (invocation.contextArg != null) {
        args += "--context=${invocation.contextArg}"
    }
    return args
}
