plugins {
    id("com.hayden.testgraphsdk.graph")
}

/**
 * Integration test graph for skill-manager.
 *
 *   ./gradlew smoke                                run the full graph
 *   ./gradlew validationPlanGraph --name=smoke     dry-run the plan
 *   ./gradlew validationReport                     aggregate per-node envelopes
 *
 * Lanes:
 *   fixture     env.prepared                      per-run SKILL_MANAGER_HOME + free ports
 *   testbed     registry.up / gateway.up /        server + gateway + echo fixture
 *               echo.http.up
 *   registry    hello.published → hello.installed → search.finds
 *   cli         umbrella.installed → transitive.clis.present
 *   mcp         mcp.registered → mcp.tools.visible  (docker stdio)
 *               echo.http.registered → echo.http.deployed → mcp.tool.search.finds
 *                                                          → mcp.tool.invoked
 *                                                          → echo.http.redeployed
 *   agents      agents.synced → agent.configs.correct     (fake HOME)
 *   report      smoke.report aggregates every envelope into smoke-report.md
 *   teardown    servers.down stops gateway + registry + echo fixture
 */
validationGraph {
    sourcesDir("sources")

    testGraph("smoke") {
        node("sources/EnvPrepared.java")
        node("sources/RegistryUp.java")
        node("sources/GatewayUp.java")
        node("sources/EchoHttpUp.java")

        node("sources/HelloPublished.java")
        node("sources/HelloInstalled.java")
        node("sources/SearchFinds.java")

        node("sources/UmbrellaInstalled.java")
        node("sources/TransitiveClisPresent.java")

        node("sources/McpRegistered.java")
        node("sources/McpToolsVisible.java")

        node("sources/EchoHttpRegistered.java")
        node("sources/EchoHttpDeployed.java")
        node("sources/McpToolSearchFinds.java")
        node("sources/McpToolInvoked.java")
        node("sources/EchoHttpRedeployed.java")

        node("sources/AgentsSynced.java")
        node("sources/AgentConfigsCorrect.java")

        node("sources/SmokeReport.java")
        node("sources/ServersDown.java")
    }
}
