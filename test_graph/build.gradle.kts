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
        node("sources/resolve/ResolverCyclesVerified.java")
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
        node("sources/smoke/CrossKindMarkdownImports.java")

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
        // Force path: install/sync rerun the script even when the
        // fingerprint and declared binary already match.
        node("sources/smoke/SkillScriptForceRerun.java")
        // Composite force-sync path: a skill with both skill-script CLI
        // and MCP deps must skip on noop sync, rerun the script under
        // --force-scripts, and still re-register MCP.
        node("sources/smoke/SkillScriptForceSyncWithMcp.java")
        // Uninstall path: a skill-script dep's orphaned bin/cli artifact
        // and cli-lock row are pruned after the owning skill is removed.
        node("sources/smoke/SkillScriptUninstallPrunesCli.java")
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
     *      `skill-manager`, `skill-publisher`, and `skill-dev-skill`
     *      into the registry by
     *      the time `registry.up` reports healthy
     *      (`onboard.seeded.by.server`).
     *   2. The CLI command actually installs the bundled skills end-to-end
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
     *   - sync --git-latest after an externally resolved merge refreshes
     *     the stale source record without printing a no-op --merge recipe
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
        node("sources/source-tracking/SourceSyncNoMergeWhenAlreadyMerged.java")
        node("sources/source-tracking/SourceSyncProducesConflict.java")
        // `skill-manager sync` (no name) iterates every git-tracked
        // install through the implicit-origin pull, accumulates the
        // refused/conflicted ones, and emits a single aggregate
        // summary at the end. By this point in the graph the fixture
        // has commits ahead of the install-time baseline, so it
        // shows up as needing --merge.
        node("sources/source-tracking/SourceSyncAllAggregates.java")
        // Issue #128: a recorded TRANSITIVE_RESOLVE_FAILED must clear
        // on the next sync after the offending reference is removed.
        // Self-contained fixture, so it runs after the aggregate sweep.
        node("sources/source-tracking/SourceSyncClearsStaleTransitiveError.java")

        node("sources/common/ServersDown.java")
                .dependsOn("source.sync.clears_stale_transitive_error")
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
        node("sources/resolve/ResolverCyclesVerified.java")
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
        // Markdown imports from plugin-level docs can target docs,
        // harnesses, and other plugins. Includes one missing plugin
        // import to prove plugin markdown is parsed and reported.
        node("sources/smoke/plugin/PluginMarkdownImportTargets.java")
        // Plugin uninstall with mixed orphan/non-orphan deps.
        node("sources/smoke/plugin/PluginUninstalledMixedOrphans.java")
        // Composite plugin force-sync path: plugin-level pip CLI, MCP,
        // and skill-script deps together; --force-scripts must rerun
        // only the skill-script dep while sync still refreshes MCP.
        node("sources/smoke/plugin/PluginSkillScriptForceSync.java")

        node("sources/common/ServersDown.java")
                .dependsOn("plugin.contained.skill.not.addressable",
                        "plugin.markdown.import.targets",
                        "plugin.uninstalled.mixed.orphans",
                        "plugin.skill_script.force.sync")
        node("sources/common/PostgresDown.java").dependsOn("servers.down")
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
        node("sources/resolve/ResolverCyclesVerified.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/smoke/GatewayUp.java")
        node("sources/smoke/harness/HarnessTransitiveInstalled.java")
        node("sources/smoke/harness/HarnessInstanceMaterialized.java")
        node("sources/smoke/harness/HarnessChildHomeMaterialized.java")
        node("sources/smoke/harness/HarnessCommandCoverage.java")
        node("sources/smoke/harness/HarnessInstanceRemoved.java")
        node("sources/smoke/harness/HarnessChildHomeRemoved.java")
        node("sources/smoke/harness/HarnessTemplateUninstalled.java")
        node("sources/common/ServersDown.java")
                .dependsOn("harness.template.uninstalled")
    }

    testGraph("doc-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/resolve/ResolverCyclesVerified.java")
        node("sources/smoke/doc/CliMetadataCatalogCovered.java")
        node("sources/smoke/doc/CliSkillDocsCatalogCovered.java")
        node("sources/smoke/doc/CliHelpProgressiveDisclosure.java")
        node("sources/smoke/doc/CliAgentContextOutput.java")
        node("sources/smoke/doc/SkillManagerSkillDocsProjected.java")
        node("sources/smoke/doc/SkillManagerEnvReportsProjectContext.java")
        node("sources/smoke/doc/DocRepoInstalled.java")
        // Markdown imports from doc-repo source markdown can target
        // skills, harnesses, and other doc-repos. Includes one missing
        // doc import to prove doc source markdown is parsed and reported.
        node("sources/smoke/doc/DocMarkdownImportTargets.java")
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

    testGraph("project-manifest") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectManifestRegistered.java")
    }

    testGraph("project-resolve") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectDependenciesResolved.java")
    }

    testGraph("project-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/resolve/ResolverCyclesVerified.java")
        node("sources/project/ProjectDependenciesResolved.java")
        node("sources/project/ProjectGlobalSyncCliRefresh.java")
        node("sources/project/ProjectLocalSyncCliRefresh.java")
    }

    // Child-home materialization contract, driven end to end through the real
    // `skill-manager` CLI against throwaway projects. Mirrors the External /
    // Internal invariants in specs/desired_program_model:
    //
    //   ChildHomeWritesNeverReachTheParentStore    independence
    //   AgentEditedChildUnitsAreNeverDestroyed     no silent destruction
    //   EveryPassReportsExactlyTheHeldBackUnits    reporting
    //   UnmodifiedChildUnitsConvergeOnTheirSource  convergence
    //   ResolveLeavesOnlyClaimedOrHeldBackUnits    prune scope
    //   InFlightMaterializationLeavesTheChildUnitIntact  atomicity
    //
    // Strictly ordered: each node builds on the child-home state the previous
    // one left behind, and the agent's edited bytes are carried forward through
    // published context so a later node can prove they are still there.
    testGraph("project-child-home") {
        node("sources/common/EnvPrepared.java")
        node("sources/child-home/ChildHomeProjectFixture.java")
        node("sources/child-home/ChildHomeResolved.java")
        node("sources/child-home/ChildHomeUnitsIndependent.java")
        node("sources/child-home/ChildHomeUnitEdited.java")
        node("sources/child-home/ChildHomeEditStaysInChildHome.java")
        node("sources/child-home/ChildHomeResolvePreservesEdits.java")
        node("sources/child-home/ChildHomeSyncPreservesEdits.java")
        node("sources/child-home/ChildHomePrunePreservesEdits.java")
        node("sources/child-home/ChildHomeConvergesOnSource.java")
        node("sources/child-home/ChildHomeMaterializationAtomic.java")
    }

    // ---------------------------------------------------------------------
    // home-clone: HOME-level isolation, T19 / #20.
    //
    // Where project-child-home asserts that the UNITS inside a home are
    // independent, this graph asserts that a HOME is a pure function of
    // SKILL_MANAGER_HOME: copy it into a project, point the env var at the copy,
    // and the copy touches neither the home it came from nor ~/.claude.
    //
    // Drives the real CLI over a SYNTHETIC fixture home (the developer's is
    // 5.4 GB and /private/tmp has less free space than that; it is also the home
    // the standing constraint on #1 forbids writing to). The fixture carries one
    // artifact per problem class #20 measured: an absolute in-unit symlink into
    // the store, an absolute bin/cli symlink, a shim with the home path in its
    // body, a shim whose target is under a skipped root, a pm/ entry, a venvs/
    // entry, and an authored file that legitimately records an absolute path.
    //
    // Invariants from specs/desired_program_model/External.tla (HomeSpec):
    //   SourceHomeIsByteIdenticalToItsCloneTimeSelf
    //   AHomeIsAPureFunctionOfItsRoot
    //   NoOwnedSurfaceNamesAnotherHome
    //   AuthoredContentIsNeverRewritten
    //   ToolchainRootsAreNeverShared
    //   AHomeMissingItsToolchainsStillHasItsPackageManagers
    //   EveryHomeMissingItsToolchainsSaysSo
    //
    // Strictly ordered: each node builds on what the previous one left on disk,
    // and the source home's digest is carried forward so every later node can
    // prove the source is still byte-identical rather than trusting a report.
    testGraph("home-clone") {
        node("sources/common/EnvPrepared.java")
        node("sources/home-clone/HomeCloneFixtureBuilt.java")
        node("sources/home-clone/HomeClonedIntoProject.java")
        // Shape of the copy first, then what happens when it is used. Every
        // skill-manager command calls SkillStore.init(), so a node asserting the
        // clone's layout has to run before one that drives the CLI through it.
        node("sources/home-clone/HomeCloneToolchainsReprovisionable.java")
        node("sources/home-clone/HomeCloneDescriptorResolves.java")
        node("sources/home-clone/HomeCloneEditStaysInClone.java")
        node("sources/home-clone/HomeCloneWorksWithSourceRenamed.java")
        node("sources/home-clone/HomeCloneNoAgentHomeLeak.java")
        // The undeclared property this home model rests on, with an oracle
        // rather than a comment. It does not depend on the fixture above: the
        // cost node needs a dedicated volume nobody else writes to.
        //
        // IT CARRIES NO SPEC INVARIANT, deliberately (T57 / #60): the clone
        // COST is a resource property — blocks consumed on a filesystem. TLA+
        // cannot express it and TLC cannot check it, so the only honest oracle
        // is a measurement.
        //
        // That measurement used to be free space on a dedicated APFS sparse
        // image, and it was flaky, because a sparse image's available space is
        // bounded by the free space of the disk backing it — so the operator's
        // own writes leaked into the "dedicated" volume. It now reads block
        // sharing per file instead (sources/lib/extentprobe.py), which nothing
        // else on the host can perturb, and carries a hard-link control that
        // must read as shared plus a byte-copy control that must read as not
        // shared, in the same run as the measurement.
        //
        // See specs/desired_program_model/External.tla for what HomeSpec does
        // cover, and issue #60 for the decision.
        node("sources/home-clone/HomeCloneCostsFarLessThanACopy.java")
        // The other side of the same economics. HomeCloneCostsFarLessThanACopy
        // asserts that copying a home is cheap; these two assert that the
        // PACKAGE STORE the home installs out of is not copied per home at
        // all, and that what is materialized out of it is materialized by
        // reference.
        //
        // Split in two on purpose. The contract node is deterministic, needs
        // no network, and is the one that fails when somebody gives a home a
        // private cache — it is where the regression will actually be caught.
        // The cost node is the instrument that proves the contract is worth
        // having: apparent size, du, stat's block counts and link counts are
        // all identical whether a venv shares blocks or not, so only the
        // physical address of each file's blocks can tell. It still needs uv
        // and a network warm-up, and SKIPS with a stated reason when it cannot
        // get them, which is why it must not be the only guard.
        //
        // LIKE THE CLONE-COST NODE ABOVE, NEITHER CARRIES A SPEC INVARIANT:
        // "these bytes are shared" is a resource property, not a state
        // machine, and TLC cannot check it.
        node("sources/home-clone/SharedPackageCacheIsNotPrivateToTheHome.java")
        node("sources/home-clone/SharedStoreMaterializationCostsFarLessThanACopy.java")
    }

    /**
     * The home tripwire: the operator's four real agent homes are snapshotted,
     * a real install runs against a sandboxed home, and the snapshot is
     * re-taken and diffed.
     *
     * This replaces two scratchpad shell scripts that were run by hand around a
     * batch of work and that found four leak paths no assertion did — including
     * issue #18, where a projection reached the real ~/.claude and showed up in
     * a live agent's skill list. An oracle that fires only when someone
     * remembers to run it is not a regression guard.
     *
     * home.tripwire.sensitive is the reason to believe the rest: it plants one
     * mutation per observed defect class into a decoy home and asserts each is
     * detected, with an unmutated control asserted clean in the same run. It
     * has no dependency on the other three nodes and touches no real home, so
     * it keeps its meaning even if the bracket is ever skipped.
     *
     * sandbox.env.contract belongs here because it watches the same defect from
     * the other side. The tripwire sees a leak AFTER a node made it, in the run
     * that made it; the contract node fails on the CODE that would make one —
     * a node that spells the sandbox env itself, or spawns the CLI without it.
     * Issue #30 measured 50 such sites, so "every node remembers" is not a
     * property this suite can rely on being true. It has no dependencies and
     * touches no home, so it also keeps its meaning if the bracket is skipped.
     */
    testGraph("home-tripwire") {
        node("sources/sandbox/SandboxEnvContract.java")
        node("sources/tripwire/HomeTripwireSensitive.java")
        node("sources/common/EnvPrepared.java")
        // Armed AFTER env.prepared so the watched window is exactly this
        // graph's skill-manager work. A leak from shared fixture setup is a
        // finding about every graph, not about this one.
        node("sources/tripwire/HomeTripwireArmed.java").dependsOn("env.prepared")
        node("sources/tripwire/HomeTripwireWorkload.java")
        node("sources/tripwire/HomeTripwireChecked.java")
    }

    /**
     * The per-checkout home contract, formerly assert-home.sh — a scratchpad
     * script run by hand once at the end of ticket #10.
     *
     * Reuses home-clone's fixture and clone nodes rather than scaffolding a
     * second fixture home. The thing under test is the CONTRACT a provisioned
     * home has to satisfy, not a second way of producing one, and a duplicate
     * fixture would be a second idiom to keep in step (issue #24).
     */
    testGraph("checkout-home") {
        node("sources/common/EnvPrepared.java")
        node("sources/home-clone/HomeCloneFixtureBuilt.java")
        node("sources/home-clone/HomeClonedIntoProject.java")
        node("sources/checkout-home/CheckoutHomeProvisioned.java")
        node("sources/checkout-home/CheckoutHomeContract.java")
        node("sources/checkout-home/CheckoutHomeLaunchIsolated.java")
    }

    /**
     * home-sync: the return path between the three home tiers.
     *
     * Where home-clone asserts that a home is a pure function of its root, this
     * graph asserts what happens once there are THREE of them — the root home
     * the operator installs into, a copy per repository, and a copy per ticket
     * worktree — and edits have to flow back up as well as down. The regression
     * it guards is silent by construction: a ticket agent improves a skill in
     * its worktree home, the worktree is removed, and the teardown succeeds
     * exactly as loudly as it would have if there had been nothing to lose.
     *
     * All four directions, driven through the real CLI over real homes:
     *
     *   home.sync.root.to.project      scaffold + keep current, no project edit lost
     *   home.sync.project.to.root      the upward copy path, and `unit publish`
     *                                  to a LOCAL bare remote for the history path
     *   home.sync.worktree.to.project  the essential one: close-out refuses, the
     *                                  remedy it prints is EXECUTED, then it passes
     *   home.sync.project.to.worktree  picking up what the project learned after
     *                                  the branch, including a three-way merge
     *
     * plus the cases that belong to no direction (home.sync.permutations: dry
     * run, conflict, removed-upstream, no common ancestor, frozen home),
     * the two failure shapes (home.sync.hazards: an interrupted pass and two
     * concurrent ones), git state read from git rather than inferred
     * (home.sync.git.flawless), and the clone-of-an-edited-home baseline case
     * (home.sync.stale.baseline).
     *
     * home.sync.provenance is the two silent-data-loss defects of the baseline
     * rule as sequences rather than as states: CHM-9 needs three homes and a
     * teardown between two successful commands (sync up, close out, remove the
     * worktree, sync down) and CHM-10 needs the same source to merge twice.
     * Neither is reachable in one command, and both reported success while
     * destroying the ticket's work, so nothing shorter than the sequence can
     * assert they are gone.
     *
     * home.sync.round.trip is the same class of finding one tier wider:
     * CHM-12, where TWO ticket worktrees merge into one project home and the
     * ordinary push-up-then-pull-down through the root home destroys the
     * second one's edit while every command reports clean=true. The cause is
     * what a merge WRITES DOWN rather than which base it reads, so nothing
     * shorter than the six-command sequence across four homes turns it into a
     * lost byte.
     *
     * home.sync.homeness covers the two ways a report was clean about units it
     * had never looked at: a `--home` that is not a Skill Manager home at all
     * (the worktree DIRECTORY rather than the home inside it, which is exactly
     * what `git worktree remove` takes) contributed zero units and cleared the
     * teardown with exit 0; and a symlinked unit or kind directory was dropped
     * by the enumerator with no report, so a home whose skills/ was a link
     * reconciled as {"clean":true,"units":[]}.
     *
     * home.sync.sensitive is the reason to believe the rest: it plants one
     * mutation per defect class — an edit silently overwritten, a conflict
     * silently resolved, a dry run that writes, half a swap left behind, a
     * removed-upstream unit deleted — each on a fresh pair of real homes, and
     * asserts each is DETECTED by the same oracles the other nodes use, with
     * unmutated controls asserted clean in the same run. It runs first and
     * depends only on env.prepared, so it keeps its meaning independently of
     * everything below it.
     *
     * Strictly ordered from home.sync.fixture.built onward: each of the four
     * direction nodes builds on the home state the previous one left behind,
     * which is the only way to assert that a worktree closing out returns work
     * to a project home that has itself moved on.
     */
    testGraph("home-sync") {
        node("sources/common/EnvPrepared.java")
        node("sources/home-sync/HomeSyncSensitive.java")
        node("sources/home-sync/HomeSyncFixtureBuilt.java")
        node("sources/home-sync/HomeSyncRootToProject.java")
        node("sources/home-sync/HomeSyncProjectToRoot.java")
        node("sources/home-sync/HomeSyncWorktreeToProject.java")
        node("sources/home-sync/HomeSyncProjectToWorktree.java")
        node("sources/home-sync/HomeSyncGitFlawless.java")
        node("sources/home-sync/HomeSyncPermutations.java")
        node("sources/home-sync/HomeSyncHazards.java")
        node("sources/home-sync/HomeSyncStaleBaseline.java")
        node("sources/home-sync/HomeSyncProvenance.java")
        node("sources/home-sync/HomeSyncRoundTrip.java")
        node("sources/home-sync/HomeSyncHomeness.java")
        // A worktree that was USED. Issue #41: running the tooling inside a
        // unit — the thing a worktree exists to do — left .gradle/, __pycache__
        // and a built jar in it, and close-out then reported `conflicted` with
        // a remedy that exited 1 without clearing the gate. It RUNS a script
        // and asserts about whatever the run left behind, because a fixture
        // that writes the artefacts writes only the ones somebody thought of.
        // It also carries the guard on its own fix: a unit the agent COMMITTED
        // in must still block (issue #29).
        node("sources/home-sync/HomeSyncBuiltInUnit.java")
        // The two writers that are not `home sync` but write into the same
        // home: `project resolve`/`project sync` (CHM-15) and the agent-tree
        // projectors (CHM-16). Both live here because the defect in each is a
        // reconcile ordering, and this is the graph that owns "who may destroy
        // bytes in a home".
        node("sources/home-sync/HomeSyncProjectSeam.java")
        node("sources/home-sync/HomeSyncAuthoredAgentTree.java")
    }

    /**
     * ticket-lifecycle: the whole per-checkout-home ticket workflow, composed.
     *
     * Every piece of this workflow is covered in isolation and the COMPOSITION
     * of them was covered nowhere: before this graph existed,
     * `grep -rl 'close-change|new-change|propagate.sh' test_graph/sources/`
     * returned nothing. Every regression epic #2 found — a livelocked teardown,
     * a clone that refused its own first launch, a remedy that was not a
     * command — was found by a human running the workflow by hand, and each of
     * them lived in the seam between two repositories whose changes were
     * individually correct.
     *
     * So the scenario is the hard one rather than the happy path: TWO
     * concurrent tickets, each with its own worktree, both editing one shared
     * skill plus one distinct skill each, closing out into one project home at
     * the same time. Two tickets is the smallest configuration that can
     * exercise the conflict path, the HomeLock exclusion path, and "does the
     * next ticket agent get the change" at once.
     *
     * The model is the one part that is stubbed. No claude/codex/gemini process
     * is launched: the ticket agent is simulated by writing files into
     * <worktree>/.skill-manager/skills/<unit>/ and then invoking the same CLI
     * the real flow invokes. What is under test is the machinery around the
     * agent. The shims' RESOLUTION path is exercised, because that is machinery
     * rather than model.
     *
     * Strictly ordered from the fixture onward: each node builds on the disk
     * state the previous one left, which is the only way to assert that a
     * teardown is gated on work a previous node actually created.
     *
     * Two things this graph asserts that nothing else does, and how:
     *
     *   ticket.lifecycle.concurrent.close.out asserts on the LOCK, not on
     *   process wall-clock. A skill-manager process spends 2-3s in jbang/JVM
     *   startup before HomeLock.acquire is reached, so two runs whose locked
     *   sections are strictly ordered still show overlapping process windows,
     *   and a wall-clock oracle over them is simply wrong.
     *   sources/ticket-lifecycle/lockprobe.py reads the fcntl record lock the
     *   JVM actually takes, and F_GETLK names the holding pid, so the
     *   attribution is the kernel's rather than the test's.
     *
     *   Each assertion that could pass by not looking carries a companion
     *   proving it can fail, in the same run: the lock oracle is shown
     *   reporting an unserialised pair, the conflict oracle is shown detecting
     *   a silent clobber, the leak oracle is shown detecting a planted write,
     *   and the cache oracle is shown detecting a private cache. That is the
     *   defect class this epic hit four separate times.
     *
     * It drives scripts that live in the git-integration-repo skill, not in
     * this repository. They are located from the integration repo above this
     * checkout's git common dir — the common dir, because this repository is
     * normally worked on from a worktree deliberately placed OUTSIDE that
     * parent — with $TICKET_LIFECYCLE_SCRIPTS as the override. When they cannot
     * be found the fixture FAILS rather than skipping: the scripts are the
     * subject, and a run that could not find them measured nothing.
     */
    testGraph("ticket-lifecycle") {
        node("sources/common/EnvPrepared.java")
        node("sources/ticket-lifecycle/TicketLifecycleFixtureBuilt.java")
        node("sources/ticket-lifecycle/TicketLifecycleProvisioned.java")
        node("sources/ticket-lifecycle/TicketLifecycleFirstLaunch.java")
        node("sources/ticket-lifecycle/TicketLifecycleCaches.java")
        node("sources/ticket-lifecycle/TicketLifecycleAgentEdits.java")
        node("sources/ticket-lifecycle/TicketLifecycleConcurrentCloseOut.java")
        node("sources/ticket-lifecycle/TicketLifecycleConflict.java")
        node("sources/ticket-lifecycle/TicketLifecycleTeardown.java")
        node("sources/ticket-lifecycle/TicketLifecycleNextAgent.java")
        node("sources/ticket-lifecycle/TicketLifecyclePublish.java")
        // Last, because it compares the operator's real homes against a
        // baseline it takes at the start, across everything the whole workflow
        // did in between. It carries its own sensitivity proof, so it keeps its
        // meaning even if a node above it is skipped.
        node("sources/ticket-lifecycle/TicketLifecycleGlobalHomeUntouched.java")
    }

    testGraph("project-env") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectEnvMaterialized.java")
    }

    testGraph("project-libs") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectLibsResolved.java")
    }

    testGraph("project-profiles") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectProfilesResolved.java")
    }

    testGraph("skill-dev-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/resolve/ResolverCyclesVerified.java")
        node("sources/common/GatewayPythonVenvReady.java")
        node("sources/common/PostgresUp.java")
        node("sources/common/RegistryUp.java")
        node("sources/smoke/GatewayUp.java")

        node("sources/skill-dev/SkillDevInstalled.java")
        node("sources/skill-dev/SkillDevUnitsInstalled.java")
        node("sources/skill-dev/SkillDevEditSkill.java")
        node("sources/skill-dev/SkillDevEditPlugin.java")
        node("sources/skill-dev/SkillDevEditDocRepo.java")
        node("sources/skill-dev/SkillDevEditHarness.java")
        node("sources/skill-dev/SkillDevConflictResolved.java")

        node("sources/common/ServersDown.java")
                .dependsOn("skill-dev.edit.skill",
                        "skill-dev.edit.plugin",
                        "skill-dev.edit.doc",
                        "skill-dev.edit.harness",
                        "skill-dev.conflict.resolved")
        node("sources/common/PostgresDown.java").dependsOn("servers.down")
    }
}
