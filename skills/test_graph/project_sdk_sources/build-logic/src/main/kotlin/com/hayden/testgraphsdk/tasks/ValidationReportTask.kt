package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.MiniJson
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Aggregates per-node JSON envelopes in the most recent run dir into
 * two outputs in the same pass:
 *
 *   - {@code summary.json} — flat concatenation of every envelope,
 *     keyed by runId. Machine-readable; ride-along for tooling that
 *     wants the full structured payload.
 *   - {@code report.md} — single-file at-a-glance run report. Section
 *     per node with status, durations, assertions, metrics, published
 *     context, subprocesses table (with relative log links), and a
 *     pointer at the captured-stdout log. The renderer matches over
 *     which envelope fields are populated, so adding a new "kind of
 *     thing a node can report" only takes one more section here.
 *
 * Stays dependency-free: parsing uses {@link MiniJson} (already
 * vendored in this plugin), markdown is generated with raw string
 * concatenation. No JSON-to-POJO mapper, no markdown-DOM library.
 */
abstract class ValidationReportTask : DefaultTask() {
    @get:Internal abstract val reportRoot: DirectoryProperty

    init {
        group = "validation"
        description = "Aggregate per-node envelopes in the latest run into summary.json + report.md."
    }

    @TaskAction
    fun report() {
        val root = reportRoot.get().asFile
        if (!root.isDirectory) {
            logger.lifecycle("no reports dir at ${root.absolutePath}")
            return
        }
        // `validationRunAll` is finalizedBy this task once after every
        // graph has finished, but each graph creates its own timestamped
        // run dir. Walk every dir that has an envelope/ — write one
        // summary.json + report.md per run dir. Idempotent: re-running
        // overwrites the existing files.
        val runDirs = root.listFiles { f -> f.isDirectory && File(f, "envelope").isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (runDirs.isEmpty()) {
            logger.lifecycle("no runs found under ${root.absolutePath}")
            return
        }
        for (runDir in runDirs) {
            writeRunReport(runDir)
        }
    }

    private fun writeRunReport(runDir: File) {
        val envelopeFiles = File(runDir, "envelope").listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()

        // 1. summary.json — machine-readable concatenation.
        val summarySb = StringBuilder()
        summarySb.append('{')
        summarySb.append("\"runId\":\"").append(runDir.name).append("\",")
        summarySb.append("\"nodes\":[")
        envelopeFiles.forEachIndexed { i, f ->
            if (i > 0) summarySb.append(',')
            summarySb.append(f.readText().trim())
        }
        summarySb.append("]}")
        val summaryOut = File(runDir, "summary.json")
        summaryOut.writeText(summarySb.toString())

        // 2. report.md — human-friendly per-run report.
        val parsed = envelopeFiles.mapNotNull { f ->
            val raw = f.readText()
            val obj = try { MiniJson.parse(raw) as? Map<*, *> } catch (e: Exception) { null }
            obj?.let { f to it }
        }
        val markdown = renderReport(runDir.name, parsed)
        val reportOut = File(runDir, "report.md")
        reportOut.writeText(markdown)

        logger.lifecycle(
            "wrote ${summaryOut.absolutePath} + ${reportOut.absolutePath} " +
                    "(${envelopeFiles.size} node envelopes)"
        )
    }

    private fun renderReport(runId: String, envelopes: List<Pair<File, Map<*, *>>>): String {
        val sb = StringBuilder()

        // Roll-up counts so the report header tells the story at a glance.
        val statusCounts = mutableMapOf<String, Int>()
        for ((_, env) in envelopes) {
            val s = (env["status"] as? String) ?: "unknown"
            statusCounts.merge(s, 1) { a, b -> a + b }
        }
        val total = envelopes.size
        val passed = statusCounts.getOrDefault("passed", 0)
        val failed = statusCounts.getOrDefault("failed", 0)
        val errored = statusCounts.getOrDefault("errored", 0)
        val skipped = statusCounts.getOrDefault("skipped", 0)
        val overall = when {
            errored > 0 -> "ERRORED"
            failed > 0 -> "FAILED"
            else -> "PASSED"
        }

        sb.append("# Validation report — ").append(runId).append("\n\n")
        sb.append("**Overall**: ").append(overall).append("  \n")
        sb.append("**Nodes**: ").append(total)
        sb.append(" (passed=").append(passed)
        sb.append(", failed=").append(failed)
        sb.append(", errored=").append(errored)
        if (skipped > 0) sb.append(", skipped=").append(skipped)
        sb.append(")\n\n")

        // Plan summary table — quickest scan path: status + duration per node.
        sb.append("| Node | Status | Duration | Captured stdout |\n")
        sb.append("|---|---|---|---|\n")
        for ((_, env) in envelopes) {
            val nodeId = (env["nodeId"] as? String) ?: "?"
            val status = (env["status"] as? String) ?: "?"
            val durationMs = durationFromExecutor(env)
            val durationStr = if (durationMs >= 0) "${durationMs}ms" else "—"
            val stdoutPath = env["capturedStdoutLog"] as? String
            val stdoutCell = if (stdoutPath != null) "[$stdoutPath]($stdoutPath)" else "—"
            sb.append("| `").append(nodeId).append("` | ").append(badge(status))
              .append(" | ").append(durationStr).append(" | ").append(stdoutCell).append(" |\n")
        }
        sb.append('\n')

        // One section per node, in plan order.
        for ((_, env) in envelopes) {
            renderNode(sb, env)
        }
        return sb.toString()
    }

    private fun renderNode(sb: StringBuilder, env: Map<*, *>) {
        val nodeId = (env["nodeId"] as? String) ?: "?"
        val status = (env["status"] as? String) ?: "?"
        sb.append("## `").append(nodeId).append("` — ").append(badge(status)).append("\n\n")

        val failureMessage = env["failureMessage"] as? String
        if (failureMessage != null) {
            sb.append("**Failure**: ").append(failureMessage).append("\n\n")
        }
        val errorStack = env["errorStack"] as? String
        if (errorStack != null) {
            sb.append("<details><summary>Error stack</summary>\n\n```\n")
              .append(errorStack.trim()).append("\n```\n</details>\n\n")
        }

        // Timing: prefer executor-measured (covers the full spawn) when
        // present, fall back to body-internal (legacy / SDK-stamped).
        val timingLines = mutableListOf<String>()
        (env["executorStartedAt"] as? String)?.let {
            timingLines += "executor start: `$it`"
        }
        (env["executorEndedAt"] as? String)?.let {
            timingLines += "executor end: `$it`"
        }
        (env["spawnExitCode"] as? Number)?.let {
            timingLines += "spawn exit code: $it"
        }
        if (timingLines.isNotEmpty()) {
            sb.append(timingLines.joinToString(separator = "  \n")).append("\n\n")
        }

        renderAssertions(sb, env["assertions"])
        renderMetrics(sb, env["metrics"])
        renderProcesses(sb, env["processes"])
        renderArtifacts(sb, env["artifacts"])
        renderPublished(sb, env["published"])
        renderInlineLogs(sb, env["logs"])

        // Captured node-process stdout pointer.
        val stdoutPath = env["capturedStdoutLog"] as? String
        if (stdoutPath != null) {
            sb.append("**Node-process stdout**: [")
              .append(stdoutPath).append("](").append(stdoutPath).append(")\n\n")
        }
        sb.append("---\n\n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderAssertions(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Assertions\n\n")
        sb.append("| Name | Status |\n|---|---|\n")
        for (item in list) {
            val a = item as? Map<*, *> ?: continue
            sb.append("| ").append(a["name"]).append(" | ").append(badge(a["status"] as? String)).append(" |\n")
        }
        sb.append('\n')
    }

    private fun renderMetrics(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        if (map.isEmpty()) return
        sb.append("### Metrics\n\n")
        for ((k, v) in map) {
            sb.append("- `").append(k).append("`: ").append(v).append("\n")
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderProcesses(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Subprocesses\n\n")
        sb.append("| Label | Exit | Duration | PID | Log | Error |\n")
        sb.append("|---|---|---|---|---|---|\n")
        for (item in list) {
            val p = item as? Map<*, *> ?: continue
            val label = p["label"] ?: "?"
            val exit = p["exitCode"] ?: "—"
            val duration = processDurationMs(p)
            val durationStr = if (duration >= 0) "${duration}ms" else "—"
            val pid = p["pid"] ?: "—"
            val logPath = p["log"] as? String
            val logCell = if (logPath != null) "[`$logPath`]($logPath)" else "—"
            val error = (p["error"] as? String)?.let { it.replace("|", "\\|") } ?: ""
            sb.append("| ").append(label)
              .append(" | ").append(exit)
              .append(" | ").append(durationStr)
              .append(" | ").append(pid)
              .append(" | ").append(logCell)
              .append(" | ").append(error).append(" |\n")
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderArtifacts(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Artifacts\n\n")
        for (item in list) {
            val a = item as? Map<*, *> ?: continue
            val type = a["type"] ?: "?"
            val path = a["path"] as? String ?: continue
            sb.append("- `").append(type).append("` — [`").append(path).append("`](").append(path).append(")\n")
        }
        sb.append('\n')
    }

    private fun renderPublished(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        if (map.isEmpty()) return
        sb.append("### Published context\n\n")
        for ((k, v) in map) {
            sb.append("- `").append(k).append("`: `").append(v).append("`\n")
        }
        sb.append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderInlineLogs(sb: StringBuilder, raw: Any?) {
        val list = (raw as? List<*>) ?: return
        if (list.isEmpty()) return
        sb.append("### Inline logs\n\n```\n")
        for (line in list) {
            sb.append(line).append('\n')
        }
        sb.append("```\n\n")
    }

    private fun durationFromExecutor(env: Map<*, *>): Long {
        val start = env["executorStartedAt"] as? String
        val end = env["executorEndedAt"] as? String
        return diffMs(start, end)
    }

    private fun processDurationMs(p: Map<*, *>): Long {
        val start = p["startedAt"] as? String
        val end = p["endedAt"] as? String
        return diffMs(start, end)
    }

    private fun diffMs(startIso: String?, endIso: String?): Long {
        if (startIso == null || endIso == null) return -1
        return try {
            java.time.Instant.parse(endIso).toEpochMilli() -
                    java.time.Instant.parse(startIso).toEpochMilli()
        } catch (e: Exception) {
            -1
        }
    }

    private fun badge(status: String?): String = when (status) {
        "passed" -> "**PASS**"
        "failed" -> "**FAIL**"
        "errored" -> "**ERROR**"
        "skipped" -> "_skipped_"
        else -> status ?: "?"
    }
}
