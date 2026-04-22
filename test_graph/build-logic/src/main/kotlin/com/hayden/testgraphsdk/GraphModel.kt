package com.hayden.testgraphsdk

/**
 * Resolved set of nodes for one test graph, with a topo-sort helper.
 *
 * GraphAssembler builds one of these per-run, handing it a map that
 * already includes every explicitly-declared node and every transitive
 * dep pulled from the sourcesDirs. The topo sort uses the merged
 * dependsOn list (script-declared + DSL-overlay).
 */
class GraphModel(
    val nodes: Map<String, ValidationNodeSpec>,
) {
    fun require(id: String): ValidationNodeSpec =
        nodes[id] ?: error("no such node: $id")

    /**
     * Topologically ordered transitive closure rooted at the given targets.
     */
    fun planForNames(targetIds: List<String>): List<ValidationNodeSpec> {
        val order = mutableListOf<ValidationNodeSpec>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            if (id in visited) return
            if (id in visiting) error("cycle in graph involving: $id")
            visiting += id
            val spec = require(id)
            for (dep in spec.dependsOn) visit(dep)
            visiting -= id
            visited += id
            order += spec
        }
        for (t in targetIds) visit(t)
        return order
    }
}
