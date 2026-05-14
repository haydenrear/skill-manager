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
        node("sources/smoke/MarkdownImportViolationsReported.java")

        node("sources/smoke/UmbrellaInstalled.java")
        node("sources/smoke/TransitiveClisPresent.java")
        // skill-script CLI backend coverage. Direct: install a fixture
        // whose `skill-scripts/install.sh` touches a sentinel under
        // bin/cli/. Transitive: install an umbrella whose only file:
        // reference declares the skill-script dep — proves the backend
        // fires for sub-skills resolved transitively, not just the
        // top-level install target.
        node("sources/smoke/SkillScriptInstalled.java")
        node("sources/smoke/SkillScriptTransitive.java")
        // Locks in the content-fingerprint rerun gate: install once,
        // sync (no script change → SKIP), edit install.sh, sync
        // again (script change → re-fire). Runs in a private
        // SKILL_MANAGER_HOME under env.prepared so the rest of the
        // smoke graph's lock state isn't disturbed.
        node("sources/smoke/SkillScriptRerunsOnChange.java")
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

        // Stdio MCP coverage. The gateway's StdioMCPClient owns its
        // session in a dedicated worker task — these nodes prove the
        // worker model handles parallel invocations correctly at both
        // global-sticky scope (one shared subprocess) and session
        // scope (one subprocess per agent session).
        node("sources/smoke/EchoStdioSkillInstalled.java")
        node("sources/smoke/McpStdioToolInvoked.java")
        node("sources/smoke/McpStdioParallelGlobalSticky.java")
        node("sources/smoke/EchoStdioSessionSkillInstalled.java")
        node("sources/smoke/McpStdioParallelSession.java")

        // CLI lifecycle commands beyond install:
        //   - sync repairs install-time invariants (drifted symlinks,
        //     missed MCP deploys after env change),
        //   - bind / unbind drop / remove an EXPLICIT skill binding
        //     into a custom project root alongside the DEFAULT_AGENT
        //     bindings install wrote (ticket-49 EXPLICIT path),
        //   - uninstall is the full counterpart to install (store +
        //     symlinks + orphan MCP unregister),
        //   - upgrade rolls back to the prior version when the new one
        //     fails to install.
        node("sources/smoke/SkillSynced.java")
        node("sources/smoke/SkillBindUnbindCycle.java")
        node("sources/smoke/SkillUninstalled.java")

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

        // Source-provenance + sync against the real github-published
        // hyper-experiments install — exercises the implicit-origin
        // path (install pins the github URL as the source-record
        // origin, so sync without --from just works):
        //   1. recorded — kind=GIT, origin=github URL, hash=HEAD
        //   2. clean.noop — sync after install succeeds, no working-tree
        //      drift
        //   3. refuses.on.local.commit — reset to HEAD~1 + add a unique
        //      local commit; sync (no --merge) exits 7 with --merge in
        //      its banner
        //   4. merges.after.commit — sync --merge succeeds, the local
        //      file survives the 3-way merge, source-record hash
        //      refreshed
        node("sources/hyper/HyperSourceRecorded.java")
        // DB-side round-trip: the postgres row for hyper@<version>
        // has the same gitSha that install wrote into the
        // sources/<name>.json record AND that the install dir's
        // `git rev-parse HEAD` reports. If any of the three drift,
        // server-versioned sync would target the wrong commit.
        node("sources/hyper/HyperServerHashMatchesInstall.java")
        node("sources/hyper/HyperSyncCleanNoOp.java")
        node("sources/hyper/HyperSyncRefusesOnLocalCommit.java")
        node("sources/hyper/HyperSyncMergesAfterCommit.java")

        node("sources/common/ServersDown.java")
                .dependsOn("hyper.cli.tbquery", "hyper.runpod.tool.invoked",
                        "hyper.sync.merges.after.commit")
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

    /*
     * `source-tracking` — covers per-skill git provenance tracking +
     * `skill-manager sync --from <dir>` / `--merge` against a
     * file-coordinate install. Self-contained: stamps a git skill into
     * a temp dir, installs it via `file:`, then exercises:
     *
     *   - install records sources/<name>.json with kind=GIT
     *   - sync --from on a dirty store refuses (exit 7) with structured
     *     merge instructions
     *   - sync --from --merge with a non-conflicting upstream commit
     *     succeeds, preserving the local edit and advancing the recorded
     *     gitHash
     *   - sync --from --merge with conflicting commits exits 8 and
     *     leaves the working tree in the conflicted state with
     *     <<<< / >>>> markers
     *
     * No registry / postgres needed — only env.prepared + the gateway
     * (install touches it). The github side of the same flow is tested
     * by the `hyper-experiments` graph.
     */
    testGraph("source-tracking") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/smoke/GatewayUp.java")

        node("sources/source-tracking/SourceFixturePublished.java")
        node("sources/source-tracking/SourceFixtureInstalled.java")
        node("sources/source-tracking/SourceSyncRefusesOnDirty.java")
        // Same dirty store, but without --from — exercises the
        // implicit-origin path that uses the source-record's pinned
        // origin (the fixture path).
        node("sources/source-tracking/SourceSyncRefusesWithoutFrom.java")
        node("sources/source-tracking/SourceSyncMergesClean.java")
        node("sources/source-tracking/SourceSyncProducesConflict.java")
        // `skill-manager sync` (no name) iterates every git-tracked
        // install through the implicit-origin pull, accumulates the
        // refused/conflicted ones, and emits a single aggregate
        // summary at the end. By this point in the graph the fixture
        // has commits ahead of the install-time baseline, so it
        // shows up as needing --merge.
        node("sources/source-tracking/SourceSyncAllAggregates.java")

        node("sources/common/ServersDown.java")
                .dependsOn("source.sync.all_aggregates")
    }

    /*
     * `git-latest-source-tracking` — exercises `skill-manager sync …
     * --git-latest` end-to-end against a self-contained file: git
     * fixture (no registry needed). Covers:
     *
     *   1. fixture.bootstrapped + fixture.installed — install pins
     *      origin to the fixture path; .git/ + sources/<name>.json
     *      land correctly.
     *   2. fast_forwards — fixture advances upstream; sync --git-latest
     *      brings the install up to date (clean fast-forward), source
     *      record gitHash refreshes.
     *   3. refuses_on_local_commit — local commit on top of upstream;
     *      sync --git-latest (no --merge) exits 7 with the recipe
     *      preserving the --git-latest flag in its suggested re-run.
     *   4. merges_after_local_commit — sync --git-latest --merge,
     *      with another non-conflicting upstream commit, succeeds via
     *      a real 3-way merge; local commit survives.
     *   5. conflict — diverging edits to SKILL.md on both sides;
     *      --git-latest --merge exits 8, working tree shows
     *      UU SKILL.md with standard <<<< / ==== / >>>> markers.
     */
    testGraph("git-latest-source-tracking") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/smoke/GatewayUp.java")

        node("sources/git-latest-source-tracking/GlsFixtureBootstrapped.java")
        node("sources/git-latest-source-tracking/GlsFixtureInstalled.java")
        node("sources/git-latest-source-tracking/GlsFastForwards.java")
        node("sources/git-latest-source-tracking/GlsRefusesOnLocalCommit.java")
        node("sources/git-latest-source-tracking/GlsMergesAfterLocalCommit.java")
        node("sources/git-latest-source-tracking/GlsConflict.java")

        node("sources/common/ServersDown.java")
                .dependsOn("gls.conflict")
    }

    /**
     * Plugin smoke graph (ticket 15 — minimal subset).
     *
     * Exercises the plugin install pipeline end-to-end: publish a
     * plugin bundle, install it, and verify the install lands the
     * plugin under plugins/ (not skills/), the lock advances with the
     * right kind, the Claude projector symlink lands, and the
     * contained skill is NOT separately addressable from the registry.
     *
     * Kept off the default `smoke` graph today because it depends on
     * server-side support for plugin bundles (server-java/ unit_kind
     * column migration is deferred per ticket 13). Will start passing
     * once that lands. The full sweep of *Plugin* parallel nodes
     * (HelloPluginPublished is one of ~30 the ticket calls for) is its
     * own follow-up — these three prove the end-to-end install path
     * and document the pattern.
     *
     *   ./gradlew plugin-smoke
     */
    testGraph("plugin-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/common/CiLoggedIn.java")
        node("sources/common/JwtValid.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/smoke/GatewayUp.java")
        // Echo MCP fixture — needed by the umbrella-plugin install
        // (both plugin-level and contained-skill MCP deps target this
        // server), and by the partner skill that re-claims the
        // plugin-level server name.
        node("sources/smoke/EchoHttpUp.java")

        node("sources/smoke/HelloPluginPublished.java")
        node("sources/smoke/HelloPluginInstalled.java")
        node("sources/smoke/HelloPluginRegisteredWithHarness.java")
        node("sources/smoke/plugin/PluginContainedSkillNotAddressable.java")

        // Plugin install with both plugin-level and contained-skill
        // CLI + MCP deps — exercises the install pipeline's walk and
        // proves feature parity with bare skills (every dep registers).
        node("sources/smoke/UmbrellaPluginInstalled.java")
        // Plugin sync — drift the marketplace, sync, assert restored.
        node("sources/smoke/plugin/PluginSynced.java")
        // Sibling skill that claims the umbrella plugin's plugin-level
        // MCP server. The orphan check on the upcoming plugin uninstall
        // must see this skill's claim and keep that server alive.
        node("sources/smoke/PartnerSkillInstalled.java")
        // Kind-aware commands should keep seeing plugins before teardown.
        node("sources/smoke/plugin/PluginCommandCoverage.java")
        // Plugin uninstall with mixed orphan/non-orphan deps.
        node("sources/smoke/plugin/PluginUninstalledMixedOrphans.java")

        node("sources/common/ServersDown.java")
                .dependsOn("plugin.contained.skill.not.addressable",
                        "plugin.uninstalled.mixed.orphans")
    }

    // -------------------------------------------------------- doc-smoke
    //
    // Ticket-48 (doc-repos) + ticket-49 (bindings) end-to-end. No
    // gateway / MCP / registry needed — doc-repos go through the
    // local-install path (file://...) and bindings live entirely on
    // the local filesystem + the projection ledger.
    // -------------------------------------------------------- harness-smoke
    //
    // Ticket-47 end-to-end. A harness template scaffolded at test-time
    // references three transitive deps via `file://` coords:
    //   - pip-cli-skill (transitive CLI dep — pip:pycowsay)
    //   - hello-plugin  (transitive plugin + contained-skill union)
    //   - hello-doc-repo (transitive doc-repo)
    // One `install file://...` pulls every transitive unit in, then
    // instantiate / rm / uninstall exercise the full lifecycle.
    testGraph("harness-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/smoke/GatewayUp.java")
        node("sources/smoke/harness/HarnessTransitiveInstalled.java")
        node("sources/smoke/harness/HarnessInstanceMaterialized.java")
        node("sources/smoke/harness/HarnessCommandCoverage.java")
        node("sources/smoke/harness/HarnessInstanceRemoved.java")
        node("sources/smoke/harness/HarnessTemplateUninstalled.java")
        node("sources/common/ServersDown.java")
                .dependsOn("harness.template.uninstalled")
    }

    testGraph("doc-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/smoke/doc/DocRepoInstalled.java")
        node("sources/smoke/doc/DocBoundToProject.java")
        node("sources/smoke/doc/DocSyncUpgrade.java")
        node("sources/smoke/doc/DocSyncLocalEditPreserved.java")
        node("sources/smoke/doc/DocSyncForceClobbers.java")
        node("sources/smoke/doc/DocUnbindCleansUp.java")
        // Multi-source bind/unbind dance: bind both [[sources]],
        // unbind one (verify the other survives), unbind the last
        // (verify the managed section + docs/agents/ dir get pruned),
        // re-bind (verify everything recreates from scratch), then
        // uninstall the doc-repo entirely.
        node("sources/smoke/doc/DocBindTwoSources.java")
        node("sources/smoke/doc/DocUnbindOneOfTwo.java")
        node("sources/smoke/doc/DocUnbindLastSectionAndDirGone.java")
        node("sources/smoke/doc/DocRebindAfterAllRemoved.java")
        node("sources/smoke/doc/DocCommandCoverage.java")
        node("sources/smoke/doc/DocRepoUninstalled.java")
    }
}
