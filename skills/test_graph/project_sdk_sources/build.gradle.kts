plugins {
    id("com.hayden.testgraphsdk.graph")
}

/**
 * Example test graphs.
 *
 * Each `testGraph("name") { ... }` registers a Gradle task:
 *   ./gradlew smoke         runs the "smoke" graph
 *
 * Inside a graph:
 *   node("path/to/script")              add a script, describe to get its spec
 *       .dependsOn("extra.id")          ADD a dep on top of what the script declared
 *       .tags("regression")             tighten with extra tags
 *       .timeout("90s")                 override timeout
 *
 * Both dependency sources feed the topo sort: the script's self-declared
 * `dependsOn` (via describe) PLUS the DSL's `.dependsOn(...)` overlays.
 * The DSL can only add edges — it can never hide one the script declared.
 *
 * Transitives (scripts not listed in the DSL but referenced via dependsOn)
 * are resolved from any registered `sourcesDir`.
 *
 * Inspect / run:
 *   ./gradlew validationListGraphs
 *   ./gradlew validationPlanGraph --name=smoke
 *   ./gradlew smoke
 *   ./gradlew validationReport
 */
validationGraph {
    sourcesDir("sources")

    testGraph("smoke") {
        node("sources/user_seeded.py")

        // sources/NetworkPingable.java's spec declares NO dependsOn.
        // Its only edge comes from this DSL overlay — `app.running`.
        // This exercises "DSL-added deps feed the topo sort".
        node("sources/NetworkPingable.java")
            .dependsOn("app.running")

        // login.smoke's script already declares app.running + user.seeded
        // in its spec. The DSL adds a THIRD dep — network.pingable — so
        // this node now depends on four upstream nodes total.
        node("sources/LoginSmoke.java")
            .dependsOn("network.pingable")
            .tags("regression")

        // app.running is NOT listed as a node(...) call here — it's pulled
        // in transitively because user.seeded (script) + NetworkPingable (DSL)
        // + LoginSmoke (script) all declare it, and the plugin finds
        // sources/AppRunning.java via sourcesDir.
    }

    // Multiple graphs supported — each becomes its own Gradle task.
    // Uncomment to add a second graph that reuses the same scripts with
    // different DSL overlays (tighter timeouts, extra tags, extra edges):
    //
    // testGraph("regression") {
    //     node("sources/LoginSmoke.java")
    //         .dependsOn("user.seeded", "app.running")     // redundant but explicit
    //         .tags("regression", "nightly")
    //         .timeout("300s")
    //     node("sources/user_seeded.py")
    // }
}

/*
 * IMPORTING USER CODE
 * -------------------
 * If this scaffold lives at <user-project>/test_graph/, node scripts in
 * sources/ can reach user code via `../..`. See:
 *   - test-graph-skill/SKILL.md → "Importing user code"
 *   - sources/AppRunning.java   (example JBang //SOURCES / //DEPS block)
 *   - sources/user_seeded.py    (example uv [tool.uv.sources] block)
 *
 * No DSL changes are needed here — imports are a property of the node
 * script, not of the graph that composes the scripts.
 */
