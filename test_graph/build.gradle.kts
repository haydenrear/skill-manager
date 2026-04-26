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
        node("sources/smoke/OwnershipRecorded.java")
        node("sources/smoke/SemverEnforced.java")
        node("sources/smoke/ImmutabilityEnforced.java")

        node("sources/smoke/UmbrellaInstalled.java")
        node("sources/smoke/TransitiveClisPresent.java")
        node("sources/smoke/EnvScriptReports.java")

        // Install a skill whose MCP dep points at the echo fixture (scope =
        // global-sticky). Registration happens transitively via skill install.
        node("sources/smoke/EchoHttpSkillInstalled.java")

        // Assertions over the deployed echo server reached via a real MCP
        // client (TgMcp) — no CLI passthrough.
        node("sources/smoke/EchoHttpDeployed.java")
        node("sources/smoke/McpToolsVisible.java")
        node("sources/smoke/McpToolSearchFinds.java")
        node("sources/smoke/McpToolInvoked.java")
        node("sources/smoke/EchoHttpRedeployed.java")

        // Deploy-per-session semantics. Each pair installs a throwaway
        // fixture skill at a specific scope and asserts isolation / global
        // visibility via TgMcp calls against two distinct sessions.
        node("sources/smoke/EchoSessionSkillInstalled.java")
        node("sources/smoke/McpSessionScopeIsolated.java")
        node("sources/smoke/EchoGlobalSkillInstalled.java")
        node("sources/smoke/McpGlobalScopeVisible.java")

        node("sources/smoke/AgentConfigsCorrect.java")

        node("sources/smoke/SmokeReport.java")
        node("sources/smoke/ServersDown.java")
        node("sources/common/PostgresDown.java").dependsOn("servers.down")
    }

    /*
     * `browser-auth` — exercises the authorization_code + PKCE flow
     * end-to-end through a real headless Chrome. Heavier than the other
     * graphs (pulls Selenium + chromedriver) and run on demand rather
     * than on every commit:
     *
     *   ./gradlew browser-auth
     */
    testGraph("browser-auth") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/common/SeleniumReady.java")
        node("sources/common/AccountCreated.java")
        node("sources/browser-auth/BrowserAuthorized.java")
        node("sources/common/PostgresDown.java").dependsOn("browser.authorized")
    }

    /*
     * `password-reset` — full self-serve password-reset flow end-to-end:
     * account.created → initial.login → reset.requested → password.changed
     * → final.login. Reads the reset token straight from Postgres so the
     * graph is mail-free. Like browser-auth it pulls Selenium +
     * chromedriver; run on demand, not every commit.
     */
    /*
     * `refresh-flow` — forces a 3-second access-token TTL on the server
     * so we can exercise the refresh_token grant under real expiry
     * (rather than corruption) end-to-end. Pulls Selenium like the
     * browser-auth graph; run on demand.
     */
    testGraph("refresh-flow") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        node("sources/refresh-flow/ShortAccessTokenTtl.java")
        node("sources/common/RegistryUp.java").dependsOn("short.access.token.ttl")
        node("sources/common/SeleniumReady.java")
        node("sources/common/AccountCreated.java")
        node("sources/refresh-flow/RefreshOnExpiry.java")
        node("sources/common/PostgresDown.java").dependsOn("refresh.on.expiry")
    }

    testGraph("password-reset") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/common/SeleniumReady.java")
        node("sources/common/AccountCreated.java")
        node("sources/password-reset/InitialLogin.java")
        node("sources/password-reset/ResetRequested.java")
        node("sources/password-reset/PasswordChanged.java")
        node("sources/password-reset/FinalLogin.java")
        node("sources/password-reset/RefreshHonored.java")
        node("sources/common/PostgresDown.java").dependsOn("refresh.honored")
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
