package com.hayden.testgraphsdk.exec

import com.hayden.testgraphsdk.isValidNodeId
import java.time.Instant

/**
 * The versioned, closed schema for executor-authored node envelopes.
 *
 * Version 1 rejects unknown fields at every schema-owned object boundary.
 * Adding a top-level or nested extension therefore requires a deliberate
 * envelope-version bump and a validator update; report rendering never treats
 * an unrecognised shape as passing evidence.
 */
internal object CanonicalEnvelopeValidator {
    const val VERSION = 1

    data class Validated(
        val value: Map<String, Any?>,
        val nodeId: String,
        val status: String,
        val traceId: String,
        val published: Map<String, String>,
    )

    data class Tentative(
        val value: Map<String, Any?>,
        val status: String,
        val failureMessage: String?,
    )

    fun validate(
        raw: String,
        label: String,
        expectedNodeId: String,
        expectedTraceId: String? = null,
        aggregateBudget: AggregateJsonStructureBudget? = null,
    ): Validated = validate(
        parseBoundedJsonObject(raw, label, aggregateBudget),
        label,
        expectedNodeId,
        expectedTraceId,
    )

    fun validate(
        value: Map<String, Any?>,
        label: String,
        expectedNodeId: String,
        expectedTraceId: String? = null,
    ): Validated {
        require(value.keys.containsAll(FINAL_REQUIRED_KEYS)) {
            "$label is missing canonical envelope fields: " +
                    (FINAL_REQUIRED_KEYS - value.keys).sorted().joinToString(", ")
        }
        require(value.keys.all { it in FINAL_ALLOWED_KEYS }) {
            "$label contains fields outside canonical envelope v$VERSION: " +
                    (value.keys - FINAL_ALLOWED_KEYS).sorted().joinToString(", ")
        }
        require(value["envelopeVersion"] == VERSION.toLong()) {
            "$label has unsupported envelopeVersion"
        }

        val nodeId = requiredString(value, "nodeId", label)
        require(isValidNodeId(nodeId) && nodeId == expectedNodeId) {
            nodeIdMismatch(label, nodeId, expectedNodeId)
        }
        val status = requiredStatus(value, label)
        val traceId = requiredString(value, "traceId", label)
        require(TRACE_ID.matches(traceId) && traceId != ZERO_TRACE_ID) {
            "$label traceId is not a valid non-zero lowercase trace id"
        }
        require(expectedTraceId == null || traceId == expectedTraceId) {
            "$label traceId does not match the run trace"
        }

        val bodyStart = requiredInstant(value, "startedAt", label)
        val bodyEnd = requiredInstant(value, "endedAt", label)
        val executorStart = requiredInstant(value, "executorStartedAt", label)
        val executorEnd = requiredInstant(value, "executorEndedAt", label)
        require(!bodyEnd.isBefore(bodyStart)) {
            "$label endedAt precedes startedAt"
        }
        require(!executorEnd.isBefore(executorStart)) {
            "$label executorEndedAt precedes executorStartedAt"
        }
        require(!bodyStart.isBefore(executorStart) && !bodyEnd.isAfter(executorEnd)) {
            "$label body timestamps must be within executor timestamps"
        }

        val spawnExitCode = requiredInt(value, "spawnExitCode", label)
        require(status != "passed" || spawnExitCode == 0) {
            "$label cannot be passed when spawnExitCode is $spawnExitCode"
        }
        require(
            requiredString(value, "capturedStdoutLog", label) ==
                    canonicalStdoutPath(nodeId)
        ) {
            "$label capturedStdoutLog is not the canonical node log pointer"
        }
        require(
            requiredString(value, "inputContextFile", label) ==
                    canonicalInputContextPath(nodeId)
        ) {
            "$label inputContextFile is not the canonical input-context pointer"
        }

        validateOptionalFailureFields(value, status, label)
        validateAssertions(value["assertions"], status, label)
        validateArtifacts(value["artifacts"], label)
        validateProcesses(value["processes"], label)
        validateMetrics(value["metrics"], label)
        validateStringList(value["logs"], "$label logs")
        val published = strictStringMap(value["published"], "$label published")
        if (value.containsKey("rerunGuidance")) {
            validateRerunGuidance(value["rerunGuidance"], status, nodeId, label)
        }
        if (value.containsKey("environmentRepositoryExecution")) {
            validateEnvironmentRepositoryExecution(
                value["environmentRepositoryExecution"],
                label,
            )
        }
        if (value.containsKey("provisioningState")) {
            validateProvisioningState(value["provisioningState"], status, label)
        }
        if (value.containsKey("malformedResultOutPreview")) {
            val preview = value["malformedResultOutPreview"]
            require(status == "errored" && preview is String) {
                "$label malformedResultOutPreview is only valid as a string on errored envelopes"
            }
        }

        return Validated(value, nodeId, status, traceId, published)
    }

    /** Validate the SDK-authored value before executor-owned fields are appended. */
    fun validateTentative(
        value: Map<String, Any?>,
        label: String,
        expectedNodeId: String,
    ): Tentative {
        val nodeId = requiredString(value, "nodeId", label)
        require(nodeId == expectedNodeId && isValidNodeId(nodeId)) {
            nodeIdMismatch(label, nodeId, expectedNodeId)
        }
        val status = requiredStatus(value, label)
        require(value.keys.containsAll(TENTATIVE_REQUIRED_KEYS)) {
            "$label is missing NodeResult fields: " +
                    (TENTATIVE_REQUIRED_KEYS - value.keys).sorted().joinToString(", ")
        }
        require(value.keys.all { it in TENTATIVE_ALLOWED_KEYS }) {
            "$label contains executor-owned or unknown NodeResult fields: " +
                    (value.keys - TENTATIVE_ALLOWED_KEYS).sorted().joinToString(", ")
        }
        val startedAt = requiredInstant(value, "startedAt", label)
        val endedAt = requiredInstant(value, "endedAt", label)
        require(!endedAt.isBefore(startedAt)) { "$label endedAt precedes startedAt" }
        validateOptionalFailureFields(value, status, label)
        validateAssertions(value["assertions"], status, label)
        validateArtifacts(value["artifacts"], label)
        validateProcesses(value["processes"], label)
        validateMetrics(value["metrics"], label)
        validateStringList(value["logs"], "$label logs")
        strictStringMap(value["published"], "$label published")
        return Tentative(value, status, value["failureMessage"] as? String)
    }

    fun canonicalInputContextPath(nodeId: String): String =
        java.io.File("context", "$nodeId.input.json").path

    fun canonicalStdoutPath(nodeId: String): String =
        java.io.File("node-logs", "$nodeId.stdout.log").path

    private fun validateOptionalFailureFields(
        value: Map<String, Any?>,
        status: String,
        label: String,
    ) {
        if (value.containsKey("failureMessage")) {
            val failureMessage = value["failureMessage"]
            require(failureMessage is String && status != "passed") {
                "$label failureMessage must be a string on a non-passing envelope"
            }
        }
        if (value.containsKey("errorStack")) {
            val errorStack = value["errorStack"]
            require(errorStack is String && status == "errored") {
                "$label errorStack must be a string on an errored envelope"
            }
        }
    }

    private fun validateAssertions(raw: Any?, nodeStatus: String, label: String) {
        val assertions = requiredList(raw, "$label assertions")
        var hasFailedAssertion = false
        for ((index, rawAssertion) in assertions.withIndex()) {
            val assertion = requiredObject(rawAssertion, "$label assertion[$index]")
            require(assertion.keys == ASSERTION_KEYS) {
                "$label assertion[$index] must contain exactly name and status"
            }
            requiredString(assertion, "name", "$label assertion[$index]")
            val status = requiredString(assertion, "status", "$label assertion[$index]")
            require(status in ASSERTION_STATUSES) {
                "$label assertion[$index] has invalid status '$status'"
            }
            hasFailedAssertion = hasFailedAssertion || status == "failed"
        }
        require(nodeStatus != "passed" || !hasFailedAssertion) {
            "$label cannot be passed while an assertion is failed"
        }
    }

    private fun validateArtifacts(raw: Any?, label: String) {
        for ((index, rawArtifact) in requiredList(raw, "$label artifacts").withIndex()) {
            val artifact = requiredObject(rawArtifact, "$label artifact[$index]")
            require(artifact.keys == ARTIFACT_KEYS) {
                "$label artifact[$index] must contain exactly type and path"
            }
            requiredString(artifact, "type", "$label artifact[$index]")
            requiredString(artifact, "path", "$label artifact[$index]")
        }
    }

    private fun validateProcesses(raw: Any?, label: String) {
        for ((index, rawProcess) in requiredList(raw, "$label processes").withIndex()) {
            val process = requiredObject(rawProcess, "$label process[$index]")
            require(process.keys.containsAll(PROCESS_REQUIRED_KEYS) &&
                    process.keys.all { it in PROCESS_ALLOWED_KEYS }) {
                "$label process[$index] has an invalid field set"
            }
            requiredString(process, "label", "$label process[$index]")
            validateStringList(process["command"], "$label process[$index] command")
            requiredInt(process, "exitCode", "$label process[$index]")
            process["pid"]?.let {
                require(it is Long && it >= 0L) {
                    "$label process[$index] pid must be a non-negative integer or null"
                }
            }
            process["log"]?.let {
                require(it is String) {
                    "$label process[$index] log must be a string or null"
                }
            }
            process["error"]?.let {
                require(it is String) {
                    "$label process[$index] error must be a string or null"
                }
            }
            val start = if (process.containsKey("startedAt")) {
                requiredInstant(process, "startedAt", "$label process[$index]")
            } else {
                null
            }
            val end = if (process.containsKey("endedAt")) {
                requiredInstant(process, "endedAt", "$label process[$index]")
            } else {
                null
            }
            if (start != null && end != null) {
                require(!end.isBefore(start)) {
                    "$label process[$index] endedAt precedes startedAt"
                }
            }
        }
    }

    private fun validateMetrics(raw: Any?, label: String) {
        val metrics = requiredObject(raw, "$label metrics")
        require(metrics.values.all { it is Number }) {
            "$label metrics must contain only numeric values"
        }
    }

    private fun nodeIdMismatch(label: String, observed: String, expected: String): String =
        if (isValidNodeId(observed)) {
            "$label nodeId '$observed' does not match expected $expected"
        } else {
            "$label nodeId is invalid (${observed.length} characters); expected $expected"
        }

    private fun validateRerunGuidance(
        raw: Any?,
        status: String,
        nodeId: String,
        label: String,
    ) {
        require(status != "passed") { "$label passed envelope cannot contain rerunGuidance" }
        val guidance = requiredObject(raw, "$label rerunGuidance")
        require(guidance.keys == RERUN_GUIDANCE_KEYS) {
            "$label rerunGuidance has an invalid field set"
        }
        requiredString(guidance, "resumeGraphCommand", "$label rerunGuidance")
        requiredString(guidance, "runOnlyCommand", "$label rerunGuidance")
        require(
            requiredString(guidance, "inputContextFile", "$label rerunGuidance") ==
                    canonicalInputContextPath(nodeId)
        ) {
            "$label rerunGuidance inputContextFile is not canonical"
        }
    }

    private fun validateEnvironmentRepositoryExecution(raw: Any?, label: String) {
        val execution = requiredObject(raw, "$label environmentRepositoryExecution")
        require(execution.keys == ENVIRONMENT_EXECUTION_KEYS) {
            "$label environmentRepositoryExecution has an invalid field set"
        }
        for (key in listOf(
            "environmentId", "branch", "target", "backend", "repositoryDir", "templateDir"
        )) {
            requiredString(execution, key, "$label environmentRepositoryExecution")
        }
        require(execution["reused"] is Boolean) {
            "$label environmentRepositoryExecution reused must be boolean"
        }
        strictStringMap(
            execution["outputs"],
            "$label environmentRepositoryExecution outputs",
        )
        for ((index, rawCommand) in requiredList(
            execution["commands"],
            "$label environmentRepositoryExecution commands",
        ).withIndex()) {
            val command = requiredObject(
                rawCommand,
                "$label environmentRepositoryExecution command[$index]",
            )
            require(command.keys.containsAll(ENVIRONMENT_COMMAND_REQUIRED_KEYS) &&
                    command.keys.all { it in ENVIRONMENT_COMMAND_ALLOWED_KEYS }) {
                "$label environmentRepositoryExecution command[$index] has an invalid field set"
            }
            requiredString(command, "label", "$label environmentRepositoryExecution command[$index]")
            validateStringList(
                command["command"],
                "$label environmentRepositoryExecution command[$index] command",
            )
            requiredInt(command, "exitCode", "$label environmentRepositoryExecution command[$index]")
            requiredString(command, "log", "$label environmentRepositoryExecution command[$index]")
            if (command.containsKey("stderrLog")) {
                require(command["stderrLog"] is String) {
                    "$label environmentRepositoryExecution command[$index] stderrLog must be a string"
                }
            }
        }
    }

    private fun validateProvisioningState(raw: Any?, status: String, label: String) {
        require(status == "passed") {
            "$label provisioningState is only valid on a passed envelope"
        }
        val state = requiredObject(raw, "$label provisioningState")
        require(state.keys.containsAll(PROVISIONING_REQUIRED_KEYS) &&
                state.keys.all { it in PROVISIONING_ALLOWED_KEYS }) {
            "$label provisioningState has an invalid field set"
        }
        for (key in listOf("environmentId", "branch", "target", "backend")) {
            requiredString(state, key, "$label provisioningState")
        }
        validateStringList(state["actions"], "$label provisioningState actions")
        for (key in PROVISIONING_MARKER_KEYS) {
            if (state.containsKey(key)) {
                require(state[key] is String) {
                    "$label provisioningState $key must be a string"
                }
            }
        }
    }

    private fun requiredStatus(value: Map<String, Any?>, label: String): String {
        val status = requiredString(value, "status", label)
        require(status in NODE_STATUSES) { "$label has invalid status '$status'" }
        return status
    }

    private fun requiredInstant(
        value: Map<String, Any?>,
        key: String,
        label: String,
    ): Instant {
        val raw = requiredString(value, key, label)
        return try {
            Instant.parse(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("$label $key is not an ISO-8601 instant", e)
        }
    }

    private fun requiredInt(value: Map<String, Any?>, key: String, label: String): Int {
        val raw = value[key]
        require(raw is Long && raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "$label $key must be a 32-bit integer"
        }
        return raw.toInt()
    }

    private fun requiredString(
        value: Map<String, Any?>,
        key: String,
        label: String,
    ): String = value[key] as? String
        ?: throw IllegalArgumentException("$label $key must be a string")

    @Suppress("UNCHECKED_CAST")
    private fun requiredObject(raw: Any?, label: String): Map<String, Any?> {
        val map = raw as? Map<*, *>
            ?: throw IllegalArgumentException("$label must be an object")
        require(map.keys.all { it is String }) { "$label keys must be strings" }
        return map as Map<String, Any?>
    }

    private fun requiredList(raw: Any?, label: String): List<*> = raw as? List<*>
        ?: throw IllegalArgumentException("$label must be an array")

    private fun validateStringList(raw: Any?, label: String) {
        require(requiredList(raw, label).all { it is String }) {
            "$label must contain only strings"
        }
    }

    private fun strictStringMap(raw: Any?, label: String): Map<String, String> {
        val map = requiredObject(raw, label)
        require(map.values.all { it is String }) {
            "$label must contain only string/string entries"
        }
        return map.mapValues { it.value as String }
    }

    private val TENTATIVE_REQUIRED_KEYS = setOf(
        "nodeId", "status", "startedAt", "endedAt", "assertions", "artifacts",
        "processes", "metrics", "logs", "published",
    )
    private val TENTATIVE_ALLOWED_KEYS = TENTATIVE_REQUIRED_KEYS + setOf(
        "failureMessage", "errorStack",
    )
    private val FINAL_REQUIRED_KEYS = TENTATIVE_REQUIRED_KEYS + setOf(
        "envelopeVersion", "traceId", "executorStartedAt", "executorEndedAt",
        "spawnExitCode", "capturedStdoutLog", "inputContextFile",
    )
    private val FINAL_ALLOWED_KEYS = FINAL_REQUIRED_KEYS + setOf(
        "failureMessage", "errorStack", "rerunGuidance", "malformedResultOutPreview",
        "environmentRepositoryExecution", "provisioningState",
    )
    private val ASSERTION_KEYS = setOf("name", "status")
    private val ARTIFACT_KEYS = setOf("type", "path")
    private val PROCESS_REQUIRED_KEYS = setOf(
        "label", "command", "exitCode", "pid", "log", "error",
    )
    private val PROCESS_ALLOWED_KEYS = PROCESS_REQUIRED_KEYS + setOf("startedAt", "endedAt")
    private val RERUN_GUIDANCE_KEYS = setOf(
        "resumeGraphCommand", "runOnlyCommand", "inputContextFile",
    )
    private val ENVIRONMENT_EXECUTION_KEYS = setOf(
        "environmentId", "branch", "target", "backend", "repositoryDir", "templateDir",
        "reused", "outputs", "commands",
    )
    private val ENVIRONMENT_COMMAND_REQUIRED_KEYS = setOf(
        "label", "command", "exitCode", "log",
    )
    private val ENVIRONMENT_COMMAND_ALLOWED_KEYS =
        ENVIRONMENT_COMMAND_REQUIRED_KEYS + "stderrLog"
    private val PROVISIONING_REQUIRED_KEYS = setOf(
        "environmentId", "branch", "target", "backend", "actions",
    )
    private val PROVISIONING_MARKER_KEYS = setOf(
        "provisionedMarker", "deployedMarker", "resetMarker",
        "destroyRequestMarker", "destroyedMarker",
    )
    private val PROVISIONING_ALLOWED_KEYS =
        PROVISIONING_REQUIRED_KEYS + PROVISIONING_MARKER_KEYS
    private val NODE_STATUSES = setOf("passed", "failed", "errored", "skipped")
    private val ASSERTION_STATUSES = setOf("passed", "failed")
    private val TRACE_ID = Regex("^[0-9a-f]{32}$")
    private const val ZERO_TRACE_ID = "00000000000000000000000000000000"
}
