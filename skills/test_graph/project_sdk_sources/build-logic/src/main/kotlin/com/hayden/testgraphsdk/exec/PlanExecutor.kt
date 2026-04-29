package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.MiniJson
import com.hayden.testgraphsdk.ValidationNodeSpec
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logger
import java.io.File
import java.time.Instant

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
 * The envelope is centrally authored here, not in the SDK. The SDK
 * writes a "tentative" NodeResult JSON to {@code --result-out=<tmp>};
 * this executor post-processes that file into the canonical
 * {@code envelope/<nodeId>.json} — stamping executor-measured timing,
 * recording the captured-stdout-log path, and synthesizing a fallback
 * envelope when the SDK output is missing or malformed. The result:
 * every planned node ends up with exactly one well-formed envelope,
 * regardless of SDK behavior.
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

        // .tmp-results/ holds the SDK's raw NodeResult JSON before we
        // post-process it. node-logs/ holds the merged stdout+stderr
        // for crash forensics. envelope/ is the canonical output.
        val tmpResultsDir = File(reportRoot, ".tmp-results").apply { mkdirs() }
        val nodeLogsDir = File(reportRoot, "node-logs").apply { mkdirs() }
        val envelopeDir = File(reportRoot, "envelope").apply { mkdirs() }

        for ((i, spec) in plan.withIndex()) {
            logger.lifecycle("  [${i + 1}/${plan.size}] ${spec.id} (${spec.runtime.name})")

            val contextArg = if (cumulative.isEmpty()) null
                             else encodeContextArg(cumulative, reportRoot, i)

            val resultOut = File(tmpResultsDir, "${spec.id}.json")
            val stdoutLog = File(nodeLogsDir, "${spec.id}.stdout.log")
            val timeoutMillis = TimeoutParser.parseMillis(spec.timeout)
            val maxAttempts = spec.retries + 1

            var execOutcome: ExecutionOutcome = ExecutionOutcome.TimedOut
            var startedAt = Instant.now()
            var endedAt = startedAt
            for (attempt in 1..maxAttempts) {
                if (attempt > 1) {
                    logger.lifecycle(
                        "    retry ${attempt - 1}/${spec.retries} after timeout — " +
                                "previous attempt exceeded ${spec.timeout}"
                    )
                }
                // Defensive: clear leftover --result-out from the prior
                // attempt (or from a stale earlier run). The "did the SDK
                // write anything" check in buildEnvelope below relies on
                // this being unambiguous.
                resultOut.delete()

                val invocation = NodeInvocation(
                    spec = spec,
                    projectDir = projectDir,
                    reportDir = reportDir,
                    runId = runId,
                    contextArg = contextArg,
                    resultOut = resultOut,
                    stdoutLog = stdoutLog,
                    timeoutMillis = timeoutMillis,
                )

                startedAt = Instant.now()
                execOutcome = registry.forNode(spec).execute(invocation)
                endedAt = Instant.now()

                // Stop retrying as soon as the executor reports the child
                // returned, regardless of exit code — only timeouts are
                // retryable. A body-returned `failed` should fail fast.
                if (execOutcome is ExecutionOutcome.Completed) break
            }

            val envelope = File(envelopeDir, "${spec.id}.json")
            val outcome = buildEnvelope(
                spec = spec,
                resultOut = resultOut,
                stdoutLog = stdoutLog,
                execOutcome = execOutcome,
                executorStartedAt = startedAt,
                executorEndedAt = endedAt,
                reportRoot = reportRoot,
            )
            envelope.writeText(outcome.envelopeJson)

            cumulative += readContextItem(spec.id)

            // Pass/fail decided from envelope status, NOT from exit code —
            // a node may legitimately exit non-zero (e.g. NodeResult.fail
            // with the SDK's exit-code-from-status policy) but the canonical
            // signal is the parsed status. We still throw on a failed
            // status so RunTestGraphTask gets the FAIL signal.
            if (outcome.status != "passed") {
                throw RuntimeException(
                    "node ${spec.id} ${outcome.status}" +
                            (outcome.reason?.let { ": $it" } ?: "")
                )
            }
        }
    }

    private fun readContextItem(nodeId: String): ContextItem {
        val envelope = File(reportDir.asFile, "envelope/$nodeId.json")
        val data = if (envelope.isFile) ContextSerde.extractPublished(envelope.readText())
                   else emptyMap()
        return ContextItem(nodeId, data)
    }

    private data class EnvelopeOutcome(
        val envelopeJson: String,
        val status: String,
        val reason: String?,
    )

    /**
     * Post-process the SDK's --result-out file into the canonical
     * envelope. Five cases:
     *
     *   0. executor reported a timeout → synthesize an "errored"
     *      envelope with `timed out` reason; the partial stdout log is
     *      the forensics channel. Skips reading --result-out (the
     *      child was force-killed mid-write so it's at best partial).
     *   1. result-out exists & parses & status valid → inject the
     *      executor-stamped fields ({@code executorStartedAt},
     *      {@code executorEndedAt}, {@code capturedStdoutLog},
     *      {@code spawnExitCode}) into the SDK's JSON and return it.
     *   2. result-out missing → SDK crashed before writing. Synthesize
     *      an "errored" envelope; the captured stdout is where to look.
     *   3. result-out malformed → SDK wrote partial / non-JSON output.
     *      Synthesize the same shape as (2) plus a parse-error reason.
     *   4. result-out parses but status missing/unknown → treat as (3).
     */
    private fun buildEnvelope(
        spec: ValidationNodeSpec,
        resultOut: File,
        stdoutLog: File,
        execOutcome: ExecutionOutcome,
        executorStartedAt: Instant,
        executorEndedAt: Instant,
        reportRoot: File,
    ): EnvelopeOutcome {
        val stdoutRel = relativeToReport(reportRoot, stdoutLog)

        if (execOutcome is ExecutionOutcome.TimedOut) {
            val attempts = spec.retries + 1
            val attemptsClause = if (attempts > 1) " across $attempts attempts" else ""
            return synthesized(
                spec, "errored",
                "node timed out after ${spec.timeout}$attemptsClause; " +
                        "executor force-killed the subprocess (see capturedStdoutLog)",
                stdoutRel, -1, executorStartedAt, executorEndedAt,
            )
        }
        val exitCode = (execOutcome as ExecutionOutcome.Completed).exitCode

        if (!resultOut.isFile) {
            return synthesized(
                spec, "errored",
                "node exited $exitCode without writing --result-out " +
                        "(see capturedStdoutLog for stdout/stderr)",
                stdoutRel, exitCode, executorStartedAt, executorEndedAt,
            )
        }

        val raw = resultOut.readText()
        val parsed = try { MiniJson.parse(raw) } catch (e: Exception) { null }
        if (parsed !is Map<*, *>) {
            return synthesized(
                spec, "errored",
                "node wrote malformed --result-out (not a JSON object); see capturedStdoutLog",
                stdoutRel, exitCode, executorStartedAt, executorEndedAt,
                malformedRaw = raw,
            )
        }
        val status = parsed["status"] as? String
        if (status == null || status !in VALID_STATUSES) {
            return synthesized(
                spec, "errored",
                "node wrote --result-out with invalid status=${status ?: "<missing>"}; see capturedStdoutLog",
                stdoutRel, exitCode, executorStartedAt, executorEndedAt,
                malformedRaw = raw,
            )
        }

        // Happy path: inject the executor-stamped fields by string-level
        // append before the closing brace. Cheaper than a full
        // parse/rewrite cycle and round-trip-safe because we already
        // confirmed `raw` is a JSON object.
        val trimmed = raw.trimEnd().removeSuffix("}")
        val needsComma = !trimmed.trimEnd().endsWith("{")
        val sep = if (needsComma) "," else ""
        val appended = buildString {
            append(trimmed)
            append(sep)
            append("\"executorStartedAt\":").append(jsonString(executorStartedAt.toString()))
            append(",\"executorEndedAt\":").append(jsonString(executorEndedAt.toString()))
            append(",\"spawnExitCode\":").append(exitCode)
            append(",\"capturedStdoutLog\":").append(jsonString(stdoutRel))
            append("}\n")
        }
        return EnvelopeOutcome(appended, status, parsed["failureMessage"] as? String)
    }

    /**
     * Build a minimal but well-formed envelope for the failure cases.
     * Carries the same field set as the happy path so report renderers
     * don't need a special branch — they always see the same shape.
     */
    private fun synthesized(
        spec: ValidationNodeSpec,
        status: String,
        reason: String,
        stdoutRel: String,
        exitCode: Int,
        startedAt: Instant,
        endedAt: Instant,
        malformedRaw: String? = null,
    ): EnvelopeOutcome {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"nodeId\":").append(jsonString(spec.id))
        sb.append(",\"status\":").append(jsonString(status))
        sb.append(",\"failureMessage\":").append(jsonString(reason))
        sb.append(",\"startedAt\":").append(jsonString(startedAt.toString()))
        sb.append(",\"endedAt\":").append(jsonString(endedAt.toString()))
        sb.append(",\"executorStartedAt\":").append(jsonString(startedAt.toString()))
        sb.append(",\"executorEndedAt\":").append(jsonString(endedAt.toString()))
        sb.append(",\"spawnExitCode\":").append(exitCode)
        sb.append(",\"capturedStdoutLog\":").append(jsonString(stdoutRel))
        sb.append(",\"assertions\":[]")
        sb.append(",\"artifacts\":[]")
        sb.append(",\"processes\":[]")
        sb.append(",\"metrics\":{}")
        sb.append(",\"logs\":[]")
        sb.append(",\"published\":{}")
        if (malformedRaw != null) {
            sb.append(",\"malformedResultOutPreview\":")
            sb.append(jsonString(malformedRaw.take(MALFORMED_PREVIEW_BYTES)))
        }
        sb.append("}\n")
        return EnvelopeOutcome(sb.toString(), status, reason)
    }

    private fun relativeToReport(reportRoot: File, target: File): String =
        try {
            reportRoot.toPath().toAbsolutePath()
                .relativize(target.toPath().toAbsolutePath())
                .toString()
        } catch (e: IllegalArgumentException) {
            target.absolutePath
        }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else ->
                if (c.code < 0x20) sb.append("\\u").append("%04x".format(c.code))
                else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        private val VALID_STATUSES = setOf("passed", "failed", "errored", "skipped")
        private const val MALFORMED_PREVIEW_BYTES = 4096
    }
}
