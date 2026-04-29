package com.hayden.testgraphsdk

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
    val cacheable: Boolean = false,
    val sideEffects: Set<String> = emptySet(),
    val inputs: Map<String, String> = emptyMap(),
    val outputs: Map<String, String> = emptyMap(),
    val reports: ReportsSpec = ReportsSpec(),
)

data class ReportsSpec(
    val structuredJson: Boolean = true,
    val junitXml: Boolean = false,
    val cucumber: Boolean = false,
)
