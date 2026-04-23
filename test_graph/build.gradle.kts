plugins {
    id("com.hayden.testgraphsdk.graph")
}

/**
 * Two integration test graphs for skill-manager.
 *
 *   ./gradlew smoke       full registry + gateway + MCP flow
 *   ./gradlew sponsored   registry-only ad auction
 *
 * Validate first:
 *   ./gradlew validationPlanGraph --name=smoke
 *   ./gradlew validationPlanGraph --name=sponsored
 *
 * Layout:
 *   sources/common/      shared infra nodes (env, postgres, registry, auth)
 *   sources/smoke/       smoke-only nodes (gateway, MCP, agents)
 *   sources/sponsored/   sponsored-only nodes (ad auction assertions)
 */
validationGraph {
    sourcesDir("sources")

    testGraph("smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/common/CiLoggedIn.java")
        node("sources/common/JwtValid.java")
        node("sources/smoke/GatewayUp.java")
        node("sources/smoke/EchoHttpUp.java")

        node("sources/smoke/HelloPublished.java")
        node("sources/smoke/HelloInstalled.java")
        node("sources/smoke/SearchFinds.java")

        node("sources/smoke/UmbrellaInstalled.java")
        node("sources/smoke/TransitiveClisPresent.java")

        node("sources/smoke/McpRegistered.java")
        node("sources/smoke/McpToolsVisible.java")

        node("sources/smoke/EchoHttpRegistered.java")
        node("sources/smoke/EchoHttpDeployed.java")
        node("sources/smoke/McpToolSearchFinds.java")
        node("sources/smoke/McpToolInvoked.java")
        node("sources/smoke/EchoHttpRedeployed.java")

        node("sources/smoke/AgentsSynced.java")
        node("sources/smoke/AgentConfigsCorrect.java")

        node("sources/smoke/SmokeReport.java")
        node("sources/smoke/ServersDown.java")
        node("sources/common/PostgresDown.java").dependsOn("servers.down")
    }

    testGraph("sponsored") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/common/CiLoggedIn.java")
        node("sources/common/JwtValid.java")

        node("sources/sponsored/ReviewerPublished.java")
        node("sources/sponsored/FormatterPublished.java")
        node("sources/sponsored/CampaignsCreated.java")

        node("sources/sponsored/SponsoredSearchMatchesKeyword.java")
        node("sources/sponsored/SponsoredNoAdsSuppresses.java")
        node("sources/sponsored/SponsoredOrganicUnchanged.java")
        node("sources/sponsored/SponsoredHigherBidWins.java")

        node("sources/sponsored/SponsoredTeardown.java")
        node("sources/common/PostgresDown.java").dependsOn("sponsored.teardown")
    }
}
