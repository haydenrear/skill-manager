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
        // Materialize virtual-mcp-gateway/.venv before gateway.up so a
        // fresh checkout doesn't crash with "ModuleNotFoundError: uvicorn".
        // Idempotent — uv sync short-circuits on a populated lock.
        node("sources/common/GatewayPythonVenvReady.java")
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

        // Validate the unified ToolDependency / EnsureTool path on the
        // MCP side: install a fixture that declares one MCP load per
        // non-binary type (npm, uv, docker), then assert that the
        // install pipeline bundled the right runtimes under
        // $SKILL_MANAGER_HOME/pm/ and registered all three servers
        // with the gateway. This is the MCP analogue of
        // umbrella.installed → transitive.clis.present.
        node("sources/smoke/McpToolLoadsInstalled.java")
        node("sources/smoke/McpToolLoadsBundled.java")

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

        // Lock in the install-time symlink contract: every install must
        // drop <CLAUDE_HOME>/.claude/skills/<name> and
        // <CODEX_HOME>/skills/<name> symlinks pointing at the store path.
        node("sources/smoke/AgentSkillSymlinks.java")

        node("sources/smoke/SmokeReport.java")
        node("sources/common/ServersDown.java").dependsOn("smoke.report")
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
        node("sources/common/GatewayPythonVenvReady.java")
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
        node("sources/common/GatewayPythonVenvReady.java")
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
        node("sources/common/GatewayPythonVenvReady.java")
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

    /*
     * `hyper-experiments` — onboarding round-trip for the
     * hyper-experiments-skill repo:
     *
     *   1. git clone (or copy from HYPER_LOCAL_DIR) the source.
     *   2. publish to the per-run registry as `hyper-experiments`.
     *   3. install by name from a fresh cwd (not the checkout).
     *   4. assert tb-query CLI dep landed under bin/cli/.
     *   5. assert runpod MCP dep is registered with the gateway.
     *   6. teardown.
     *
     * Kept off the default `smoke` graph because it pulls a remote
     * source by default; opt in explicitly:
     *
     *   ./gradlew hyper-experiments
     *   HYPER_LOCAL_DIR=/path/to/hyper-experiments-skill ./gradlew hyper-experiments
     *
     * Documented as a case study in
     * skill-publisher-skill/references/runpod-mcp-onboarding.md.
     */
    testGraph("hyper-experiments") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        // Flips SKILL_REGISTRY_ALLOW_FILE_UPLOAD=false on the registry
        // server so this graph exercises the github-only publish path
        // end-to-end (the production default).
        node("sources/hyper/EnvPreparedHyper.java")
        node("sources/common/RegistryUp.java")
                .dependsOn("env.hyper.prepared")
        node("sources/common/CiLoggedIn.java")
        node("sources/common/JwtValid.java")
        // Bring the gateway venv up before the gateway itself.
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/smoke/GatewayUp.java")

        node("sources/hyper/HyperCheckout.java")
        node("sources/hyper/HyperPublished.java")
        // Asserts the registry persisted a github pointer only — no tarball
        // bytes on disk, sha256/size_bytes null in skill_versions for the
        // hyper-experiments rows.
        node("sources/hyper/HyperRegistryNoTarball.java")
        node("sources/hyper/HyperInstalled.java")
        node("sources/hyper/HyperCliTbquery.java")
        node("sources/hyper/HyperRunpodRegistered.java")
        // After install with X_RUNPOD_KEY in env, runpod auto-deployed.
        // Enumerate its tools, then invoke list-gpu-types to prove the
        // npx subprocess actually started, the API key reached it via
        // the install-time env-init path, and the gateway can talk to
        // it. Each step dumps its full response to a log artifact.
        node("sources/hyper/HyperRunpodDeployed.java")
        node("sources/hyper/HyperRunpodTools.java")
        node("sources/hyper/HyperRunpodToolInvoked.java")

        node("sources/common/ServersDown.java")
                .dependsOn("hyper.cli.tbquery", "hyper.runpod.tool.invoked")
        node("sources/common/PostgresDown.java").dependsOn("servers.down")
    }

    /*
     * `onboard` — single-command bootstrap path that ships in the CLI as
     * `skill-manager onboard`. Two halves:
     *
     *   1. The Spring `SkillBootstrapper` bean has seeded
     *      `skill-manager` and `skill-publisher` into the registry by
     *      the time `registry.up` reports healthy
     *      (`onboard.seeded.by.server`).
     *   2. The CLI command actually installs both skills end-to-end
     *      and leaves the gateway up
     *      (`onboard.completed` → `onboard.skills.installed` /
     *       `onboard.gateway.healthy`).
     */
    testGraph("onboard") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/common/CiLoggedIn.java")
        node("sources/common/JwtValid.java")

        node("sources/onboard/OnboardSeededByServer.java")
        node("sources/onboard/OnboardCompleted.java")
        node("sources/onboard/OnboardSkillsInstalled.java")
        node("sources/onboard/OnboardGatewayHealthy.java")
        node("sources/onboard/OnboardAgentConfigsWritten.java")

        node("sources/common/ServersDown.java")
                .dependsOn("onboard.skills.installed", "onboard.gateway.healthy",
                        "onboard.agent.configs.written")
        node("sources/common/PostgresDown.java").dependsOn("servers.down")
    }

    testGraph("sponsored") {
        node("sources/common/GatewayPythonVenvReady.java")
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
