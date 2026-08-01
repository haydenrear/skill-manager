package com.hayden.testgraphsdk.tasks

import com.hayden.testgraphsdk.exec.AggregateJsonStructureBudget
import com.hayden.testgraphsdk.exec.CanonicalEnvelopeValidator
import com.hayden.testgraphsdk.exec.CONTEXT_JSON_MAX_UTF8_BYTES
import com.hayden.testgraphsdk.exec.ContextItem
import com.hayden.testgraphsdk.exec.ContextSerde
import com.hayden.testgraphsdk.exec.GraphObservability
import com.hayden.testgraphsdk.exec.ReplaySourceSnapshot
import com.hayden.testgraphsdk.exec.publishImmutableEvidence
import com.hayden.testgraphsdk.exec.parseBoundedJsonObject
import com.hayden.testgraphsdk.exec.readBoundedUtf8RegularFile
import com.hayden.testgraphsdk.isValidNodeId
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes one {@code summary.json} + one {@code report.md} for a single
 * test-graph run dir.
 *
 * <p>This used to live inside {@link ValidationReportTask} as the
 * task-action body. Two problems with that location:
 *
 * <ul>
 *   <li>{@code validationReport} is wired as a finalizer on every per-graph
 *       task <em>and</em> on {@code validationRunAll}. Gradle runs a
 *       task at most once per build invocation, so when several graph
 *       tasks share the same finalizer the report task fires once at
 *       a moment that is sensitive to scheduling — in practice, fanning
 *       out across multiple graphs left some run dirs without a report.</li>
 *   <li>It coupled "render markdown for one run dir" to a Gradle task
 *       lifecycle, when the renderer itself is a bounded transformation
 *       over one run's persisted evidence.</li>
 * </ul>
 *
 * The renderer now lives here as a plain object so {@link
 * com.hayden.testgraphsdk.exec.PlanExecutor} can call it inline at the
 * end of a graph run (guaranteeing every graph emits its report) and
 * {@link ValidationReportTask} can still call it for the
 * "regenerate every existing report" use case.
 */
internal object RunReportWriter {

    enum class ReplayMode(val wireName: String) {
        RESUME_FROM_NODE("resume-from-node"),
        RUN_ONLY_NODE("run-only-node"),
    }

    data class ReplayMetadata(
        val mode: ReplayMode,
        val selectedNodeId: String,
        val sourceBuild: File,
        val sourceClosureSha256: String,
        val sourceContextSha256: String,
    )

    private data class ExecutionScope(
        val graphName: String?,
        val expectedNodeIds: List<String>,
        val replay: ReplayMetadata?,
        val legacyUnknown: Boolean = false,
    ) {
        val modeWireName: String
            get() = replay?.mode?.wireName ?: if (legacyUnknown) "legacy-unknown" else "full"
    }

    private data class AttemptClosure(
        val runId: String,
        val traceId: String,
        val scope: ExecutionScope,
        val finalizerNodeIds: List<String>,
        val scopeSha256: String,
        val carrierSha256: String,
        val contextSha256: Map<String, String>,
        val envelopeSha256: Map<String, String>,
    )

    private data class ClosedEvidenceSnapshot(
        val contextSha256: Map<String, String>,
        val envelopeSha256: Map<String, String>,
        val selectedContextJson: String? = null,
        val selectedContext: List<ContextItem>? = null,
    )

    data class Outcome(
        val written: Boolean,
        val status: String?,
        val complete: Boolean,
    ) {
        val passed: Boolean
            get() = written && complete && status == "passed"
    }

    private data class ReportIntegrity(
        val expectedNodeIds: List<String>,
        val observedNodeIds: List<String>,
        val missingNodeIds: List<String>,
        val invalidEnvelopeFiles: List<String>,
        val duplicateNodeIds: List<String>,
        val unexpectedNodeIds: List<String>,
        val unknownStatusNodeIds: List<String>,
        val missingTraceNodeIds: List<String>,
        val invalidTraceNodeIds: List<String>,
        val mismatchedTraceNodeIds: List<String>,
        val observedContextNodeIds: List<String>,
        val missingContextNodeIds: List<String>,
        val invalidContextFiles: List<String>,
        val unexpectedContextNodeIds: List<String>,
        val contextProvenanceViolationNodeIds: List<String>,
        val replaySourceContextMismatchNodeIds: List<String>,
        val contextFileCountExceeded: Boolean,
        val aggregateContextBytesExceeded: Boolean,
        val emptyEvidence: Boolean,
        val envelopeFileCountExceeded: Boolean,
        val aggregateEnvelopeBytesExceeded: Boolean,
        val aggregateJsonStructureExceeded: Boolean,
        val unknownExecutionScope: Boolean,
        val attemptClosureIntegrityError: String?,
        val executionFailure: Throwable?,
    ) {
        val errored: Boolean
            get() = executionFailure != null || missingNodeIds.isNotEmpty() ||
                    invalidEnvelopeFiles.isNotEmpty() || duplicateNodeIds.isNotEmpty() ||
                    unexpectedNodeIds.isNotEmpty() || unknownStatusNodeIds.isNotEmpty() ||
                    missingTraceNodeIds.isNotEmpty() || invalidTraceNodeIds.isNotEmpty() ||
                    mismatchedTraceNodeIds.isNotEmpty() || missingContextNodeIds.isNotEmpty() ||
                    invalidContextFiles.isNotEmpty() || unexpectedContextNodeIds.isNotEmpty() ||
                    contextProvenanceViolationNodeIds.isNotEmpty() ||
                    replaySourceContextMismatchNodeIds.isNotEmpty() ||
                    contextFileCountExceeded || aggregateContextBytesExceeded ||
                    emptyEvidence || envelopeFileCountExceeded || aggregateEnvelopeBytesExceeded ||
                    aggregateJsonStructureExceeded ||
                    unknownExecutionScope || attemptClosureIntegrityError != null

        val complete: Boolean
            get() = !errored
    }

    internal data class ContextSnapshotIntegrity(
        val observedNodeIds: List<String>,
        val missingNodeIds: List<String>,
        val invalidFiles: List<String>,
        val unexpectedNodeIds: List<String>,
        val provenanceViolationNodeIds: List<String> = emptyList(),
        val replaySourceMismatchNodeIds: List<String> = emptyList(),
        val fileCountExceeded: Boolean,
        val aggregateBytesExceeded: Boolean,
    )

    private data class ParsedContextSnapshot(
        val sha256: String,
        val selectedRaw: String?,
        val items: List<ContextItem>,
    )

    private data class ParsedEnvelope(
        val file: File,
        val raw: String,
        val value: Map<String, Any?>,
        val validated: CanonicalEnvelopeValidator.Validated,
    )

    fun persistExecutionScope(
        runDir: File,
        graphName: String,
        expectedNodeIds: List<String>,
        replay: ReplayMetadata? = null,
    ) {
        val desired = validatedExecutionScope(graphName, expectedNodeIds, replay)
        requireReplaySourceDistinctFromTarget(runDir, desired.replay)
        val scopeFile = File(runDir, EXECUTION_SCOPE_FILE)
        if (Files.exists(scopeFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            requireMatchingExecutionScope(scopeFile, desired)
            return
        }
        try {
            Files.createDirectories(runDir.toPath())
        } catch (e: Exception) {
            throw IllegalStateException(
                "could not create execution scope directory: ${runDir.absolutePath}",
                e,
            )
        }
        requireRegularDirectory(runDir, "execution scope directory")
        val json = executionScopeJson(desired)
        val encoded = json.toByteArray(Charsets.UTF_8)
        require(encoded.size <= CONTEXT_JSON_MAX_UTF8_BYTES) {
            "execution scope exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes"
        }
        val temp = Files.createTempFile(runDir.toPath(), ".execution-scope-", ".tmp")
        try {
            writeAndForce(temp, encoded)
            try {
                // A hard link publishes the already-complete inode in one
                // no-replace operation. Unsupported filesystems fail closed.
                Files.createLink(scopeFile.toPath(), temp)
            } catch (_: FileAlreadyExistsException) {
                requireMatchingExecutionScope(scopeFile, desired)
            } catch (e: UnsupportedOperationException) {
                throw IllegalStateException(
                    "execution scope publication requires same-filesystem hard-link support",
                    e,
                )
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun resolveExecutionScope(
        runDir: File,
        graphName: String?,
        expectedNodeIds: List<String>,
        replay: ReplayMetadata?,
    ): ExecutionScope {
        val scopeFile = File(runDir, EXECUTION_SCOPE_FILE)
        val requested = if (expectedNodeIds.isNotEmpty() || replay != null || graphName != null) {
            validatedExecutionScope(
                graphName ?: throw IllegalArgumentException(
                    "graphName is required when reporting an explicit execution scope"
                ),
                expectedNodeIds,
                replay,
            )
        } else {
            null
        }
        if (Files.exists(scopeFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val persisted = readExecutionScope(scopeFile)
            require(requested == null || requested == persisted) {
                "persisted execution scope does not match report request"
            }
            requireReplaySourceDistinctFromTarget(runDir, persisted.replay)
            return persisted
        }
        requested?.let { requireReplaySourceDistinctFromTarget(runDir, it.replay) }
        return requested ?: ExecutionScope(
            graphName = null,
            expectedNodeIds = emptyList(),
            replay = null,
            legacyUnknown = true,
        )
    }

    private fun requireMatchingExecutionScope(scopeFile: File, desired: ExecutionScope) {
        requireRegularFile(scopeFile, "existing execution scope")
        require(readExecutionScope(scopeFile) == desired) {
            "existing execution scope does not match this graph invocation"
        }
    }

    private fun validatedExecutionScope(
        graphName: String,
        expectedNodeIds: List<String>,
        replay: ReplayMetadata?,
    ): ExecutionScope {
        requireValidGraphName(graphName)
        require(expectedNodeIds.size in 1..MAX_ENVELOPE_FILES) {
            "expected node count must be 1..$MAX_ENVELOPE_FILES; the absolute report limit is " +
                    MAX_ENVELOPE_FILES
        }
        require(expectedNodeIds.all(::isValidNodeId)) {
            "expected node ids must match [a-z0-9._-]{1,128}"
        }
        require(expectedNodeIds.distinct().size == expectedNodeIds.size) {
            "expected node ids must be unique"
        }
        val normalizedReplay = replay?.copy(
            sourceBuild = normalizedAbsoluteFile(replay.sourceBuild)
        )
        require(normalizedReplay == null || isValidNodeId(normalizedReplay.selectedNodeId)) {
            "replay selected node id must match [a-z0-9._-]{1,128}"
        }
        require(normalizedReplay == null || normalizedReplay.selectedNodeId in expectedNodeIds) {
            "replay selected node id must be in the expected execution scope"
        }
        when (normalizedReplay?.mode) {
            ReplayMode.RUN_ONLY_NODE -> require(
                expectedNodeIds == listOf(normalizedReplay.selectedNodeId)
            ) {
                "run-only replay scope must contain only the selected node"
            }
            ReplayMode.RESUME_FROM_NODE -> require(
                expectedNodeIds.firstOrNull() == normalizedReplay.selectedNodeId
            ) {
                "resume replay scope must start with the selected node"
            }
            null -> Unit
        }
        require(
            normalizedReplay == null ||
                    normalizedReplay.sourceBuild.absolutePath.length <= MAX_SOURCE_BUILD_PATH_CHARS
        ) {
            "replay source build path exceeds $MAX_SOURCE_BUILD_PATH_CHARS characters"
        }
        require(
            normalizedReplay == null ||
                    SHA256.matches(normalizedReplay.sourceClosureSha256)
        ) {
            "replay source closure SHA-256 must be lowercase hexadecimal"
        }
        require(
            normalizedReplay == null ||
                    SHA256.matches(normalizedReplay.sourceContextSha256)
        ) {
            "replay source context SHA-256 must be lowercase hexadecimal"
        }
        return ExecutionScope(graphName, expectedNodeIds.toList(), normalizedReplay)
    }

    private fun readExecutionScope(scopeFile: File): ExecutionScope {
        requireRegularFile(scopeFile, "execution scope")
        val raw = try {
            readBoundedUtf8RegularFile(
                scopeFile,
                EXECUTION_SCOPE_FILE,
                maxUtf8Bytes = CONTEXT_JSON_MAX_UTF8_BYTES,
            ).text
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid execution scope: ${e.message}", e)
        }
        return readExecutionScopeJson(raw)
    }

    private fun readExecutionScopeJson(raw: String): ExecutionScope {
        val obj = try {
            com.hayden.testgraphsdk.exec.parseBoundedJsonObject(raw, EXECUTION_SCOPE_FILE)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid execution scope: ${e.message}", e)
        }
        return readExecutionScopeObject(obj, "invalid execution scope")
    }

    private fun readExecutionScopeObject(
        obj: Map<String, Any?>,
        label: String,
    ): ExecutionScope {
        require(obj["version"] == EXECUTION_SCOPE_VERSION.toLong()) {
            "$label: unsupported version"
        }
        val graphName = obj["graphName"] as? String
            ?: throw IllegalArgumentException(
                "$label: graphName must be a string"
            )
        val mode = obj["mode"] as? String
            ?: throw IllegalArgumentException("$label: mode must be a string")
        val rawExpected = obj["expectedNodeIds"] as? List<*>
            ?: throw IllegalArgumentException(
                "$label: expectedNodeIds must be an array"
            )
        require(rawExpected.size <= MAX_ENVELOPE_FILES) {
            "$label: expected node count exceeds the absolute report limit"
        }
        require(rawExpected.all { it is String }) {
            "$label: expectedNodeIds entries must be strings"
        }
        val expected = rawExpected.map { it as String }
        val replay = when (mode) {
            "full" -> {
                require(obj.keys == FULL_SCOPE_KEYS) {
                    "$label: full scope contains unknown or missing fields"
                }
                null
            }
            ReplayMode.RESUME_FROM_NODE.wireName,
            ReplayMode.RUN_ONLY_NODE.wireName -> {
                require(obj.keys == REPLAY_SCOPE_KEYS) {
                    "$label: replay scope contains unknown or missing fields"
                }
                val selectedNodeId = obj["selectedNodeId"] as? String
                    ?: throw IllegalArgumentException(
                        "$label: selectedNodeId must be a string"
                    )
                val sourceBuild = obj["sourceBuild"] as? String
                    ?: throw IllegalArgumentException(
                        "$label: sourceBuild must be a string"
                    )
                val sourceClosureSha256 = obj["sourceClosureSha256"] as? String
                    ?: throw IllegalArgumentException(
                        "$label: sourceClosureSha256 must be a string"
                    )
                val sourceContextSha256 = obj["sourceContextSha256"] as? String
                    ?: throw IllegalArgumentException(
                        "$label: sourceContextSha256 must be a string"
                    )
                require(sourceBuild.isNotBlank()) {
                    "$label: sourceBuild must not be blank"
                }
                ReplayMetadata(
                    mode = if (mode == ReplayMode.RESUME_FROM_NODE.wireName) {
                        ReplayMode.RESUME_FROM_NODE
                    } else {
                        ReplayMode.RUN_ONLY_NODE
                    },
                    selectedNodeId = selectedNodeId,
                    sourceBuild = File(sourceBuild),
                    sourceClosureSha256 = sourceClosureSha256,
                    sourceContextSha256 = sourceContextSha256,
                )
            }
            else -> throw IllegalArgumentException("$label: unsupported mode '$mode'")
        }
        return validatedExecutionScope(graphName, expected, replay)
    }

    private fun executionScopeJson(scope: ExecutionScope): String = buildString {
        append('{')
        append("\"version\":").append(EXECUTION_SCOPE_VERSION)
        append(",\"graphName\":").append(jsonString(scope.graphName!!))
        append(",\"mode\":").append(
            jsonString(scope.replay?.mode?.wireName ?: "full")
        )
        append(",\"expectedNodeIds\":")
        appendJsonStringArray(this, scope.expectedNodeIds)
        scope.replay?.let {
            append(",\"selectedNodeId\":").append(jsonString(it.selectedNodeId))
            append(",\"sourceBuild\":").append(jsonString(it.sourceBuild.absolutePath))
            append(",\"sourceClosureSha256\":")
                .append(jsonString(it.sourceClosureSha256))
            append(",\"sourceContextSha256\":")
                .append(jsonString(it.sourceContextSha256))
        }
        append("}\n")
    }

    fun persistAttemptClosure(
        runDir: File,
        graphName: String,
        expectedNodeIds: List<String>,
        traceId: String,
        replay: ReplayMetadata? = null,
        finalizerNodeIds: Set<String> = emptySet(),
    ) {
        requireRegularDirectory(runDir, "attempt closure directory")
        require(isValidTraceId(traceId)) {
            "attempt closure trace id must be a valid non-zero 32-character lowercase hexadecimal id"
        }
        val scopeFile = File(runDir, EXECUTION_SCOPE_FILE)
        val capturedScope = readBoundedUtf8RegularFile(
            scopeFile,
            "attempt closure execution scope",
        )
        val persistedScope = readExecutionScopeJson(capturedScope.text)
        val requestedScope = validatedExecutionScope(graphName, expectedNodeIds, replay)
        require(persistedScope == requestedScope) {
            "attempt closure scope does not match the persisted execution scope"
        }
        require(finalizerNodeIds.all(::isValidNodeId)) {
            "attempt closure finalizer node ids must match [a-z0-9._-]{1,128}"
        }
        require(finalizerNodeIds.all(expectedNodeIds::contains)) {
            "attempt closure finalizer node ids must belong to the execution scope"
        }
        val orderedFinalizerNodeIds = expectedNodeIds.filter(finalizerNodeIds::contains)
        val capturedCarrier = readBoundedUtf8RegularFile(
            File(runDir, TRACE_CARRIER_FILE),
            "attempt closure trace carrier",
            GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES,
        )
        val carrier = GraphObservability.parseCarrierJson(capturedCarrier.text)
        require(GraphObservability.traceIdForCarrier(carrier) == traceId) {
            "attempt closure trace does not match the persisted trace carrier"
        }
        val evidence = captureAndValidateClosedEvidence(
            runDir = runDir,
            scope = persistedScope,
            traceId = traceId,
            finalizerNodeIds = orderedFinalizerNodeIds.toSet(),
            aggregateBudget = AggregateJsonStructureBudget(
                MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS
            ),
        )
        val encoded = attemptClosureJson(
            AttemptClosure(
                runId = runDir.name,
                traceId = traceId,
                scope = persistedScope,
                finalizerNodeIds = orderedFinalizerNodeIds,
                scopeSha256 = capturedScope.sha256,
                carrierSha256 = capturedCarrier.sha256,
                contextSha256 = evidence.contextSha256,
                envelopeSha256 = evidence.envelopeSha256,
            )
        ).toByteArray(Charsets.UTF_8)
        require(encoded.size <= CONTEXT_JSON_MAX_UTF8_BYTES) {
            "attempt closure exceeds $CONTEXT_JSON_MAX_UTF8_BYTES UTF-8 bytes"
        }
        publishImmutableEvidence(
            File(runDir, ATTEMPT_CLOSURE_FILE).toPath(),
            encoded,
            "attempt closure",
        )
    }

    private fun attemptClosureJson(closure: AttemptClosure): String = buildString {
        append('{')
        append("\"version\":").append(ATTEMPT_CLOSURE_VERSION)
        append(",\"runId\":").append(jsonString(closure.runId))
        append(",\"traceId\":").append(jsonString(closure.traceId))
        append(",\"scope\":").append(executionScopeJson(closure.scope).trim())
        append(",\"finalizerNodeIds\":")
        appendJsonStringArray(this, closure.finalizerNodeIds)
        append(",\"scopeSha256\":").append(jsonString(closure.scopeSha256))
        append(",\"carrierSha256\":").append(jsonString(closure.carrierSha256))
        append(",\"contextSha256\":")
        appendJsonStringMap(this, closure.contextSha256)
        append(",\"envelopeSha256\":")
        appendJsonStringMap(this, closure.envelopeSha256)
        append("}\n")
    }

    private fun readAttemptClosure(raw: String): AttemptClosure {
        val obj = try {
            com.hayden.testgraphsdk.exec.parseBoundedJsonObject(raw, ATTEMPT_CLOSURE_FILE)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid attempt closure: ${e.message}", e)
        }
        val version = obj["version"]
        require(version == 2L || version == ATTEMPT_CLOSURE_VERSION.toLong()) {
            "invalid attempt closure: unsupported version"
        }
        val expectedKeys = if (version == 2L) {
            ATTEMPT_CLOSURE_V2_KEYS
        } else {
            ATTEMPT_CLOSURE_KEYS
        }
        require(obj.keys == expectedKeys) {
            "invalid attempt closure: unknown or missing fields"
        }
        val runId = obj["runId"] as? String
            ?: throw IllegalArgumentException("invalid attempt closure: runId must be a string")
        val traceId = obj["traceId"] as? String
            ?: throw IllegalArgumentException("invalid attempt closure: traceId must be a string")
        require(isValidTraceId(traceId)) {
            "invalid attempt closure: traceId is not a valid graph trace id"
        }
        @Suppress("UNCHECKED_CAST")
        val rawScope = obj["scope"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("invalid attempt closure: scope must be an object")
        val scope = readExecutionScopeObject(rawScope, "invalid attempt closure scope")
        val finalizerNodeIds = if (version == 2L) {
            emptyList()
        } else {
            val rawFinalizers = obj["finalizerNodeIds"] as? List<*>
                ?: throw IllegalArgumentException(
                    "invalid attempt closure: finalizerNodeIds must be an array"
                )
            require(rawFinalizers.all { it is String }) {
                "invalid attempt closure: finalizerNodeIds entries must be strings"
            }
            val parsedFinalizers = rawFinalizers.map { it as String }
            require(parsedFinalizers.distinct().size == parsedFinalizers.size) {
                "invalid attempt closure: finalizerNodeIds entries must be unique"
            }
            require(parsedFinalizers.all(::isValidNodeId)) {
                "invalid attempt closure: finalizerNodeIds entries are invalid"
            }
            require(parsedFinalizers.all(scope.expectedNodeIds::contains)) {
                "invalid attempt closure: finalizerNodeIds must belong to the execution scope"
            }
            require(
                parsedFinalizers == scope.expectedNodeIds.filter(parsedFinalizers::contains)
            ) {
                "invalid attempt closure: finalizerNodeIds must follow execution-scope order"
            }
            parsedFinalizers
        }
        return AttemptClosure(
            runId = runId,
            traceId = traceId,
            scope = scope,
            finalizerNodeIds = finalizerNodeIds,
            scopeSha256 = requireSha256(obj["scopeSha256"], "scopeSha256"),
            carrierSha256 = requireSha256(obj["carrierSha256"], "carrierSha256"),
            contextSha256 = readEvidenceDigestMap(
                obj["contextSha256"],
                CONTEXT_DIRECTORY,
                INPUT_CONTEXT_SUFFIX,
                scope.expectedNodeIds,
                MAX_CONTEXT_SNAPSHOT_FILES,
            ),
            envelopeSha256 = readEvidenceDigestMap(
                obj["envelopeSha256"],
                ENVELOPE_DIRECTORY,
                ENVELOPE_SUFFIX,
                scope.expectedNodeIds,
                MAX_ENVELOPE_FILES,
            ),
        )
    }

    private fun currentClosureIntegrityError(
        runDir: File,
        expectedScope: ExecutionScope,
        expectedTraceId: String?,
    ): String? = try {
        require(!expectedScope.legacyUnknown) {
            "attempt closure cannot be verified without a persisted execution scope"
        }
        val capturedClosure = readBoundedUtf8RegularFile(
            File(runDir, ATTEMPT_CLOSURE_FILE),
            "attempt closure",
        )
        val closure = readAttemptClosure(capturedClosure.text)
        require(closure.runId == runDir.name) {
            "attempt closure run id does not match its directory"
        }
        require(closure.scope == expectedScope) {
            "attempt closure scope does not match the current execution scope"
        }
        val capturedScope = readBoundedUtf8RegularFile(
            File(runDir, EXECUTION_SCOPE_FILE),
            "attempt closure execution scope",
        )
        require(closure.scopeSha256 == capturedScope.sha256) {
            "execution scope digest does not match the attempt closure"
        }
        require(readExecutionScopeJson(capturedScope.text) == expectedScope) {
            "current execution scope does not match the report scope"
        }
        val capturedCarrier = readBoundedUtf8RegularFile(
            File(runDir, TRACE_CARRIER_FILE),
            "attempt closure trace carrier",
            GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES,
        )
        require(closure.carrierSha256 == capturedCarrier.sha256) {
            "trace carrier digest does not match the attempt closure"
        }
        val carrier = GraphObservability.parseCarrierJson(capturedCarrier.text)
        val carrierTraceId = GraphObservability.traceIdForCarrier(carrier)
        require(closure.traceId == carrierTraceId) {
            "attempt closure trace does not match the current trace carrier"
        }
        require(expectedTraceId == null || closure.traceId == expectedTraceId) {
            "attempt closure trace does not match the report trace"
        }
        val evidence = captureAndValidateClosedEvidence(
            runDir = runDir,
            scope = expectedScope,
            traceId = closure.traceId,
            finalizerNodeIds = closure.finalizerNodeIds.toSet(),
            aggregateBudget = AggregateJsonStructureBudget(
                MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS
            ),
        )
        require(evidence.contextSha256 == closure.contextSha256) {
            "context file set or digest does not match the attempt closure"
        }
        require(evidence.envelopeSha256 == closure.envelopeSha256) {
            "envelope file set or digest does not match the attempt closure"
        }
        null
    } catch (e: Exception) {
        (e.message ?: e.javaClass.simpleName).take(MAX_FAILURE_MESSAGE_CHARS)
    }

    fun requireReplaySource(
        sourceDir: File,
        graphName: String,
        selectedNodeId: String,
    ): ReplaySourceSnapshot {
        require(isValidNodeId(selectedNodeId)) {
            "replay selected node id must match [a-z0-9._-]{1,128}"
        }
        requireRegularDirectory(sourceDir, "replay source")
        val capturedClosure = readBoundedUtf8RegularFile(
            File(sourceDir, ATTEMPT_CLOSURE_FILE),
            "replay source attempt closure",
        )
        val closure = readAttemptClosure(capturedClosure.text)
        val sourceScopeFile = File(sourceDir, EXECUTION_SCOPE_FILE)
        val capturedScope = readBoundedUtf8RegularFile(
            sourceScopeFile,
            "replay source execution scope (legacy or arbitrary replay sources are not accepted)",
        )
        val sourceScope = readExecutionScopeJson(capturedScope.text)
        require(sourceScope.graphName == graphName) {
            "replay source graph '${sourceScope.graphName}' does not match requested graph '$graphName'"
        }
        require(sourceScope.replay == null) {
            "replay source must be a full graph attempt, not another replay attempt"
        }
        require(selectedNodeId in sourceScope.expectedNodeIds) {
            "replay source scope does not contain selected node '$selectedNodeId'"
        }
        requireReplaySourceDistinctFromTarget(sourceDir, sourceScope.replay)
        require(closure.runId == sourceDir.name) {
            "replay source attempt closure run id does not match its directory"
        }
        require(closure.scope == sourceScope) {
            "replay source attempt closure does not match its execution scope"
        }
        require(closure.scopeSha256 == capturedScope.sha256) {
            "replay source execution scope digest does not match its attempt closure"
        }
        val capturedCarrier = readBoundedUtf8RegularFile(
            File(sourceDir, TRACE_CARRIER_FILE),
            "replay source trace carrier",
            GraphObservability.TRACE_CARRIER_MAX_UTF8_BYTES,
        )
        require(closure.carrierSha256 == capturedCarrier.sha256) {
            "replay source trace carrier digest does not match its attempt closure"
        }
        val carrier = GraphObservability.parseCarrierJson(capturedCarrier.text)
        val capturedTraceId = GraphObservability.traceIdForCarrier(
            carrier,
            "replay source trace carrier",
        )
        require(closure.traceId == capturedTraceId) {
            "replay source attempt closure does not match its trace carrier"
        }
        val evidence = captureAndValidateClosedEvidence(
            runDir = sourceDir,
            scope = sourceScope,
            traceId = capturedTraceId,
            selectedNodeId = selectedNodeId,
            finalizerNodeIds = closure.finalizerNodeIds.toSet(),
            aggregateBudget = AggregateJsonStructureBudget(
                MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS
            ),
        )
        require(evidence.contextSha256 == closure.contextSha256) {
            "replay source context file set or digest does not match its attempt closure"
        }
        require(evidence.envelopeSha256 == closure.envelopeSha256) {
            "replay source envelope file set or digest does not match its attempt closure"
        }
        val selectedContextJson = evidence.selectedContextJson
            ?: throw IllegalArgumentException(
                "replay source closure does not contain selected context for '$selectedNodeId'"
            )
        val selectedContext = requireNotNull(evidence.selectedContext)
        return ReplaySourceSnapshot.immutable(
            sourceBuild = sourceDir.canonicalFile,
            graphName = graphName,
            selectedNodeId = selectedNodeId,
            sourceExpectedNodeIds = sourceScope.expectedNodeIds,
            traceId = capturedTraceId,
            carrierJson = capturedCarrier.text,
            carrier = carrier,
            selectedContextJson = selectedContextJson,
            selectedContext = selectedContext,
            closureSha256 = capturedClosure.sha256,
            selectedContextSha256 = evidence.contextSha256.getValue(
                "$CONTEXT_DIRECTORY/$selectedNodeId$INPUT_CONTEXT_SUFFIX"
            ),
        )
    }

    /**
     * Capture, digest, and semantically validate one closed attempt without
     * retaining every raw document or parse tree. Each context/envelope pair is
     * consumed once, while the returned value keeps only digest maps and the
     * selected replay context. A second name scan rejects add/remove races.
     */
    private fun captureAndValidateClosedEvidence(
        runDir: File,
        scope: ExecutionScope,
        traceId: String,
        selectedNodeId: String? = null,
        finalizerNodeIds: Set<String> = emptySet(),
        aggregateBudget: AggregateJsonStructureBudget,
    ): ClosedEvidenceSnapshot {
        val contextDirectory = runDir.toPath().resolve(CONTEXT_DIRECTORY)
        val envelopeDirectory = runDir.toPath().resolve(ENVELOPE_DIRECTORY)
        val contextEntries = indexEvidenceEntries(
            contextDirectory,
            CONTEXT_DIRECTORY,
            INPUT_CONTEXT_SUFFIX,
            scope.expectedNodeIds,
            MAX_CONTEXT_SNAPSHOT_FILES,
        )
        val envelopeEntries = indexEvidenceEntries(
            envelopeDirectory,
            ENVELOPE_DIRECTORY,
            ENVELOPE_SUFFIX,
            scope.expectedNodeIds,
            MAX_ENVELOPE_FILES,
        )
        val contextNodes = scope.expectedNodeIds.filter(contextEntries::containsKey)
        val envelopeNodes = scope.expectedNodeIds.filter(envelopeEntries::containsKey)
        require(contextNodes == envelopeNodes) {
            "closed attempt must have one context snapshot for every envelope"
        }
        require(contextNodes == scope.expectedNodeIds.take(contextNodes.size)) {
            "closed attempt context/envelope evidence must be an exact execution prefix"
        }
        require(contextNodes.size == contextEntries.size && envelopeNodes.size == envelopeEntries.size) {
            "closed attempt evidence contains duplicate or non-canonical paths"
        }

        val contextDigests = sortedMapOf<String, String>()
        val envelopeDigests = sortedMapOf<String, String>()
        val expectedContext = mutableListOf<ContextItem>()
        var selectedContextJson: String? = null
        var selectedContext: List<ContextItem>? = null
        var aggregateContextBytes = 0L
        var aggregateEnvelopeBytes = 0L
        var observedNonPassingEnvelope = false

        for ((index, nodeId) in contextNodes.withIndex()) {
            val contextRelative = "$CONTEXT_DIRECTORY/$nodeId$INPUT_CONTEXT_SUFFIX"
            val capturedContext = readBoundedUtf8RegularFile(
                contextEntries.getValue(nodeId).toFile(),
                "attempt evidence '$contextRelative'",
            )
            require(
                aggregateContextBytes <= MAX_AGGREGATE_CONTEXT_BYTES - capturedContext.size
            ) {
                "attempt context evidence exceeds $MAX_AGGREGATE_CONTEXT_BYTES bytes"
            }
            aggregateContextBytes += capturedContext.size
            val contextRoot = parseBoundedJsonObject(
                capturedContext.text,
                "attempt evidence '$contextRelative'",
                aggregateBudget,
            )
            val actualContext = ContextSerde.fromParsed(contextRoot)
            if (index == 0 && scope.replay != null) {
                require(capturedContext.sha256 == scope.replay.sourceContextSha256) {
                    "replay attempt's first context does not match its bound source context digest"
                }
                expectedContext += actualContext
            }
            require(actualContext == expectedContext) {
                "closed attempt context for '$nodeId' is not its exact ordered published-data prefix"
            }
            contextDigests[contextRelative] = capturedContext.sha256
            if (nodeId == selectedNodeId) {
                selectedContextJson = capturedContext.text
                selectedContext = actualContext.toList()
            }

            val envelopeRelative = "$ENVELOPE_DIRECTORY/$nodeId$ENVELOPE_SUFFIX"
            val capturedEnvelope = readBoundedUtf8RegularFile(
                envelopeEntries.getValue(nodeId).toFile(),
                "attempt evidence '$envelopeRelative'",
            )
            require(
                aggregateEnvelopeBytes <= MAX_AGGREGATE_ENVELOPE_BYTES - capturedEnvelope.size
            ) {
                "attempt envelope evidence exceeds $MAX_AGGREGATE_ENVELOPE_BYTES bytes"
            }
            aggregateEnvelopeBytes += capturedEnvelope.size
            val envelope = CanonicalEnvelopeValidator.validate(
                capturedEnvelope.text,
                "closed attempt envelope '$nodeId'",
                expectedNodeId = nodeId,
                expectedTraceId = traceId,
                aggregateBudget = aggregateBudget,
            )
            if (envelope.status == "passed") {
                require(!observedNonPassingEnvelope || nodeId in finalizerNodeIds) {
                    "closed attempt envelope '$nodeId' passed after a non-passing " +
                            "ordinary node but is not a finalizer"
                }
            } else {
                require(
                    !observedNonPassingEnvelope ||
                            envelope.status == "skipped" ||
                            nodeId in finalizerNodeIds
                ) {
                    "closed attempt envelope '$nodeId' is a second non-passing " +
                            "ordinary node but was not skipped"
                }
                observedNonPassingEnvelope = true
            }
            envelopeDigests[envelopeRelative] = capturedEnvelope.sha256
            if (envelope.status != "skipped") {
                expectedContext += ContextItem(nodeId, envelope.published)
            }
        }

        requireEvidenceNamesUnchanged(contextDirectory, contextEntries, MAX_CONTEXT_SNAPSHOT_FILES)
        requireEvidenceNamesUnchanged(envelopeDirectory, envelopeEntries, MAX_ENVELOPE_FILES)
        if (selectedNodeId != null) {
            require(selectedContextJson != null && selectedContext != null) {
                "closed attempt does not contain selected context for '$selectedNodeId'"
            }
        }
        return ClosedEvidenceSnapshot(
            contextSha256 = LinkedHashMap(contextDigests),
            envelopeSha256 = LinkedHashMap(envelopeDigests),
            selectedContextJson = selectedContextJson,
            selectedContext = selectedContext,
        )
    }

    private fun indexEvidenceEntries(
        directory: java.nio.file.Path,
        directoryName: String,
        suffix: String,
        expectedNodeIds: List<String>,
        maxFiles: Int,
    ): Map<String, java.nio.file.Path> {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "attempt evidence directory must be real, not a symlink: $directory"
        }
        val expected = expectedNodeIds.toSet()
        val indexed = linkedMapOf<String, java.nio.file.Path>()
        for (entry in listEvidenceEntries(directory, maxFiles)) {
            val name = entry.fileName.toString()
            require(name.endsWith(suffix)) {
                "unexpected attempt evidence entry '$directoryName/$name'"
            }
            val nodeId = name.removeSuffix(suffix)
            require(isValidNodeId(nodeId) && nodeId in expected) {
                "attempt evidence entry '$directoryName/$name' is outside the execution scope"
            }
            require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                "attempt evidence entry must be a regular file, not a symlink: $entry"
            }
            require(indexed.put(nodeId, entry) == null) {
                "duplicate attempt evidence for node '$nodeId'"
            }
        }
        return indexed
    }

    private fun requireEvidenceNamesUnchanged(
        directory: java.nio.file.Path,
        initial: Map<String, java.nio.file.Path>,
        maxFiles: Int,
    ) {
        if (initial.isEmpty() && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "attempt evidence directory changed while it was being captured: $directory"
        }
        val finalNames = listEvidenceEntries(directory, maxFiles).map { it.fileName.toString() }
        val initialNames = initial.values.map { it.fileName.toString() }.sorted()
        require(initialNames == finalNames) {
            "attempt evidence directory '$directory' changed while it was being captured"
        }
    }

    private fun listEvidenceEntries(
        directory: java.nio.file.Path,
        maxFiles: Int,
    ): List<java.nio.file.Path> {
        val entries = ArrayList<java.nio.file.Path>(maxFiles.coerceAtMost(1_024) + 1)
        Files.newDirectoryStream(directory).use { stream ->
            for (entry in stream) {
                entries.add(entry)
                require(entries.size <= maxFiles) {
                    "replay evidence directory '$directory' exceeds $maxFiles files"
                }
            }
        }
        entries.sortBy { it.fileName.toString() }
        return entries
    }

    private fun requireSha256(raw: Any?, field: String): String {
        val value = raw as? String
            ?: throw IllegalArgumentException("invalid attempt closure: $field must be a string")
        require(SHA256.matches(value)) {
            "invalid attempt closure: $field must be lowercase SHA-256"
        }
        return value
    }

    private fun readEvidenceDigestMap(
        raw: Any?,
        directoryName: String,
        suffix: String,
        expectedNodeIds: List<String>,
        maxFiles: Int,
    ): Map<String, String> {
        val map = raw as? Map<*, *>
            ?: throw IllegalArgumentException(
                "invalid attempt closure: ${directoryName}Sha256 must be an object"
            )
        require(map.size <= maxFiles) {
            "invalid attempt closure: ${directoryName}Sha256 exceeds $maxFiles entries"
        }
        val expected = expectedNodeIds.toSet()
        val normalized = sortedMapOf<String, String>()
        for ((rawPath, rawDigest) in map) {
            val path = rawPath as? String
                ?: throw IllegalArgumentException(
                    "invalid attempt closure: evidence paths must be strings"
                )
            val prefix = "$directoryName/"
            require(path.startsWith(prefix) && path.endsWith(suffix)) {
                "invalid attempt closure evidence path '$path'"
            }
            val nodeId = path.removePrefix(prefix).removeSuffix(suffix)
            require(isValidNodeId(nodeId) && nodeId in expected) {
                "invalid attempt closure evidence path '$path' is outside its scope"
            }
            normalized[path] = requireSha256(rawDigest, path)
        }
        return LinkedHashMap(normalized)
    }

    private fun requireValidGraphName(graphName: String) {
        require(
            graphName.isNotBlank() && graphName.length <= MAX_GRAPH_NAME_CHARS &&
                    graphName.none(Char::isISOControl)
        ) {
            "graphName must be non-blank, contain no control characters, and be at most " +
                    "$MAX_GRAPH_NAME_CHARS characters"
        }
    }

    private fun requireReplaySourceDistinctFromTarget(
        runDir: File,
        replay: ReplayMetadata?,
    ) {
        if (replay == null) return
        val target = normalizedAbsoluteFile(runDir)
        val source = normalizedAbsoluteFile(replay.sourceBuild)
        require(target != source) {
            "replay source build must be distinct from the target run directory"
        }
    }

    /** Canonicalize the path so filesystem aliases cannot identify the target as its own source. */
    private fun normalizedAbsoluteFile(file: File): File =
        file.canonicalFile

    private fun requireRegularDirectory(directory: File, label: String) {
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "$label must be a real directory, not a symlink: ${directory.absolutePath}"
        }
    }

    private fun requireRegularFile(file: File, label: String) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "$label must be a regular file, not a symlink: ${file.absolutePath}"
        }
    }

    private fun writeAndForce(path: java.nio.file.Path, encoded: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(encoded)
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun writeDerivedViewAtomically(runDir: File, fileName: String, content: String) {
        val target = runDir.toPath().resolve(fileName)
        val temp = Files.createTempFile(runDir.toPath(), ".$fileName-", ".tmp")
        try {
            writeAndForce(temp, content.toByteArray(Charsets.UTF_8))
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                throw IllegalStateException(
                    "atomic replacement is required for derived report '$fileName'",
                    e,
                )
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun provisionalSummary(
        finalSummary: String,
        finalStatus: String,
        finalComplete: Boolean,
    ): String {
        val statusField = "\"status\":" + jsonString(finalStatus)
        val completeField = "\"complete\":$finalComplete"
        check(finalSummary.contains(statusField)) {
            "generated summary is missing its final status field"
        }
        check(finalSummary.contains(completeField)) {
            "generated summary is missing its final completeness field"
        }
        return finalSummary
            .replaceFirst(statusField, "\"status\":\"errored\"")
            .replaceFirst(
                completeField,
                "\"complete\":false,\"reportPublicationComplete\":false",
            )
    }

    /**
     * Render {@code <runDir>/summary.json} + {@code <runDir>/report.md}
     * from the envelopes already on disk under {@code <runDir>/envelope/}.
     * Idempotent — re-running atomically replaces both derived files. A
     * scope-bearing run with no envelopes is rendered as incomplete; only a
     * legacy directory with no plan, envelopes, or execution failure is a no-op.
     *
     * @return the rendered status and integrity decision, or an unwritten
     *         outcome for a legacy directory with neither scope nor envelopes.
     */
    fun writeRunReport(
        runDir: File,
        graphName: String? = null,
        expectedNodeIds: List<String> = emptyList(),
        executionFailure: Throwable? = null,
        expectedTraceId: String? = null,
        replay: ReplayMetadata? = null,
        replaySourceSnapshot: ReplaySourceSnapshot? = null,
        maxAggregateStructuralTokens: Int = MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS,
    ): Outcome {
        require(maxAggregateStructuralTokens in 1..MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS) {
            "aggregate report structural-token limit must be between 1 and " +
                    MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS
        }
        requireRegularDirectory(runDir, "run report directory")
        val executionScope = resolveExecutionScope(runDir, graphName, expectedNodeIds, replay)
        val effectiveExpectedNodeIds = executionScope.expectedNodeIds
        val effectiveReplay = executionScope.replay
        val persistedTraceId = if (
            File(runDir, EXECUTION_SCOPE_FILE).isFile
        ) {
            GraphObservability.persistedTraceId(runDir)
        } else {
            null
        }
        require(
            expectedTraceId == null || persistedTraceId == null || expectedTraceId == persistedTraceId
        ) {
            "in-memory graph trace does not match the persisted trace carrier"
        }
        val effectiveExpectedTraceId = expectedTraceId ?: persistedTraceId
        require(effectiveExpectedNodeIds.size <= MAX_ENVELOPE_FILES) {
            "expected node count exceeds the absolute report limit of $MAX_ENVELOPE_FILES"
        }
        require(effectiveExpectedNodeIds.all(::isValidNodeId)) {
            "expected node ids must match [a-z0-9._-]{1,128}"
        }
        require(effectiveExpectedNodeIds.distinct().size == effectiveExpectedNodeIds.size) {
            "expected node ids must be unique"
        }
        require(effectiveExpectedTraceId == null || isValidTraceId(effectiveExpectedTraceId)) {
            "expected trace id must be a valid non-zero 32-character lowercase hexadecimal id"
        }
        require(effectiveReplay == null || isValidNodeId(effectiveReplay.selectedNodeId)) {
            "replay selected node id must match [a-z0-9._-]{1,128}"
        }
        require(effectiveReplay == null || effectiveReplay.selectedNodeId in effectiveExpectedNodeIds) {
            "replay selected node id must be in the expected execution scope"
        }
        replaySourceSnapshot?.let { snapshot ->
            val metadata = effectiveReplay ?: throw IllegalArgumentException(
                "a captured replay source requires replay execution metadata"
            )
            requireReplaySnapshotMatchesMetadata(snapshot, metadata, executionScope.graphName)
        }
        val envelopeDir = File(runDir, "envelope")
        val envelopePath = envelopeDir.toPath()
        if (Files.exists(envelopePath, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(envelopePath, LinkOption.NOFOLLOW_LINKS)) {
                "envelope path must be a real directory, not a symlink: ${envelopeDir.absolutePath}"
            }
        }
        if (!Files.isDirectory(envelopePath, LinkOption.NOFOLLOW_LINKS) &&
            effectiveExpectedNodeIds.isEmpty() && executionFailure == null
        ) {
            return Outcome(written = false, status = null, complete = false)
        }
        val envelopeFileLimit = envelopeFileLimit(effectiveExpectedNodeIds.size)
        val envelopeScan = scanEnvelopeFiles(envelopeDir, envelopeFileLimit)
        val envelopeFiles = envelopeScan.files
        val reportStructureBudget = AggregateJsonStructureBudget(maxAggregateStructuralTokens)
        var retainedEnvelopeBytes = 0L
        var aggregateEnvelopeBytesExceeded = false
        for (file in envelopeFiles) {
            val fileBytes = file.length()
            if (fileBytes > CONTEXT_JSON_MAX_UTF8_BYTES) continue
            if (retainedEnvelopeBytes > MAX_AGGREGATE_ENVELOPE_BYTES - fileBytes) {
                aggregateEnvelopeBytesExceeded = true
                break
            }
            retainedEnvelopeBytes += fileBytes
        }
        val invalidEnvelopeFiles = linkedSetOf<String>()
        val identityParsed = mutableListOf<Triple<File, String, Map<String, Any?>>>()
        if (aggregateEnvelopeBytesExceeded) {
            invalidEnvelopeFiles += envelopeFiles.map { it.name }
        } else {
            var capturedEnvelopeBytes = 0L
            for (file in envelopeFiles) {
                val captured = try {
                    readBoundedUtf8RegularFile(
                        file,
                        "envelope/${file.name}",
                    )
                } catch (_: Exception) {
                    null
                }
                if (captured == null) {
                    invalidEnvelopeFiles += file.name
                    continue
                }
                if (
                    capturedEnvelopeBytes >
                    MAX_AGGREGATE_ENVELOPE_BYTES - captured.size
                ) {
                    aggregateEnvelopeBytesExceeded = true
                    invalidEnvelopeFiles += envelopeFiles.map { it.name }
                    identityParsed.clear()
                    break
                }
                capturedEnvelopeBytes += captured.size
                val obj = try {
                    parseBoundedJsonObject(
                        captured.text,
                        "envelope/${file.name}",
                        reportStructureBudget,
                    )
                } catch (_: Exception) {
                    null
                }
                if (obj == null) {
                    invalidEnvelopeFiles += file.name
                    continue
                }
                val nodeId = obj["nodeId"] as? String
                if (
                    nodeId == null || obj["status"] !is String ||
                    !isValidNodeId(nodeId) ||
                    file.name != "$nodeId.json"
                ) {
                    invalidEnvelopeFiles += file.name
                    continue
                }
                identityParsed += Triple(file, captured.text, obj)
            }
        }
        val parsed = identityParsed.mapNotNull { (file, raw, obj) ->
            try {
                val validated = CanonicalEnvelopeValidator.validate(
                    obj,
                    "envelope/${file.name}",
                    expectedNodeId = file.name.removeSuffix(".json"),
                )
                ParsedEnvelope(file, raw, obj, validated)
            } catch (_: Exception) {
                invalidEnvelopeFiles += file.name
                null
            }
        }
        val traceId = effectiveExpectedTraceId ?: identityParsed.firstNotNullOfOrNull { (_, _, envelope) ->
            (envelope["traceId"] as? String)?.takeIf(::isValidTraceId)
        }
        val traceValidationActive = effectiveExpectedTraceId != null ||
                identityParsed.any { (_, _, envelope) -> envelope.containsKey("traceId") }
        val observedNodeIds = parsed.map { it.validated.nodeId }
        val observedNodeIdSet = observedNodeIds.toSet()
        val identifiedNodeIds = identityParsed.map { (_, _, envelope) ->
            envelope["nodeId"] as String
        }
        val duplicateNodeIds = identifiedNodeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.sorted()
        val expectedNodeIdSet = effectiveExpectedNodeIds.toSet()
        val unexpectedNodeIds = if (effectiveExpectedNodeIds.isEmpty()) {
            emptyList()
        } else {
            (identifiedNodeIds.toSet() - expectedNodeIdSet).sorted()
        }
        val unknownStatusNodeIds = identityParsed.mapNotNull { (_, _, envelope) ->
            val status = envelope["status"] as String
            (envelope["nodeId"] as String).takeIf { status !in VALID_NODE_STATUSES }
        }.distinct().sorted()
        val missingTraceNodeIds = if (!traceValidationActive) {
            emptyList()
        } else identityParsed.mapNotNull { (_, _, envelope) ->
            val nodeId = envelope["nodeId"] as String
            val envelopeTraceId = envelope["traceId"]
            nodeId.takeIf {
                !envelope.containsKey("traceId") ||
                        (envelopeTraceId is String && envelopeTraceId.isBlank())
            }
        }.sorted()
        val invalidTraceNodeIds = if (!traceValidationActive) {
            emptyList()
        } else identityParsed.mapNotNull { (_, _, envelope) ->
            val nodeId = envelope["nodeId"] as String
            val envelopeTraceId = envelope["traceId"]
            nodeId.takeIf {
                envelope.containsKey("traceId") &&
                        (envelopeTraceId !is String ||
                                (envelopeTraceId.isNotBlank() && !isValidTraceId(envelopeTraceId)))
            }
        }.sorted()
        val mismatchedTraceNodeIds = if (!traceValidationActive) {
            emptyList()
        } else identityParsed.mapNotNull { (_, _, envelope) ->
            val nodeId = envelope["nodeId"] as String
            val envelopeTraceId = envelope["traceId"] as? String
            nodeId.takeIf {
                !envelopeTraceId.isNullOrBlank() &&
                isValidTraceId(envelopeTraceId) && envelopeTraceId != traceId
            }
        }.sorted()
        val publishedByNodeId = parsed.associate { envelope ->
            envelope.validated.nodeId to envelope.validated.published
        }
        val skippedNodeIds = parsed.asSequence()
            .filter { it.validated.status == "skipped" }
            .mapTo(linkedSetOf()) { it.validated.nodeId }
        val contextIntegrity = inspectContextSnapshots(
            runDir = runDir,
            expectedNodeIds = effectiveExpectedNodeIds,
            publishedByNodeId = publishedByNodeId,
            skippedNodeIds = skippedNodeIds,
            replay = effectiveReplay,
            replaySourceSnapshot = replaySourceSnapshot,
            verifyProvenance = true,
            aggregateBudget = reportStructureBudget,
        )
        // Verify closure *after* capturing the bytes used by this report. If
        // evidence changes between either read, the later closure scan sees
        // the mismatch and the derived view cannot become green from unbound
        // bytes. A change after this point cannot alter the already captured
        // report input.
        val attemptClosureIntegrityError = currentClosureIntegrityError(
            runDir,
            executionScope,
            effectiveExpectedTraceId,
        )
        val integrity = ReportIntegrity(
            expectedNodeIds = effectiveExpectedNodeIds,
            observedNodeIds = observedNodeIds,
            missingNodeIds = effectiveExpectedNodeIds.filterNot(observedNodeIdSet::contains),
            invalidEnvelopeFiles = invalidEnvelopeFiles.sorted(),
            duplicateNodeIds = duplicateNodeIds,
            unexpectedNodeIds = unexpectedNodeIds,
            unknownStatusNodeIds = unknownStatusNodeIds,
            missingTraceNodeIds = missingTraceNodeIds,
            invalidTraceNodeIds = invalidTraceNodeIds,
            mismatchedTraceNodeIds = mismatchedTraceNodeIds,
            observedContextNodeIds = contextIntegrity.observedNodeIds,
            missingContextNodeIds = contextIntegrity.missingNodeIds,
            invalidContextFiles = contextIntegrity.invalidFiles,
            unexpectedContextNodeIds = contextIntegrity.unexpectedNodeIds,
            contextProvenanceViolationNodeIds = contextIntegrity.provenanceViolationNodeIds,
            replaySourceContextMismatchNodeIds = contextIntegrity.replaySourceMismatchNodeIds,
            contextFileCountExceeded = contextIntegrity.fileCountExceeded,
            aggregateContextBytesExceeded = contextIntegrity.aggregateBytesExceeded,
            emptyEvidence = envelopeFiles.isEmpty(),
            envelopeFileCountExceeded = envelopeScan.countExceeded,
            aggregateEnvelopeBytesExceeded = aggregateEnvelopeBytesExceeded,
            aggregateJsonStructureExceeded = reportStructureBudget.exceeded,
            unknownExecutionScope = executionScope.legacyUnknown,
            attemptClosureIntegrityError = attemptClosureIntegrityError,
            executionFailure = executionFailure,
        )

        val statusCounts = mutableMapOf<String, Int>()
        for (envelope in parsed) {
            val status = envelope.validated.status
            statusCounts.merge(status, 1) { a, b -> a + b }
        }
        val overallStatus = when {
            integrity.errored || statusCounts.getOrDefault("errored", 0) > 0 -> "errored"
            statusCounts.getOrDefault("failed", 0) > 0 -> "failed"
            statusCounts.getOrDefault("skipped", 0) > 0 -> "errored"
            else -> "passed"
        }

        // 1. summary.json — machine-readable concatenation.
        val summarySb = StringBuilder()
        summarySb.append('{')
        summarySb.append("\"runId\":").append(jsonString(runDir.name)).append(',')
        summarySb.append("\"status\":").append(jsonString(overallStatus)).append(',')
        if (traceId != null) {
            summarySb.append("\"traceId\":").append(jsonString(traceId)).append(',')
        }
        summarySb.append("\"execution\":{")
        summarySb.append("\"mode\":").append(jsonString(executionScope.modeWireName))
        executionScope.graphName?.let {
            summarySb.append(",\"graphName\":").append(jsonString(it))
        }
        effectiveReplay?.let {
            summarySb.append(",\"selectedNodeId\":").append(jsonString(it.selectedNodeId))
            summarySb.append(",\"sourceBuild\":").append(
                jsonString(it.sourceBuild.absolutePath)
            )
            summarySb.append(",\"sourceClosureSha256\":")
                .append(jsonString(it.sourceClosureSha256))
            summarySb.append(",\"sourceContextSha256\":")
                .append(jsonString(it.sourceContextSha256))
        }
        summarySb.append(",\"complete\":").append(integrity.complete).append(',')
        summarySb.append("\"expectedNodeIds\":")
        appendJsonStringArray(summarySb, integrity.expectedNodeIds)
        summarySb.append(",\"observedNodeIds\":")
        appendJsonStringArray(summarySb, integrity.observedNodeIds)
        summarySb.append(",\"missingNodeIds\":")
        appendJsonStringArray(summarySb, integrity.missingNodeIds)
        summarySb.append(",\"invalidEnvelopeFiles\":")
        appendJsonStringArray(summarySb, integrity.invalidEnvelopeFiles)
        summarySb.append(",\"duplicateNodeIds\":")
        appendJsonStringArray(summarySb, integrity.duplicateNodeIds)
        summarySb.append(",\"unexpectedNodeIds\":")
        appendJsonStringArray(summarySb, integrity.unexpectedNodeIds)
        summarySb.append(",\"unknownStatusNodeIds\":")
        appendJsonStringArray(summarySb, integrity.unknownStatusNodeIds)
        summarySb.append(",\"missingTraceNodeIds\":")
        appendJsonStringArray(summarySb, integrity.missingTraceNodeIds)
        summarySb.append(",\"invalidTraceNodeIds\":")
        appendJsonStringArray(summarySb, integrity.invalidTraceNodeIds)
        summarySb.append(",\"mismatchedTraceNodeIds\":")
        appendJsonStringArray(summarySb, integrity.mismatchedTraceNodeIds)
        summarySb.append(",\"observedContextNodeIds\":")
        appendJsonStringArray(summarySb, integrity.observedContextNodeIds)
        summarySb.append(",\"missingContextNodeIds\":")
        appendJsonStringArray(summarySb, integrity.missingContextNodeIds)
        summarySb.append(",\"invalidContextFiles\":")
        appendJsonStringArray(summarySb, integrity.invalidContextFiles)
        summarySb.append(",\"unexpectedContextNodeIds\":")
        appendJsonStringArray(summarySb, integrity.unexpectedContextNodeIds)
        summarySb.append(",\"contextProvenanceViolationNodeIds\":")
        appendJsonStringArray(summarySb, integrity.contextProvenanceViolationNodeIds)
        summarySb.append(",\"replaySourceContextMismatchNodeIds\":")
        appendJsonStringArray(summarySb, integrity.replaySourceContextMismatchNodeIds)
        summarySb.append(",\"contextFileCountExceeded\":")
            .append(integrity.contextFileCountExceeded)
        summarySb.append(",\"aggregateContextBytesExceeded\":")
            .append(integrity.aggregateContextBytesExceeded)
        summarySb.append(",\"envelopeFileCountExceeded\":")
            .append(integrity.envelopeFileCountExceeded)
        summarySb.append(",\"aggregateEnvelopeBytesExceeded\":")
            .append(integrity.aggregateEnvelopeBytesExceeded)
        summarySb.append(",\"aggregateJsonStructureExceeded\":")
            .append(integrity.aggregateJsonStructureExceeded)
        summarySb.append(",\"unknownExecutionScope\":")
            .append(integrity.unknownExecutionScope)
        integrity.attemptClosureIntegrityError?.let { error ->
            summarySb.append(",\"attemptClosureIntegrityError\":")
                .append(jsonString(error))
        }
        integrity.executionFailure?.let { failure ->
            summarySb.append(",\"failure\":{")
            summarySb.append("\"type\":").append(jsonString(failure.javaClass.name)).append(',')
            summarySb.append("\"message\":").append(
                jsonString((failure.message ?: "").take(MAX_FAILURE_MESSAGE_CHARS))
            )
            summarySb.append('}')
        }
        summarySb.append("},")
        summarySb.append("\"nodes\":[")
        parsed.forEachIndexed { i, envelope ->
            if (i > 0) summarySb.append(',')
            summarySb.append(envelope.raw.trim())
        }
        summarySb.append("]}")
        val finalSummary = summarySb.toString()

        // Publish an explicit fail-closed verdict before touching the human
        // view. This replaces any older green summary during regeneration. If
        // report.md publication or the final summary replacement fails, no
        // complete/passed machine verdict can survive that failed pair update.
        writeDerivedViewAtomically(
            runDir,
            "summary.json",
            provisionalSummary(finalSummary, overallStatus, integrity.complete),
        )

        // 2. report.md — human-friendly per-run report.
        val report = renderReport(
            runId = runDir.name,
            traceId = traceId,
            envelopes = parsed.map { it.file to it.value },
            integrity = integrity,
            overallStatus = overallStatus,
            statusCounts = statusCounts,
            graphName = executionScope.graphName,
            executionMode = executionScope.modeWireName,
            replay = effectiveReplay,
        ).trimEnd() + "\n"
        writeDerivedViewAtomically(runDir, "report.md", report)
        writeDerivedViewAtomically(runDir, "summary.json", finalSummary)
        return Outcome(
            written = true,
            status = overallStatus,
            complete = integrity.complete,
        )
    }

    private fun renderReport(
        runId: String,
        traceId: String?,
        envelopes: List<Pair<File, Map<*, *>>>,
        integrity: ReportIntegrity,
        overallStatus: String,
        statusCounts: Map<String, Int>,
        graphName: String?,
        executionMode: String,
        replay: ReplayMetadata?,
    ): String {
        val sb = StringBuilder()

        // Roll-up counts so the report header tells the story at a glance.
        val total = envelopes.size
        val passed = statusCounts.getOrDefault("passed", 0)
        val failed = statusCounts.getOrDefault("failed", 0)
        val errored = statusCounts.getOrDefault("errored", 0)
        val skipped = statusCounts.getOrDefault("skipped", 0)

        sb.append("# Validation report — ").append(runId).append("\n\n")
        graphName?.let {
            sb.append("**Graph**: `").append(it).append("`\n\n")
        }
        if (traceId != null) {
            sb.append("**Trace ID**: `").append(traceId).append("`\n\n")
        }
        sb.append("**Overall**: ").append(overallStatus.uppercase()).append("\n\n")
        if (executionMode == "legacy-unknown") {
            sb.append("**Execution scope**: legacy/unknown plan (no persisted scope metadata)\n\n")
        } else if (replay == null) {
            sb.append("**Execution scope**: full graph\n\n")
        } else {
            sb.append("**Execution scope**: `").append(replay.mode.wireName)
                .append("` from `").append(replay.selectedNodeId).append("`\n\n")
            sb.append("**Source build**: `")
                .append(replay.sourceBuild.absolutePath)
                .append("`\n\n")
            sb.append("**Source closure SHA-256**: `")
                .append(replay.sourceClosureSha256).append("`\n\n")
            sb.append("**Source context SHA-256**: `")
                .append(replay.sourceContextSha256).append("`\n\n")
        }
        if (integrity.expectedNodeIds.isNotEmpty()) {
            val expectedObserved = integrity.expectedNodeIds.count(integrity.observedNodeIds.toSet()::contains)
            sb.append("**Plan evidence**: ").append(expectedObserved)
                .append('/').append(integrity.expectedNodeIds.size)
                .append(" expected node envelopes observed\n\n")
            val expectedContexts = integrity.expectedNodeIds.count(
                integrity.observedContextNodeIds.toSet()::contains
            )
            sb.append("**Input-context evidence**: ").append(expectedContexts)
                .append('/').append(integrity.expectedNodeIds.size)
                .append(" expected node snapshots observed\n\n")
        }
        if (integrity.missingNodeIds.isNotEmpty()) {
            sb.append("**Missing node envelopes**: ")
                .append(integrity.missingNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        integrity.executionFailure?.let { failure ->
            sb.append("**Execution error**: `").append(failure.javaClass.name).append('`')
            failure.message?.take(MAX_FAILURE_MESSAGE_CHARS)?.takeIf { it.isNotBlank() }?.let {
                sb.append(" — ").append(it.replace("\n", " ").replace("\r", " "))
            }
            sb.append("\n\n")
        }
        if (integrity.invalidEnvelopeFiles.isNotEmpty()) {
            sb.append("**Invalid envelope files**: ")
                .append(integrity.invalidEnvelopeFiles.joinToString(", ") { "`envelope/$it`" })
                .append("\n\n")
        }
        if (integrity.duplicateNodeIds.isNotEmpty()) {
            sb.append("**Duplicate node envelopes**: ")
                .append(integrity.duplicateNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.unexpectedNodeIds.isNotEmpty()) {
            sb.append("**Unexpected node envelopes**: ")
                .append(integrity.unexpectedNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.unknownStatusNodeIds.isNotEmpty()) {
            sb.append("**Unknown node statuses**: ")
                .append(integrity.unknownStatusNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.missingTraceNodeIds.isNotEmpty()) {
            sb.append("**Missing node trace IDs**: ")
                .append(integrity.missingTraceNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.invalidTraceNodeIds.isNotEmpty()) {
            sb.append("**Invalid node trace IDs**: ")
                .append(integrity.invalidTraceNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.mismatchedTraceNodeIds.isNotEmpty()) {
            sb.append("**Mismatched node trace IDs**: ")
                .append(integrity.mismatchedTraceNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.missingContextNodeIds.isNotEmpty()) {
            sb.append("**Missing input-context snapshots**: ")
                .append(integrity.missingContextNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.invalidContextFiles.isNotEmpty()) {
            sb.append("**Invalid context files**: ")
                .append(integrity.invalidContextFiles.joinToString(", ") { "`context/$it`" })
                .append("\n\n")
        }
        if (integrity.unexpectedContextNodeIds.isNotEmpty()) {
            sb.append("**Unexpected input-context snapshots**: ")
                .append(integrity.unexpectedContextNodeIds.joinToString(", ") { "`$it`" })
                .append("\n\n")
        }
        if (integrity.contextProvenanceViolationNodeIds.isNotEmpty()) {
            sb.append("**Input-context provenance violations**: ")
                .append(
                    integrity.contextProvenanceViolationNodeIds.joinToString(", ") { "`$it`" }
                )
                .append("\n\n")
        }
        if (integrity.replaySourceContextMismatchNodeIds.isNotEmpty()) {
            sb.append("**Replay source-context mismatches**: ")
                .append(
                    integrity.replaySourceContextMismatchNodeIds.joinToString(", ") { "`$it`" }
                )
                .append("\n\n")
        }
        if (integrity.contextFileCountExceeded) {
            sb.append("**Context file count**: exceeded the bounded scan limit of ")
                .append(MAX_CONTEXT_SNAPSHOT_FILES)
                .append(" entries\n\n")
        }
        if (integrity.aggregateContextBytesExceeded) {
            sb.append("**Aggregate context bytes**: exceeded ")
                .append(MAX_AGGREGATE_CONTEXT_BYTES)
                .append(" bytes; remaining context parsing was skipped\n\n")
        }
        if (integrity.emptyEvidence) {
            sb.append("**Execution evidence**: no node envelopes were produced\n\n")
        }
        if (integrity.envelopeFileCountExceeded) {
            sb.append("**Envelope file count**: exceeded the bounded scan limit; ")
                .append("only bounded partial evidence was retained\n\n")
        }
        if (integrity.aggregateEnvelopeBytesExceeded) {
            sb.append("**Aggregate envelope bytes**: exceeded ")
                .append(MAX_AGGREGATE_ENVELOPE_BYTES)
                .append(" bytes; envelope parsing was skipped\n\n")
        }
        if (integrity.aggregateJsonStructureExceeded) {
            sb.append("**Aggregate JSON structure**: exceeded the bounded token inventory of ")
                .append(MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS)
                .append("; remaining parsing was rejected\n\n")
        }
        if (integrity.unknownExecutionScope) {
            sb.append("**Execution scope integrity**: no persisted scope metadata; ")
                .append("completeness cannot be proven\n\n")
        }
        integrity.attemptClosureIntegrityError?.let { error ->
            sb.append("**Attempt closure integrity**: ERROR — ")
                .append(error.replace("\n", " ").replace("\r", " "))
                .append("\n\n")
        }
        sb.append("**Nodes**: ").append(total)
        sb.append(" (passed=").append(passed)
        sb.append(", failed=").append(failed)
        sb.append(", errored=").append(errored)
        if (skipped > 0) sb.append(", skipped=").append(skipped)
        sb.append(")\n\n")

        // Plan summary table — quickest scan path: status + duration per node.
        sb.append("| Node | Status | Duration | Input context | Captured stdout |\n")
        sb.append("|---|---|---|---|---|\n")
        for ((_, env) in envelopes) {
            val nodeId = (env["nodeId"] as? String) ?: "?"
            val status = (env["status"] as? String) ?: "?"
            val durationMs = durationFromExecutor(env)
            val durationStr = if (durationMs >= 0) "${durationMs}ms" else "—"
            val inputContextPath = env["inputContextFile"] as? String
            val inputContextCell = if (inputContextPath != null) "[$inputContextPath]($inputContextPath)" else "—"
            val stdoutPath = env["capturedStdoutLog"] as? String
            val stdoutCell = if (stdoutPath != null) "[$stdoutPath]($stdoutPath)" else "—"
            sb.append("| `").append(nodeId).append("` | ").append(badge(status))
              .append(" | ").append(durationStr)
              .append(" | ").append(inputContextCell)
              .append(" | ").append(stdoutCell).append(" |\n")
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
            sb.append(timingLines.joinToString(separator = "\n\n")).append("\n\n")
        }

        val inputContextPath = env["inputContextFile"] as? String
        if (inputContextPath != null) {
            sb.append("**Input context**: [")
              .append(inputContextPath).append("](").append(inputContextPath).append(")\n\n")
        }

        renderRerunGuidance(sb, env["rerunGuidance"])
        renderAssertions(sb, env["assertions"])
        renderMetrics(sb, env["metrics"])
        renderProcesses(sb, env["processes"])
        renderArtifacts(sb, env["artifacts"])
        renderPublished(sb, env["published"])
        renderProvisioningState(sb, env["provisioningState"])
        renderInlineLogs(sb, env["logs"])

        // Captured node-process stdout pointer.
        val stdoutPath = env["capturedStdoutLog"] as? String
        if (stdoutPath != null) {
            sb.append("**Node-process stdout**: [")
              .append(stdoutPath).append("](").append(stdoutPath).append(")\n\n")
        }
        sb.append("---\n\n")
    }

    private fun renderRerunGuidance(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        val resumeGraph = map["resumeGraphCommand"] as? String
        val runOnly = map["runOnlyCommand"] as? String
        if (resumeGraph == null && runOnly == null) return
        sb.append("### Rerun guidance\n\n")
        val inputContext = map["inputContextFile"] as? String
        if (inputContext != null) {
            sb.append("Saved input context: [`")
              .append(inputContext).append("`](").append(inputContext).append(")\n\n")
        }
        if (resumeGraph != null) {
            sb.append("Resume graph:\n\n```bash\n")
              .append(resumeGraph).append("\n```\n\n")
        }
        if (runOnly != null) {
            sb.append("Run only this node:\n\n```bash\n")
              .append(runOnly).append("\n```\n\n")
        }
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
    private fun renderProvisioningState(sb: StringBuilder, raw: Any?) {
        val map = (raw as? Map<*, *>) ?: return
        if (map.isEmpty()) return
        sb.append("### Provisioning state\n\n")
        listOf("environmentId", "branch", "target", "backend").forEach { key ->
            (map[key] as? String)?.let { sb.append("- `").append(key).append("`: `").append(it).append("`\n") }
        }
        (map["actions"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { actions ->
            sb.append("- `actions`: `").append(actions.joinToString(",")).append("`\n")
        }
        listOf("provisionedMarker", "deployedMarker", "resetMarker", "destroyRequestMarker", "destroyedMarker").forEach { key ->
            (map[key] as? String)?.let { path ->
                sb.append("- `").append(key).append("`: [`").append(path).append("`](").append(path).append(")\n")
            }
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

    private fun appendJsonStringArray(sb: StringBuilder, values: List<String>) {
        sb.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            sb.append(jsonString(value))
        }
        sb.append(']')
    }

    private fun appendJsonStringMap(sb: StringBuilder, values: Map<String, String>) {
        sb.append('{')
        values.toSortedMap().entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append(jsonString(key)).append(':').append(jsonString(value))
        }
        sb.append('}')
    }

    internal data class EnvelopeFileScan(
        val files: List<File>,
        val countExceeded: Boolean,
    )

    internal fun envelopeFileLimit(expectedNodeCount: Int): Int {
        require(expectedNodeCount >= 0) { "expected node count must be non-negative" }
        return if (expectedNodeCount == 0) {
            MAX_ENVELOPE_FILES
        } else {
            expectedNodeCount.coerceAtMost(MAX_ENVELOPE_FILES)
        }
    }

    internal fun inspectContextSnapshots(
        runDir: File,
        expectedNodeIds: List<String>,
        maxFiles: Int = MAX_CONTEXT_SNAPSHOT_FILES,
        maxAggregateBytes: Long = MAX_AGGREGATE_CONTEXT_BYTES,
        publishedByNodeId: Map<String, Map<String, String>> = emptyMap(),
        skippedNodeIds: Set<String> = emptySet(),
        replay: ReplayMetadata? = null,
        replaySourceSnapshot: ReplaySourceSnapshot? = null,
        verifyProvenance: Boolean = false,
        aggregateBudget: AggregateJsonStructureBudget = AggregateJsonStructureBudget(
            MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS
        ),
    ): ContextSnapshotIntegrity {
        require(maxFiles in 0..MAX_CONTEXT_SNAPSHOT_FILES) {
            "context snapshot file limit must be between 0 and $MAX_CONTEXT_SNAPSHOT_FILES"
        }
        require(maxAggregateBytes in 0..MAX_AGGREGATE_CONTEXT_BYTES) {
            "aggregate context limit must be between 0 and $MAX_AGGREGATE_CONTEXT_BYTES"
        }
        require(expectedNodeIds.size <= MAX_CONTEXT_SNAPSHOT_FILES) {
            "expected context snapshot count exceeds $MAX_CONTEXT_SNAPSHOT_FILES"
        }
        val expectedSet = expectedNodeIds.toSet()
        val contextDir = runDir.toPath().resolve("context")
        if (!Files.exists(contextDir, LinkOption.NOFOLLOW_LINKS)) {
            return ContextSnapshotIntegrity(
                observedNodeIds = emptyList(),
                missingNodeIds = expectedNodeIds,
                invalidFiles = emptyList(),
                unexpectedNodeIds = emptyList(),
                fileCountExceeded = false,
                aggregateBytesExceeded = false,
            )
        }
        if (!Files.isDirectory(contextDir, LinkOption.NOFOLLOW_LINKS)) {
            return ContextSnapshotIntegrity(
                observedNodeIds = emptyList(),
                missingNodeIds = expectedNodeIds,
                invalidFiles = listOf("context"),
                unexpectedNodeIds = emptyList(),
                fileCountExceeded = false,
                aggregateBytesExceeded = false,
            )
        }

        val retained = ArrayList<java.nio.file.Path>(1_024)
        var fileCountExceeded = false
        Files.newDirectoryStream(contextDir).use { entries ->
            for (entry in entries) {
                if (retained.size == maxFiles) {
                    fileCountExceeded = true
                    break
                }
                retained.add(entry)
            }
        }
        retained.sortBy { it.fileName.toString() }

        val invalidFiles = mutableListOf<String>()
        val unexpectedNodeIds = mutableSetOf<String>()
        val observedNodeIds = mutableSetOf<String>()
        val snapshots = mutableMapOf<String, ParsedContextSnapshot>()
        var retainedBytes = 0L
        var aggregateBytesExceeded = false
        for (entry in retained) {
            val name = entry.fileName.toString()
            if (!name.endsWith(INPUT_CONTEXT_SUFFIX)) {
                invalidFiles += name
                continue
            }
            val nodeId = name.removeSuffix(INPUT_CONTEXT_SUFFIX)
            if (!isValidNodeId(nodeId) ||
                !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
            ) {
                invalidFiles += name
                continue
            }
            if (nodeId !in expectedSet) unexpectedNodeIds += nodeId
            val captured = try {
                readBoundedUtf8RegularFile(
                    entry.toFile(),
                    "context file $name",
                )
            } catch (_: Exception) {
                invalidFiles += name
                continue
            }
            val bytes = captured.size.toLong()
            if (retainedBytes > maxAggregateBytes - bytes) {
                aggregateBytesExceeded = true
                break
            }
            val snapshot = try {
                val root = parseBoundedJsonObject(
                    captured.text,
                    "context file $name",
                    aggregateBudget,
                )
                ParsedContextSnapshot(
                    sha256 = captured.sha256,
                    selectedRaw = captured.text.takeIf {
                        replay?.selectedNodeId == nodeId
                    },
                    items = ContextSerde.fromParsed(root),
                )
            } catch (_: Exception) {
                invalidFiles += name
                continue
            }
            retainedBytes += bytes
            observedNodeIds += nodeId
            snapshots[nodeId] = snapshot
        }

        val orderedObserved = expectedNodeIds.filter(observedNodeIds::contains) +
                (observedNodeIds - expectedSet).sorted()
        val (provenanceViolations, replaySourceMismatches) = if (verifyProvenance) {
            inspectContextProvenance(
                expectedNodeIds,
                snapshots,
                publishedByNodeId,
                skippedNodeIds,
                replay,
                replaySourceSnapshot,
            )
        } else {
            emptyList<String>() to emptyList()
        }
        return ContextSnapshotIntegrity(
            observedNodeIds = orderedObserved,
            missingNodeIds = expectedNodeIds.filterNot(observedNodeIds::contains),
            invalidFiles = invalidFiles.sorted(),
            unexpectedNodeIds = unexpectedNodeIds.sorted(),
            provenanceViolationNodeIds = provenanceViolations,
            replaySourceMismatchNodeIds = replaySourceMismatches,
            fileCountExceeded = fileCountExceeded,
            aggregateBytesExceeded = aggregateBytesExceeded,
        )
    }

    private fun inspectContextProvenance(
        expectedNodeIds: List<String>,
        snapshots: Map<String, ParsedContextSnapshot>,
        publishedByNodeId: Map<String, Map<String, String>>,
        skippedNodeIds: Set<String>,
        replay: ReplayMetadata?,
        replaySourceSnapshot: ReplaySourceSnapshot?,
    ): Pair<List<String>, List<String>> {
        val violations = linkedSetOf<String>()
        val replayMismatches = linkedSetOf<String>()
        val expectedContext = mutableListOf<ContextItem>()
        var predecessorEvidenceAvailable = true

        if (replay != null) {
            val selected = replay.selectedNodeId
            val targetSnapshot = snapshots[selected]
            val targetDigestMatches = targetSnapshot != null &&
                    targetSnapshot.sha256 == replay.sourceContextSha256
            val capturedMatches = replaySourceSnapshot == null || (
                    replaySourceSnapshot.selectedContextSha256 == replay.sourceContextSha256 &&
                            targetSnapshot?.selectedRaw == replaySourceSnapshot.selectedContextJson &&
                            targetSnapshot.items == replaySourceSnapshot.selectedContext
                    )
            if (!targetDigestMatches || !capturedMatches) {
                replayMismatches += selected
            }
            if (!targetDigestMatches || !capturedMatches || targetSnapshot == null) {
                predecessorEvidenceAvailable = false
            } else {
                // On inline reporting use the immutable captured source value.
                // On manual regeneration the target copy is safe because its
                // raw digest is anchored in execution-scope.json.
                expectedContext += replaySourceSnapshot?.selectedContext ?: targetSnapshot.items
            }
        }

        for (nodeId in expectedNodeIds) {
            val snapshot = snapshots[nodeId]
            val replaySelectedMismatch =
                replay?.selectedNodeId == nodeId && nodeId in replayMismatches
            if (
                snapshot != null &&
                !replaySelectedMismatch &&
                (!predecessorEvidenceAvailable || snapshot.items != expectedContext)
            ) {
                violations += nodeId
            }
            val published = publishedByNodeId[nodeId]
            if (published == null) {
                predecessorEvidenceAvailable = false
            } else if (predecessorEvidenceAvailable && nodeId !in skippedNodeIds) {
                expectedContext += ContextItem(nodeId, published)
            }
        }
        return violations.toList() to replayMismatches.toList()
    }

    private fun requireReplaySnapshotMatchesMetadata(
        snapshot: ReplaySourceSnapshot,
        replay: ReplayMetadata,
        graphName: String?,
    ) {
        require(snapshot.sourceBuild == normalizedAbsoluteFile(replay.sourceBuild)) {
            "captured replay source path does not match execution metadata"
        }
        require(snapshot.graphName == graphName) {
            "captured replay source graph does not match execution metadata"
        }
        require(snapshot.selectedNodeId == replay.selectedNodeId) {
            "captured replay source node does not match execution metadata"
        }
        require(snapshot.closureSha256 == replay.sourceClosureSha256) {
            "captured replay source closure does not match execution metadata"
        }
        require(snapshot.selectedContextSha256 == replay.sourceContextSha256) {
            "captured replay source context does not match execution metadata"
        }
    }

    internal fun scanEnvelopeFiles(envelopeDir: File, maxFiles: Int): EnvelopeFileScan {
        require(maxFiles >= 0) { "envelope file limit must be non-negative" }
        val envelopePath = envelopeDir.toPath()
        if (!Files.exists(envelopePath, LinkOption.NOFOLLOW_LINKS)) {
            return EnvelopeFileScan(emptyList(), false)
        }
        require(Files.isDirectory(envelopePath, LinkOption.NOFOLLOW_LINKS)) {
            "envelope path must be a real directory, not a symlink: ${envelopeDir.absolutePath}"
        }

        val retained = ArrayList<File>(maxFiles.coerceAtMost(1_024))
        var countExceeded = false
        Files.newDirectoryStream(envelopePath).use { entries ->
            for (entry in entries) {
                if (!entry.fileName.toString().endsWith(".json")) continue
                require(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    "envelope JSON entry must be a regular file, not a symlink: $entry"
                }
                if (retained.size == maxFiles) {
                    countExceeded = true
                    break
                }
                retained += entry.toFile()
            }
        }
        retained.sortBy { it.name }
        return EnvelopeFileScan(retained, countExceeded)
    }

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    private const val MAX_FAILURE_MESSAGE_CHARS = 4_096
    private const val EXECUTION_SCOPE_FILE = "execution-scope.json"
    private const val EXECUTION_SCOPE_VERSION = 3
    private const val ATTEMPT_CLOSURE_FILE = "attempt-closure.json"
    private const val ATTEMPT_CLOSURE_VERSION = 3
    private const val TRACE_CARRIER_FILE = "trace-context.json"
    private const val CONTEXT_DIRECTORY = "context"
    private const val ENVELOPE_DIRECTORY = "envelope"
    private const val MAX_GRAPH_NAME_CHARS = 256
    private const val MAX_SOURCE_BUILD_PATH_CHARS = 8_192
    private const val INPUT_CONTEXT_SUFFIX = ".input.json"
    private const val ENVELOPE_SUFFIX = ".json"
    internal const val MAX_AGGREGATE_ENVELOPE_BYTES = 16L * 1024 * 1024
    internal const val MAX_ENVELOPE_FILES = 10_000
    internal const val MAX_AGGREGATE_CONTEXT_BYTES = 16L * 1024 * 1024
    internal const val MAX_CONTEXT_SNAPSHOT_FILES = 10_000
    internal const val MAX_AGGREGATE_JSON_STRUCTURAL_TOKENS = 500_000
    private val FULL_SCOPE_KEYS = setOf("version", "graphName", "mode", "expectedNodeIds")
    private val REPLAY_SCOPE_KEYS = FULL_SCOPE_KEYS + setOf(
        "selectedNodeId",
        "sourceBuild",
        "sourceClosureSha256",
        "sourceContextSha256",
    )
    private val ATTEMPT_CLOSURE_KEYS = setOf(
        "version",
        "runId",
        "traceId",
        "scope",
        "finalizerNodeIds",
        "scopeSha256",
        "carrierSha256",
        "contextSha256",
        "envelopeSha256",
    )
    private val ATTEMPT_CLOSURE_V2_KEYS = ATTEMPT_CLOSURE_KEYS - "finalizerNodeIds"
    private val VALID_NODE_STATUSES = setOf("passed", "failed", "errored", "skipped")
    private val TRACE_ID = Regex("^[0-9a-f]{32}$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private const val INVALID_ZERO_TRACE_ID = "00000000000000000000000000000000"

    private fun isValidTraceId(value: String): Boolean =
        value != INVALID_ZERO_TRACE_ID && TRACE_ID.matches(value)
}
