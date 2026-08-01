package com.hayden.testgraphsdk

private val VALID_NODE_ID = Regex("[a-z0-9._-]{1,128}")

internal fun isValidNodeId(nodeId: String): Boolean = VALID_NODE_ID.matches(nodeId)

internal fun requireValidNodeId(nodeId: String, label: String = "node id"): String {
    require(isValidNodeId(nodeId)) {
        "$label must match [a-z0-9._-]{1,128}"
    }
    return nodeId
}

/**
 * Typed graph-model representation of a node.
 *
 * Self-describe output from each script gets parsed into one of these,
 * then (for explicit nodes) merged with any DSL overlay. This is the
 * single source of truth the plugin tasks act on.
 */
data class ValidationNodeSpec(
    val id: String,
    val kind: NodeKind,
    val runtime: ValidationRuntime,
    val dependsOn: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
    val timeout: String = "60s",
    /**
     * Extra attempts the executor makes when the spawned node-process
     * exceeds its [timeout]. Default 0 means "fail fast on first timeout".
     * Retries trigger ONLY on a timeout outcome — a body-returned
     * `failed`/`errored` is final on the first attempt. Authors opt in
     * per node via `NodeSpec.retries(n)` (or the DSL overlay's
     * `.retries(n)`); most nodes are stateful and not safely re-runnable.
     */
    val retries: Int = 0,
    /**
     * Whether failed-run output should suggest a direct rerun command from the
     * saved build-directory input context. Defaults true and is independent of
     * timeout [retries], which are automatic executor attempts.
     */
    val rerun: Boolean = true,
    val cacheable: Boolean = false,
    val sideEffects: Set<String> = emptySet(),
    val environmentRepository: EnvironmentRepositorySpec? = null,
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    val reports: ReportsSpec = ReportsSpec(),
) {
    init {
        requireValidNodeId(id)
    }

    fun sideEffectSpecs(): Set<SideEffectSpec> =
        SideEffectSpec.parseAll(sideEffects, "node '$id' sideEffects")
}

internal fun ValidationNodeSpec.isFinalizerNode(): Boolean =
    id.endsWith(".cleanup") || "finalizer" in tags

data class ReportsSpec(
    val structuredJson: Boolean = true,
    val junitXml: Boolean = false,
    val cucumber: Boolean = false,
)
