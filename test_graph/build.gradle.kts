plugins {
    id("com.hayden.testgraphsdk.graph")
}

/**
 * Two integration test graphs for skill-manager.
 *
 *   ./gradlew smoke       full registry + gateway + MCP flow (20 nodes)
 *   ./gradlew sponsored   registry-only ad auction (9 nodes)
 *
 * Validate first:
 *   ./gradlew validationPlanGraph --name=smoke
 *   ./gradlew validationPlanGraph --name=sponsored
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

    /*
     * `sponsored` — covers the ad-auction lane in the skill registry:
     * publish two skills, create three campaigns (including a competing
     * pair on the same keyword), then assert keyword match, no_ads
     * suppression, organic lane stability, and highest-bid-wins.
     */
    testGraph("sponsored") {
        node("sources/EnvPrepared.java")
        node("sources/RegistryUp.java")

        node("sources/ReviewerPublished.java")
        node("sources/FormatterPublished.java")
        node("sources/CampaignsCreated.java")

        node("sources/SponsoredSearchMatchesKeyword.java")
        node("sources/SponsoredNoAdsSuppresses.java")
        node("sources/SponsoredOrganicUnchanged.java")
        node("sources/SponsoredHigherBidWins.java")

        node("sources/SponsoredTeardown.java")
    }
}
