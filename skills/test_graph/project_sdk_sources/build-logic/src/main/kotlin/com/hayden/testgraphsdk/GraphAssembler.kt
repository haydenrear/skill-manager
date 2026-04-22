package com.hayden.testgraphsdk

import java.io.File

/**
 * Builds a topo-ordered plan for one {@link TestGraphSpec}.
 *
 *  1. Describe each explicit script, apply its DSL overlay (extra
 *     dependsOn/tags/timeout/etc on top of what the script self-declared).
 *  2. Index every script under the configured sourcesDirs so transitive
 *     deps can be resolved.
 *  3. BFS over the explicit nodes' dependsOn (merged list, so DSL-added
 *     edges count too), pulling unresolved ids from the sourcesDir index.
 *  4. Topo-sort from the explicit nodes as roots.
 *
 * Both sources of dependencies — the script's own `dependsOn` from
 * describe, and the DSL's `.dependsOn(...)` overlay — are merged *before*
 * topo sort, so both contribute to ordering.
 */
internal object GraphAssembler {

    fun plan(
        spec: TestGraphSpec,
        sourcesDirs: List<File>,
        projectDir: File,
    ): List<ValidationNodeSpec> {
        val nodes = linkedMapOf<String, ValidationNodeSpec>()
        val explicitIds = mutableListOf<String>()

        // 1. Describe + apply overlays.
        for ((file, overlay) in spec.explicitNodes) {
            val described = NodeDescribeLoader.describe(file, projectDir)
            val merged = overlay.applyTo(described)
            if (nodes.containsKey(merged.id)) {
                error("duplicate node id '${merged.id}' in graph '${spec.name}'")
            }
            nodes[merged.id] = merged
            explicitIds += merged.id
        }

        // 2. Index the sourcesDirs.
        val sourceIndex = linkedMapOf<String, ValidationNodeSpec>()
        for (dir in sourcesDirs) {
            for ((id, s) in NodeDescribeLoader.indexDir(dir, projectDir)) {
                sourceIndex.putIfAbsent(id, s)
            }
        }

        // 3. Transitive resolution over the merged dependsOn lists.
        val frontier = ArrayDeque<String>()
        nodes.values.forEach { frontier.addAll(it.dependsOn) }
        while (frontier.isNotEmpty()) {
            val id = frontier.removeFirst()
            if (id in nodes) continue
            val found = sourceIndex[id] ?: error(
                "unresolved dependency '$id' in graph '${spec.name}' — " +
                "not declared explicitly and no script with this id in any sourcesDir"
            )
            nodes[id] = found
            frontier.addAll(found.dependsOn)
        }

        // 4. Topo-sort from explicit nodes.
        val model = GraphModel(nodes)
        return model.planForNames(explicitIds)
    }
}
