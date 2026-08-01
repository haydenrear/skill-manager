package com.hayden.testgraphsdk

import java.io.File

/**
 * Builds a topo-ordered plan for one {@link TestGraphSpec}.
 *
 *  1. Index the shipped standard-node catalog.
 *  2. Describe each explicit script, apply its DSL overlay (extra
 *     dependsOn/tags/timeout/etc on top of what the script self-declared).
 *     Reject an explicit consumer script that declares a shipped standard id.
 *  3. Index every remaining script under the configured sourcesDirs so
 *     transitive deps can be resolved. Shipped standard nodes retain first
 *     precedence.
 *  4. BFS over the explicit nodes' dependsOn (merged list, so DSL-added
 *     edges count too), pulling unresolved ids from the sourcesDir index.
 *  5. Topo-sort from the explicit nodes as roots.
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
        tools: ToolPaths,
    ): List<ValidationNodeSpec> {
        val nodes = linkedMapOf<String, ValidationNodeSpec>()
        val explicitIds = mutableListOf<String>()
        val standardDir = projectDir.resolve("standard-nodes")
        val standardIndex = if (standardDir.isDirectory) {
            NodeDescribeLoader.indexDir(standardDir, projectDir, tools)
        } else {
            emptyMap()
        }

        // 1/2. Describe explicit nodes after indexing the standard catalog so
        // an explicit consumer script cannot replace a provider-owned id.
        for ((file, overlay) in spec.explicitNodes) {
            val described = NodeDescribeLoader.describe(file, projectDir, tools)
            val merged = overlay.applyTo(described)
            val standard = standardIndex[merged.id]
            if (standard != null && merged.runtime != standard.runtime) {
                error(
                    "node id '${merged.id}' in graph '${spec.name}' is reserved by " +
                        "the shipped standard-node catalog; compose it with " +
                        "standardNode(\"${merged.id}\")"
                )
            }
            if (nodes.containsKey(merged.id)) {
                error("duplicate node id '${merged.id}' in graph '${spec.name}'")
            }
            nodes[merged.id] = merged
            explicitIds += merged.id
        }

        // 3. Index the remaining sourcesDirs. Seed from the already-described
        // standard catalog and skip that directory when the extension includes
        // it, avoiding a second describe pass.
        val sourceIndex = linkedMapOf<String, ValidationNodeSpec>()
        sourceIndex.putAll(standardIndex)
        for (dir in sourcesDirs) {
            if (dir.canonicalFile == standardDir.canonicalFile) continue
            for ((id, s) in NodeDescribeLoader.indexDir(dir, projectDir, tools)) {
                sourceIndex.putIfAbsent(id, s)
            }
        }

        // 4. Transitive resolution over the merged dependsOn lists.
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

        // 5. Topo-sort from explicit nodes.
        val model = GraphModel(nodes)
        return model.planForNames(explicitIds)
    }
}
