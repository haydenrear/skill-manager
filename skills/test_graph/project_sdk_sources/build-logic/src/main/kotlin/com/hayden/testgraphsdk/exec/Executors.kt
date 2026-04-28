package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ToolPaths
import com.hayden.testgraphsdk.ValidationNodeSpec
import org.gradle.api.file.Directory
import java.io.File

/**
 * Everything an executor needs to dispatch one node invocation.
 *
 * [contextArg] is the already-encoded value for the single {@code --context}
 * CLI flag — either an inline JSON blob or an {@code @<path>} reference.
 * Executors stay runtime-focused; serialization lives in PlanExecutor so
 * one threshold policy applies across runtimes.
 *
 * [resultOut] is where the SDK writes its NodeResult JSON. It's
 * intentionally distinct from the canonical envelope path — PlanExecutor
 * post-processes this file (stamps executor-measured fields, validates
 * shape, synthesizes a fallback for missing/malformed cases) before
 * writing the final {@code envelope/<nodeId>.json}. So even when a node
 * crashes mid-write, every planned node ends up with exactly one
 * well-formed envelope.
 *
 * [stdoutLog] is the file the executor redirects the spawned
 * node-process's merged stdout+stderr into. It's the "node crashed
 * before populating result-out" forensics channel — and the path is
 * also stamped onto the canonical envelope as a top-level
 * {@code capturedStdoutLog} field so the report renderer can show it.
 */
data class NodeInvocation(
    val spec: ValidationNodeSpec,
    val projectDir: Directory,
    val reportDir: Directory,
    val runId: String,
    val contextArg: String? = null,
    val resultOut: File,
    val stdoutLog: File,
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
 *
 * The {@code --result-out} arg moves envelope authorship out of the
 * SDK: nodes write their NodeResult JSON to that path, and the executor
 * post-processes it into the canonical {@code envelope/<nodeId>.json}.
 * The SDK no longer owns the envelope filename, the timing stamps, or
 * the exit-code-from-status policy — that's centralized in
 * {@code PlanExecutor}, single Kotlin implementation, no per-runtime
 * duplication.
 */
internal fun standardArgs(invocation: NodeInvocation): List<String> {
    val args = mutableListOf<String>()
    args += "--nodeId=${invocation.spec.id}"
    args += "--runId=${invocation.runId}"
    args += "--reportDir=${invocation.reportDir.asFile.absolutePath}"
    args += "--result-out=${invocation.resultOut.absolutePath}"
    if (invocation.contextArg != null) {
        args += "--context=${invocation.contextArg}"
    }
    return args
}
