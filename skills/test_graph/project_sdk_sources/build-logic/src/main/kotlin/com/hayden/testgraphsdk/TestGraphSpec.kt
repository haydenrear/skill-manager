package com.hayden.testgraphsdk

import org.gradle.api.Project
import java.io.File

/**
 * A chainable handle for an explicitly-declared node in a test graph.
 * Every setter returns `this`; at plan-build time the overlay is merged
 * on top of the spec the script self-declared.
 *
 * Merge policy:
 *   - Collections (dependsOn, tags, sideEffects) — UNIONed with the script's.
 *   - Scalars (timeout, cacheable) — OVERRIDE if set here; otherwise keep script's.
 *
 * The script is always the floor; the DSL can only add edges or tighten
 * constraints. It can never hide a dependency the script declared —
 * scripts remain the single source of truth for their own requirements.
 */
class NodeOverlay(internal val file: File) {
    internal val extraDependsOn = mutableListOf<String>()
    internal val extraTags = linkedSetOf<String>()
    internal val extraSideEffects = linkedSetOf<String>()
    internal var timeoutOverride: String? = null
    internal var retriesOverride: Int? = null
    internal var cacheableOverride: Boolean? = null

    fun dependsOn(vararg ids: String): NodeOverlay { extraDependsOn.addAll(ids); return this }
    fun tags(vararg t: String): NodeOverlay { extraTags.addAll(t); return this }
    fun sideEffects(vararg s: String): NodeOverlay { extraSideEffects.addAll(s); return this }
    fun timeout(v: String): NodeOverlay { timeoutOverride = v; return this }
    fun retries(n: Int): NodeOverlay { retriesOverride = n.coerceAtLeast(0); return this }
    fun cacheable(b: Boolean): NodeOverlay { cacheableOverride = b; return this }

    internal fun applyTo(spec: ValidationNodeSpec): ValidationNodeSpec =
        spec.copy(
            dependsOn = (spec.dependsOn + extraDependsOn).distinct(),
            tags = spec.tags + extraTags,
            sideEffects = spec.sideEffects + extraSideEffects,
            timeout = timeoutOverride ?: spec.timeout,
            retries = retriesOverride ?: spec.retries,
            cacheable = cacheableOverride ?: spec.cacheable,
        )
}

/**
 * A named composition of nodes — the "test graph" the user composes in
 * build.gradle.kts. Each one becomes a registered Gradle task.
 *
 * A graph holds:
 *  - a map of explicit script files → per-node overlays (DSL-added
 *    deps/tags/timeout on top of what the script self-declares)
 *  - its name (also the Gradle task name)
 *
 * Transitive deps are resolved from the extension's `sourcesDir` list
 * at execution time.
 */
data class TestGraphSpec(
    val name: String,
    val explicitNodes: Map<File, NodeOverlay>,
)

/**
 * DSL builder for `testGraph("name") { ... }`. Every `node(path)` call
 * returns a {@link NodeOverlay} the caller can chain
 * `.dependsOn(...)`, `.tags(...)`, `.timeout(...)`, etc. on.
 *
 * Chained deps are *additive* — they always layer on top of what the
 * script self-declared; the DSL can never hide a dep the script
 * declared.
 */
class TestGraphBuilder(private val project: Project, private val name: String) {
    internal val explicitNodes = linkedMapOf<File, NodeOverlay>()

    fun node(path: String): NodeOverlay {
        val f = project.file(path)
        require(f.isFile) { "node script not found: ${f.path}" }
        return explicitNodes.getOrPut(f) { NodeOverlay(f) }
    }

    internal fun build(): TestGraphSpec = TestGraphSpec(name, explicitNodes.toMap())
}
