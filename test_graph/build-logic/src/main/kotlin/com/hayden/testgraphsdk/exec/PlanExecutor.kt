package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.ValidationNodeSpec
import com.hayden.testgraphsdk.isFinalizerNode
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logger
import java.io.File
import java.time.Instant

internal fun dependencyClosureByNode(plan: List<ValidationNodeSpec>): Map<String, Set<String>> {
    val byId = plan.associateBy { it.id }
    val memo = mutableMapOf<String, Set<String>>()

    fun visit(nodeId: String): Set<String> =
        memo.getOrPut(nodeId) {
            val spec = byId[nodeId] ?: return@getOrPut emptySet()
            buildSet {
                for (dependencyId in spec.dependsOn) {
                    add(dependencyId)
                    addAll(visit(dependencyId))
                }
            }
        }

    return plan.associate { spec -> spec.id to visit(spec.id) }
}

internal fun jsonObjectValueRange(json: String, key: String): IntRange? {
    var depth = 0
    var inString = false
    var escaped = false
    var i = 0
    while (i < json.length) {
        val ch = json[i]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            i++
            continue
        }

        when (ch) {
            '{' -> depth++
            '}' -> depth--
            '"' -> {
                if (depth == 1 && json.startsWith(key, i)) {
                    val colon = json.indexOf(':', i + key.length)
                    if (colon < 0) return null
                    val braceStart = firstNonWhitespace(json, colon + 1)
                    if (braceStart >= json.length || json[braceStart] != '{') return null
                    val end = jsonObjectEnd(json, braceStart) ?: return null
                    return i..end
                }
                inString = true
            }
        }
        i++
    }
    return null
}

private fun firstNonWhitespace(json: String, start: Int): Int {
    var i = start
    while (i < json.length && json[i].isWhitespace()) i++
    return i
}

private fun jsonObjectEnd(json: String, braceStart: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in braceStart until json.length) {
        val ch = json[i]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}

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
    private val graphName: String,
    private val observability: GraphObservability,
) {
    enum class BuildReplayMode {
        RESUME_GRAPH,
        RUN_ONLY_NODE,
    }

    data class ResumeFromBuild(
        val buildDir: File,
        val nodeId: String,
        val mode: BuildReplayMode = BuildReplayMode.RESUME_GRAPH,
    )

    data class ExecutionSelection(
        val executionPlan: List<ValidationNodeSpec>,
        val selectedNodeIndex: Int?,
    )

    internal fun run(
        plan: List<ValidationNodeSpec>,
        resumeFromBuild: ResumeFromBuild? = null,
        replaySourceSnapshot: ReplaySourceSnapshot? = null,
    ) {
        val reportRoot = reportDir.asFile
        val resumeState = prepareResume(plan, resumeFromBuild, replaySourceSnapshot)
        val cumulative = resumeState.initialContext.toMutableList()
        val executionPlan = resumeState.executionPlan
        val dependencyClosure = dependencyClosureByNode(plan)
        executionPlan.forEach { it.sideEffectSpecs() }
        val provisioningState = ProvisioningState(projectDir.asFile, graphName, runId)
        val environmentRepositoryRuntime = EnvironmentRepositoryRuntime(
            projectDir = projectDir.asFile,
            reportRoot = reportRoot,
            provisioningState = provisioningState,
        )
        val executed = mutableSetOf<String>()
        var executionFailure: RuntimeException? = null

        // .tmp-results/ holds the SDK's raw NodeResult JSON before we
        // post-process it. node-logs/ holds the merged stdout+stderr
        // for crash forensics. envelope/ is the canonical output.
        val tmpResultsDir = File(reportRoot, ".tmp-results").apply { mkdirs() }
        val nodeLogsDir = File(reportRoot, "node-logs").apply { mkdirs() }
        val envelopeDir = File(reportRoot, "envelope")
        ensureRealDirectory(envelopeDir, "canonical envelope directory")

        for ((i, spec) in executionPlan.withIndex()) {
            if (executionFailure != null && !shouldRunAfterFailure(spec, executed)) {
                logger.lifecycle(
                    "  [${i + 1}/${executionPlan.size}] ${spec.id} skipped after earlier failure"
                )
                writeSkippedEnvelope(
                    spec = spec,
                    cumulative = cumulative,
                    reportRoot = reportRoot,
                    nodeLogsDir = nodeLogsDir,
                    envelopeDir = envelopeDir,
                )
                // A skipped node has reached a canonical terminal state. Count
                // it as settled so a finalizer that depends on the ordinary
                // suffix can still run after an earlier failure.
                executed += spec.id
                continue
            }
            logger.lifecycle("  [${i + 1}/${executionPlan.size}] ${spec.id} (${spec.runtime.name})")

            val inputContextFile = if (
                resumeState.useSnapshotForFirstNode && i == 0
            ) {
                writeCapturedInputContextSnapshot(
                    requireNotNull(replaySourceSnapshot),
                    reportRoot,
                    spec.id,
                )
            } else {
                writeInputContextSnapshot(cumulative, reportRoot, spec.id)
            }
            val contextArg = if (cumulative.isEmpty()) null
                             else if (resumeState.useSnapshotForFirstNode && i == 0) {
                                 "@" + inputContextFile.absolutePath
                             } else {
                                 encodeContextArg(cumulative, inputContextFile)
                             }

            val resultOut = File(tmpResultsDir, "${spec.id}.json")
            val stdoutLog = File(nodeLogsDir, "${spec.id}.stdout.log")
            val timeoutMillis = TimeoutParser.parseMillis(spec.timeout)
            val maxAttempts = spec.retries + 1
            val preparedProvisioning = provisioningState.prepare(spec)
            val sideEffectSpecs = spec.sideEffectSpecs()
            val dependencyContext = cumulative.filter {
                it.nodeId in (dependencyClosure[spec.id] ?: emptySet())
            }
            val projectedEnvironment = EnvironmentContextProjection.project(dependencyContext, sideEffectSpecs)

            var execOutcome: ExecutionOutcome = ExecutionOutcome.TimedOut
            var environmentExecution: EnvironmentRepositoryExecution? = null
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

                environmentExecution = try {
                    environmentRepositoryRuntime.execute(spec, preparedProvisioning)
                } catch (e: ProcessOwnershipUncertainException) {
                    throw e
                } catch (e: Exception) {
                    startedAt = Instant.now()
                    endedAt = startedAt
                    writeEnvironmentRepositoryFailureResult(spec, resultOut, e, startedAt)
                    execOutcome = ExecutionOutcome.Completed(-1)
                    break
                }

                val invocation = NodeInvocation(
                    spec = spec,
                    projectDir = projectDir,
                    reportDir = reportDir,
                    runId = runId,
                    contextArg = contextArg,
                    resultOut = resultOut,
                    stdoutLog = stdoutLog,
                    environment = projectedEnvironment +
                            (preparedProvisioning?.environment ?: emptyMap()) +
                            (environmentExecution?.environment ?: emptyMap()) +
                            observability.carrier,
                    timeoutMillis = timeoutMillis,
                )

                startedAt = Instant.now()
                observability.nodeLaunch(spec, attempt)
                execOutcome = try {
                    registry.forNode(spec).execute(invocation)
                } catch (e: ProcessOwnershipUncertainException) {
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    writeExecutorFailureResult(spec, resultOut, e, startedAt)
                    ExecutionOutcome.Completed(-1)
                }
                endedAt = Instant.now()

                // Stop retrying as soon as the executor reports the child
                // returned, regardless of exit code — only timeouts are
                // retryable. A body-returned `failed` should fail fast.
                if (execOutcome !is ExecutionOutcome.TimedOut) break
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
                inputContextFile = inputContextFile,
                rerunGuidance = null,
            )
            val rerunGuidance = if (outcome.status != "passed" && spec.rerun) {
                buildRerunGuidance(spec, reportRoot, inputContextFile)
            } else {
                null
            }
            observability.nodeResult(
                spec,
                outcome.status,
                java.time.Duration.between(startedAt, endedAt),
            )
            val envelopeJson = if (rerunGuidance == null) {
                outcome.envelopeJson
            } else {
                logger.lifecycle("rerun guidance for '${spec.id}':")
                logger.lifecycle("  resume graph: ${rerunGuidance.resumeGraphCommand}")
                logger.lifecycle("  run only: ${rerunGuidance.runOnlyCommand}")
                addRerunGuidance(outcome.envelopeJson, rerunGuidance)
            }
            val withEnvironmentOutputs = mergePublished(
                envelopeJson,
                environmentExecution?.publishedOutputs ?: emptyMap(),
            )
            val withEnvironmentRepository = addEnvironmentRepositoryExecution(
                withEnvironmentOutputs,
                environmentExecution,
                reportRoot,
            )
            val provisioningRecord = provisioningState.recordSuccessful(
                spec = spec,
                prepared = preparedProvisioning,
                status = outcome.status,
            )
            val canonicalEnvelope = addProvisioningState(
                withEnvironmentRepository,
                provisioningRecord,
                reportRoot,
            )
            val validatedEnvelope = CanonicalEnvelopeValidator.validate(
                canonicalEnvelope,
                "canonical envelope for node '${spec.id}'",
                expectedNodeId = spec.id,
                expectedTraceId = observability.traceId,
            )
            publishImmutableEvidence(
                envelope.toPath(),
                canonicalEnvelope.toByteArray(Charsets.UTF_8),
                "canonical envelope for node '${spec.id}'",
            )

            cumulative += ContextItem(spec.id, validatedEnvelope.published)
            executed += spec.id

            // The canonical envelope is the terminal signal. Its validator
            // already enforces that PASS implies spawn exit 0; failed/error
            // evidence may legitimately carry a non-zero process exit. Retain
            // the first failure while continuing only cleanup/finalizer nodes
            // whose dependencies have executed, then rethrow after the plan.
            if (outcome.status != "passed" && executionFailure == null) {
                executionFailure = RuntimeException(
                    "node ${spec.id} ${outcome.status}" +
                            (outcome.reason?.let { ": $it" } ?: "")
                )
            }
        }
        executionFailure?.let { throw it }
    }

    private fun shouldRunAfterFailure(
        spec: ValidationNodeSpec,
        executed: Set<String>,
    ): Boolean =
        spec.isFinalizerNode() && spec.dependsOn.all { it in executed }

    private fun writeSkippedEnvelope(
        spec: ValidationNodeSpec,
        cumulative: List<ContextItem>,
        reportRoot: File,
        nodeLogsDir: File,
        envelopeDir: File,
    ) {
        val now = Instant.now()
        val stdoutLog = File(nodeLogsDir, "${spec.id}.stdout.log").apply {
            parentFile.mkdirs()
            writeText("node skipped after earlier graph failure\n")
        }
        val inputContextFile = writeInputContextSnapshot(
            cumulative,
            reportRoot,
            spec.id,
        )
        observability.nodeResult(spec, "skipped", java.time.Duration.ZERO)
        val outcome = synthesized(
            spec = spec,
            status = "skipped",
            reason = "node skipped after earlier graph failure",
            stdoutRel = relativeToReport(reportRoot, stdoutLog),
            inputContextRel = relativeToReport(reportRoot, inputContextFile),
            exitCode = -1,
            startedAt = now,
            endedAt = now,
        )
        val canonical = CanonicalEnvelopeValidator.validate(
            outcome.envelopeJson,
            "canonical skipped envelope for node '${spec.id}'",
            expectedNodeId = spec.id,
            expectedTraceId = observability.traceId,
        )
        publishImmutableEvidence(
            File(envelopeDir, "${spec.id}.json").toPath(),
            outcome.envelopeJson.toByteArray(Charsets.UTF_8),
            "canonical skipped envelope for node '${spec.id}'",
        )
        check(canonical.status == "skipped")
    }

    private data class ResumeState(
        val executionPlan: List<ValidationNodeSpec>,
        val initialContext: List<ContextItem>,
        val useSnapshotForFirstNode: Boolean,
    )

    private fun prepareResume(
        plan: List<ValidationNodeSpec>,
        resumeFromBuild: ResumeFromBuild?,
        replaySourceSnapshot: ReplaySourceSnapshot?,
    ): ResumeState {
        val selection = selectExecutionPlan(plan, resumeFromBuild)
        if (resumeFromBuild == null) {
            require(replaySourceSnapshot == null) {
                "captured replay source was provided for a full graph execution"
            }
            return ResumeState(selection.executionPlan, emptyList(), false)
        }

        val captured = replaySourceSnapshot ?: throw IllegalArgumentException(
            "replay execution requires one previously verified source snapshot"
        )
        require(
            captured.sourceBuild ==
                    resumeFromBuild.buildDir.canonicalFile
        ) {
            "captured replay source does not match --resume-from-build"
        }
        require(captured.graphName == graphName) {
            "captured replay source graph does not match '$graphName'"
        }
        require(captured.selectedNodeId == resumeFromBuild.nodeId) {
            "captured replay source node does not match '${resumeFromBuild.nodeId}'"
        }

        val resumeIndex = selection.selectedNodeIndex!!
        val resumeSpec = plan[resumeIndex]

        val initialContext = captured.selectedContext
        val currentPlanNodeIds = plan.map { it.id }
        require(captured.sourceExpectedNodeIds == currentPlanNodeIds) {
            "cannot resume from node '${resumeSpec.id}': source full-plan node sequence " +
                    "does not match the current graph plan"
        }
        val expectedPrefixNodeIds = currentPlanNodeIds.take(resumeIndex)
        val capturedPrefixNodeIds = initialContext.map { it.nodeId }
        require(capturedPrefixNodeIds == expectedPrefixNodeIds) {
            "cannot resume from node '${resumeSpec.id}': saved input context node sequence " +
                    "does not match the exact current plan prefix"
        }

        return when (resumeFromBuild.mode) {
            BuildReplayMode.RESUME_GRAPH -> {
                logger.lifecycle(
                    "resuming '${resumeSpec.id}' from ${resumeFromBuild.buildDir.absolutePath}; " +
                            "skipping ${resumeIndex} already-passed dependency step(s)"
                )
                ResumeState(selection.executionPlan, initialContext, true)
            }
            BuildReplayMode.RUN_ONLY_NODE -> {
                logger.lifecycle(
                    "running only '${resumeSpec.id}' from ${resumeFromBuild.buildDir.absolutePath}; " +
                            "not continuing downstream graph nodes"
                )
                ResumeState(selection.executionPlan, initialContext, true)
            }
        }
    }

    private data class EnvelopeOutcome(
        val envelopeJson: String,
        val status: String,
        val reason: String?,
    )

    private data class RerunGuidance(
        val resumeGraphCommand: String,
        val runOnlyCommand: String,
        val inputContextFile: String,
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
        inputContextFile: File,
        rerunGuidance: RerunGuidance?,
    ): EnvelopeOutcome {
        val stdoutRel = relativeToReport(reportRoot, stdoutLog)
        val inputContextRel = relativeToReport(reportRoot, inputContextFile)

        if (execOutcome is ExecutionOutcome.TimedOut) {
            val attempts = spec.retries + 1
            val attemptsClause = if (attempts > 1) " across $attempts attempts" else ""
            return synthesized(
                spec, "errored",
                "node timed out after ${spec.timeout}$attemptsClause; " +
                        "executor force-killed the subprocess (see capturedStdoutLog)",
                stdoutRel, inputContextRel, -1, executorStartedAt, executorEndedAt,
                rerunGuidance = rerunGuidance,
            )
        }
        if (execOutcome is ExecutionOutcome.ProcessContractViolation) {
            return synthesized(
                spec, "errored",
                execOutcome.reason,
                stdoutRel, inputContextRel, execOutcome.exitCode,
                executorStartedAt, executorEndedAt,
                rerunGuidance = rerunGuidance,
            )
        }
        val exitCode = (execOutcome as ExecutionOutcome.Completed).exitCode

        if (!resultOut.isFile) {
            return synthesized(
                spec, "errored",
                "node exited $exitCode without writing --result-out " +
                        "(see capturedStdoutLog for stdout/stderr)",
                stdoutRel, inputContextRel, exitCode, executorStartedAt, executorEndedAt,
                rerunGuidance = rerunGuidance,
            )
        }

        val boundedResult = try {
            readBoundedJsonObject(resultOut, "--result-out for node '${spec.id}'")
        } catch (e: Exception) {
            return synthesized(
                spec, "errored",
                "node wrote invalid --result-out: " +
                        "${e.message?.take(RESULT_ERROR_MESSAGE_CHARS) ?: e.javaClass.simpleName}; " +
                        "see capturedStdoutLog",
                stdoutRel, inputContextRel, exitCode, executorStartedAt, executorEndedAt,
                malformedRaw = readMalformedResultPreview(resultOut),
                rerunGuidance = rerunGuidance,
            )
        }
        val (raw, parsed) = boundedResult
        val tentative = try {
            CanonicalEnvelopeValidator.validateTentative(
                parsed,
                "--result-out for node '${spec.id}'",
                spec.id,
            )
        } catch (e: Exception) {
            return synthesized(
                spec, "errored",
                "node wrote invalid --result-out: " +
                        "${e.message?.take(RESULT_ERROR_MESSAGE_CHARS) ?: e.javaClass.simpleName}; " +
                        "see capturedStdoutLog",
                stdoutRel, inputContextRel, exitCode, executorStartedAt, executorEndedAt,
                malformedRaw = readMalformedResultPreview(resultOut),
                rerunGuidance = rerunGuidance,
            )
        }
        val status = tentative.status
        if (status == "passed" && exitCode != 0) {
            return synthesized(
                spec, "errored",
                "node wrote a passed result but its process exited $exitCode",
                stdoutRel, inputContextRel, exitCode,
                executorStartedAt, executorEndedAt,
                rerunGuidance = rerunGuidance,
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
            append("\"envelopeVersion\":").append(CanonicalEnvelopeValidator.VERSION)
            append(",\"executorStartedAt\":").append(jsonString(executorStartedAt.toString()))
            append(",\"executorEndedAt\":").append(jsonString(executorEndedAt.toString()))
            append(",\"spawnExitCode\":").append(exitCode)
            append(",\"capturedStdoutLog\":").append(jsonString(stdoutRel))
            append(",\"inputContextFile\":").append(jsonString(inputContextRel))
            append(",\"traceId\":").append(jsonString(observability.traceId))
            appendRerunGuidance(rerunGuidance)
            append("}\n")
        }
        return EnvelopeOutcome(appended, status, tentative.failureMessage)
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
        inputContextRel: String,
        exitCode: Int,
        startedAt: Instant,
        endedAt: Instant,
        malformedRaw: String? = null,
        rerunGuidance: RerunGuidance? = null,
    ): EnvelopeOutcome {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"envelopeVersion\":").append(CanonicalEnvelopeValidator.VERSION)
        sb.append(",\"nodeId\":").append(jsonString(spec.id))
        sb.append(",\"status\":").append(jsonString(status))
        sb.append(",\"failureMessage\":").append(jsonString(reason))
        sb.append(",\"startedAt\":").append(jsonString(startedAt.toString()))
        sb.append(",\"endedAt\":").append(jsonString(endedAt.toString()))
        sb.append(",\"executorStartedAt\":").append(jsonString(startedAt.toString()))
        sb.append(",\"executorEndedAt\":").append(jsonString(endedAt.toString()))
        sb.append(",\"spawnExitCode\":").append(exitCode)
        sb.append(",\"capturedStdoutLog\":").append(jsonString(stdoutRel))
        sb.append(",\"inputContextFile\":").append(jsonString(inputContextRel))
        sb.append(",\"traceId\":").append(jsonString(observability.traceId))
        sb.append(",\"assertions\":[]")
        sb.append(",\"artifacts\":[]")
        sb.append(",\"processes\":[]")
        sb.append(",\"metrics\":{}")
        sb.append(",\"logs\":[]")
        sb.append(",\"published\":{}")
        sb.appendRerunGuidance(rerunGuidance)
        if (malformedRaw != null) {
            sb.append(",\"malformedResultOutPreview\":")
            sb.append(jsonString(malformedRaw.take(MALFORMED_PREVIEW_UTF8_BYTES)))
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

    private fun readMalformedResultPreview(resultOut: File): String? = try {
        resultOut.inputStream().use { input ->
            input.readNBytes(MALFORMED_PREVIEW_UTF8_BYTES).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    private fun buildRerunGuidance(
        spec: ValidationNodeSpec,
        reportRoot: File,
        inputContextFile: File,
    ): RerunGuidance {
        val buildArg = shellQuote(reportRoot.absolutePath)
        val graphArg = shellQuote(graphName)
        val nodeArg = shellQuote(spec.id)
        return RerunGuidance(
            resumeGraphCommand = "./gradlew $graphArg --resume-from-build $buildArg --resume-from-node $nodeArg",
            runOnlyCommand = "./gradlew $graphArg --resume-from-build $buildArg --run-only-node $nodeArg",
            inputContextFile = relativeToReport(reportRoot, inputContextFile),
        )
    }

    private fun addRerunGuidance(envelopeJson: String, guidance: RerunGuidance): String {
        val trimmed = envelopeJson.trimEnd().removeSuffix("}")
        return buildString {
            append(trimmed)
            appendRerunGuidance(guidance)
            append("}\n")
        }
    }

    private fun writeEnvironmentRepositoryFailureResult(
        spec: ValidationNodeSpec,
        resultOut: File,
        error: Exception,
        timestamp: Instant,
    ) {
        resultOut.parentFile.mkdirs()
        val now = timestamp.toString()
        resultOut.writeText(
            buildString {
                append("{")
                append("\"nodeId\":").append(jsonString(spec.id))
                append(",\"status\":\"errored\"")
                append(",\"failureMessage\":")
                append(jsonString("environmentRepository failed: ${error.message ?: error::class.simpleName}"))
                append(",\"startedAt\":").append(jsonString(now))
                append(",\"endedAt\":").append(jsonString(now))
                append(",\"assertions\":[]")
                append(",\"artifacts\":[]")
                append(",\"processes\":[]")
                append(",\"metrics\":{}")
                append(",\"logs\":[]")
                append(",\"published\":{}")
                append("}\n")
            }
        )
    }

    private fun writeExecutorFailureResult(
        spec: ValidationNodeSpec,
        resultOut: File,
        error: Exception,
        timestamp: Instant,
    ) {
        resultOut.parentFile.mkdirs()
        val now = timestamp.toString()
        resultOut.writeText(
            buildString {
                append("{")
                append("\"nodeId\":").append(jsonString(spec.id))
                append(",\"status\":\"errored\"")
                append(",\"failureMessage\":")
                append(jsonString("executor failed: ${error.message ?: error::class.simpleName}"))
                append(",\"startedAt\":").append(jsonString(now))
                append(",\"endedAt\":").append(jsonString(now))
                append(",\"assertions\":[]")
                append(",\"artifacts\":[]")
                append(",\"processes\":[]")
                append(",\"metrics\":{}")
                append(",\"logs\":[]")
                append(",\"published\":{}")
                append("}\n")
            }
        )
    }

    private fun addEnvironmentRepositoryExecution(
        envelopeJson: String,
        execution: EnvironmentRepositoryExecution?,
        reportRoot: File,
    ): String {
        if (execution == null) return envelopeJson
        val trimmed = envelopeJson.trimEnd().removeSuffix("}")
        return buildString {
            append(trimmed)
            append(",\"environmentRepositoryExecution\":{")
            append("\"environmentId\":").append(jsonString(execution.identity.id))
            append(",\"branch\":").append(jsonString(execution.identity.branch))
            append(",\"target\":").append(jsonString(execution.identity.target))
            append(",\"backend\":").append(jsonString(execution.identity.backend))
            append(",\"repositoryDir\":").append(jsonString(relativeToReport(reportRoot, execution.repositoryDir)))
            append(",\"templateDir\":").append(jsonString(relativeToReport(reportRoot, execution.templateDir)))
            append(",\"reused\":").append(execution.reused)
            append(",\"outputs\":{")
            execution.outputs.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append(jsonString(key)).append(":").append(jsonString(value))
            }
            append("}")
            append(",\"commands\":[")
            execution.commands.forEachIndexed { index, command ->
                if (index > 0) append(",")
                append("{")
                append("\"label\":").append(jsonString(command.label))
                append(",\"command\":")
                appendJsonArray(command.command)
                append(",\"exitCode\":").append(command.exitCode)
                append(",\"log\":").append(jsonString(relativeToReport(reportRoot, command.log)))
                command.stderrLog?.let {
                    append(",\"stderrLog\":").append(jsonString(relativeToReport(reportRoot, it)))
                }
                append("}")
            }
            append("]}")
            append("}\n")
        }
    }

    private fun addProvisioningState(
        envelopeJson: String,
        record: ProvisioningStateRecord?,
        reportRoot: File,
    ): String {
        if (record == null) return envelopeJson
        val trimmed = envelopeJson.trimEnd().removeSuffix("}")
        return buildString {
            append(trimmed)
            append(",\"provisioningState\":{")
            append("\"environmentId\":").append(jsonString(record.identity.id))
            append(",\"branch\":").append(jsonString(record.identity.branch))
            append(",\"target\":").append(jsonString(record.identity.target))
            append(",\"backend\":").append(jsonString(record.identity.backend))
            append(",\"actions\":")
            appendJsonArray(record.actions)
            record.provisionedMarker?.let {
                append(",\"provisionedMarker\":").append(jsonString(relativeToReport(reportRoot, it)))
            }
            record.deployedMarker?.let {
                append(",\"deployedMarker\":").append(jsonString(relativeToReport(reportRoot, it)))
            }
            record.resetMarker?.let {
                append(",\"resetMarker\":").append(jsonString(relativeToReport(reportRoot, it)))
            }
            record.destroyRequestMarker?.let {
                append(",\"destroyRequestMarker\":").append(jsonString(relativeToReport(reportRoot, it)))
            }
            record.destroyedMarker?.let {
                append(",\"destroyedMarker\":").append(jsonString(relativeToReport(reportRoot, it)))
            }
            append("}}\n")
        }
    }

    private fun StringBuilder.appendRerunGuidance(guidance: RerunGuidance?) {
        if (guidance == null) return
        append(",\"rerunGuidance\":{")
        append("\"resumeGraphCommand\":").append(jsonString(guidance.resumeGraphCommand))
        append(",\"runOnlyCommand\":").append(jsonString(guidance.runOnlyCommand))
        append(",\"inputContextFile\":").append(jsonString(guidance.inputContextFile))
        append("}")
    }

    private fun StringBuilder.appendJsonArray(values: Iterable<String>) {
        append("[")
        values.forEachIndexed { index, value ->
            if (index > 0) append(",")
            append(jsonString(value))
        }
        append("]")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        internal fun mergePublished(
            envelopeJson: String,
            additions: Map<String, String>,
        ): String {
            // Validate even when there are no additions. A child-authored
            // non-string published value must never be silently coerced into
            // downstream context by MiniJson.stringMap/toString.
            val published = linkedMapOf<String, String>()
            published.putAll(ContextSerde.extractPublished(envelopeJson))
            if (additions.isEmpty()) return envelopeJson
            published.putAll(additions)

            val replacement = buildString {
                append("\"published\":{")
                published.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(",")
                    append(jsonString(key)).append(":").append(jsonString(value))
                }
                append("}")
            }

            val range = jsonObjectValueRange(envelopeJson, "\"published\"")
            if (range != null) {
                return envelopeJson.replaceRange(range, replacement)
            }

            val trimmed = envelopeJson.trimEnd().removeSuffix("}")
            val sep = if (trimmed.trimEnd().endsWith("{")) "" else ","
            return "$trimmed$sep$replacement}\n"
        }

        internal fun selectExecutionPlan(
            plan: List<ValidationNodeSpec>,
            resumeFromBuild: ResumeFromBuild?,
        ): ExecutionSelection {
            if (resumeFromBuild == null) {
                return ExecutionSelection(plan, selectedNodeIndex = null)
            }

            val selectedIndex = plan.indexOfFirst { it.id == resumeFromBuild.nodeId }
            if (selectedIndex < 0) {
                throw IllegalArgumentException(
                    "cannot resume from node '${resumeFromBuild.nodeId}': node is not in this graph plan"
                )
            }

            val selected = plan[selectedIndex]
            if (!selected.rerun) {
                throw IllegalArgumentException(
                    "cannot resume from node '${selected.id}': node metadata has rerun=false"
                )
            }

            val executionPlan = when (resumeFromBuild.mode) {
                BuildReplayMode.RESUME_GRAPH -> plan.drop(selectedIndex)
                BuildReplayMode.RUN_ONLY_NODE -> listOf(selected)
            }
            return ExecutionSelection(executionPlan, selectedIndex)
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

        private const val MALFORMED_PREVIEW_UTF8_BYTES = 4_096
        private const val RESULT_ERROR_MESSAGE_CHARS = 512
    }
}
