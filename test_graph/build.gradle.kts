plugins {
    id("com.hayden.testgraphsdk.graph")
}

/**
 * Integration test graph for skill-manager.
 *
 * Nodes bring up the bundled Spring Boot registry server + virtual MCP
 * gateway in isolated per-run state, drive the CLI through a publish →
 * install → search loop, register an MCP server through the gateway,
 * prove it's visible via the Java MCP SDK, and tear everything down.
 *
 *   ./gradlew smoke                 run the full graph
 *   ./gradlew validationPlanGraph --name=smoke   dry-run the plan
 *   ./gradlew validationReport      aggregate per-node envelopes
 */
validationGraph {
    sourcesDir("sources")

    testGraph("smoke") {
        // Fixture + testbeds (pulled in transitively by downstream nodes,
        // but listing them explicitly makes the plan readable).
        node("sources/EnvPrepared.java")
        node("sources/RegistryUp.java")
        node("sources/GatewayUp.java")

        // Registry side
        node("sources/HelloPublished.java")
        node("sources/HelloInstalled.java")
        node("sources/SearchFinds.java")

        // MCP gateway side
        node("sources/McpRegistered.java")
        node("sources/McpToolsVisible.java")

        // Teardown last — already declares dependsOn on the terminal
        // assertion/action nodes in its spec, so it runs after them.
        node("sources/ServersDown.java")
    }
}
