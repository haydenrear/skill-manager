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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("postgres.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("postgres.down")
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
     * This graph reaches THREE third-party services it does not control:
     * github.com (the clone in hyper.checkout), npm (the runpod MCP server
     * in hyper.installed), and the live RunPod API (hyper.runpod.*, which
     * needs X_RUNPOD_KEY). None of them answer a question about
     * skill-manager, so a failure here is not a release signal — and in
     * practice it was one, repeatedly: hyper.checkout timed out against
     * github during release validation for 0.21.0, and hyper.installed sits
     * on its own timeout doing a cold npm install (#143).
     *
     * The comment here has always said this graph is opt-in. Registration
     * did not agree: `testGraph(...)` puts a task into `ext.graphs`, and
     * `validationRunAll` fans out to every entry — so "run everything"
     * could not pass without network, a RunPod key, and luck. The opt-in is
     * now enforced rather than described.
     *
     * Opt in with either:
     *
     *   HYPER_EXPERIMENTS=1 ./gradlew hyper-experiments
     *   HYPER_LOCAL_DIR=/path/to/hyper-experiments-skill ./gradlew hyper-experiments
     *
     * HYPER_LOCAL_DIR also removes the github dependency, since
     * hyper.checkout copies that tree instead of cloning.
     *
     * Documented as a case study in
     * skill-publisher-skill/references/runpod-mcp-onboarding.md.
     */
    val hyperOptIn = !System.getenv("HYPER_LOCAL_DIR").isNullOrBlank()
            || System.getenv("HYPER_EXPERIMENTS") == "1"
    if (!hyperOptIn) {
        // Register a task under the same name so `./gradlew hyper-experiments`
        // explains itself instead of failing with "task not found". This is
        // deliberately NOT a `testGraph(...)` call: staying out of
        // `ext.graphs` is the whole point, since that map is what
        // `validationRunAll` walks.
        tasks.register("hyper-experiments") {
            group = "validation"
            description = "Third-party onboarding round-trip (opt-in: needs github, npm, RunPod)."
            doFirst {
                throw GradleException(
                    "hyper-experiments talks to github, npm and the live RunPod API, " +
                    "so it is opt-in and excluded from validationRunAll.\n" +
                    "  HYPER_EXPERIMENTS=1 ./gradlew hyper-experiments\n" +
                    "  HYPER_LOCAL_DIR=<hyper-experiments-skill checkout> ./gradlew hyper-experiments  " +
                    "(no github clone)"
                )
            }
        }
    } else testGraph("hyper-experiments") {
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("postgres.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("postgres.down")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("servers.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("servers.down")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("servers.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("servers.down")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("postgres.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("postgres.down")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("servers.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("servers.down")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("doc.repo.uninstalled")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("doc.repo.uninstalled")
    }

    testGraph("project-manifest") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectManifestRegistered.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("project.manifest.registered")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("project.manifest.registered")
    }

    testGraph("project-resolve") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectDependenciesResolved.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("project.dependencies.resolved")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("project.dependencies.resolved")
    }

    testGraph("project-smoke") {
        node("sources/common/EnvPrepared.java")
        node("sources/resolve/ResolverCyclesVerified.java")
        node("sources/project/ProjectDependenciesResolved.java")
        node("sources/project/ProjectGlobalSyncCliRefresh.java")
        node("sources/project/ProjectLocalSyncCliRefresh.java")
        node("sources/project/ProjectPinnedSyncRevisionCoherent.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn(
            "project.local.sync.cli.refresh",
            "project.pinned.sync.revision.coherent",
        )
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn(
            "project.local.sync.cli.refresh",
            "project.pinned.sync.revision.coherent",
        )
    }

    // Public CLI adapter for the bounded External model's named-sync action.
    // Formal TLC and spec-unit validation remain outside Test Graph; this graph
    // owns only the production A/B behavior and its durable provenance.
    testGraph("spec-conformance") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectPinnedSyncRevisionCoherent.java")
        node("sources/common/HomeFixpointLaw.java").dependsOn("project.pinned.sync.revision.coherent")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("project.pinned.sync.revision.coherent")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("child.home.materialization.atomic")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("child.home.materialization.atomic")
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
        // And the instrument those two lean on, checked on its own terms.
        // Issue #131 found two ways sources/lib/extentprobe.py could report
        // sharing that is not there: an extent whose fe_physical is not a
        // device address, and an overlayfs whose synthetic st_dev does not
        // name one backing store. Neither hazard is produced by a healthy
        // host, so neither is exercised by any run of the two nodes above --
        // the gates could be deleted and nothing would go red. This node
        // drives them from tables, needs no uv and no network, and so keeps
        // its meaning in exactly the environments where the cost node skips.
        node("sources/home-clone/ExtentProbeIsSound.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("extent.probe.is.sound")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("extent.probe.is.sound")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("home.tripwire.checked")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("home.tripwire.checked")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("checkout.home.launch.isolated")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("checkout.home.launch.isolated")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("home.sync.authored.agent.tree")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("home.sync.authored.agent.tree")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("ticket.lifecycle.global.home.untouched")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("ticket.lifecycle.global.home.untouched")
    }

    testGraph("project-env") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectEnvMaterialized.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("project.env.materialized")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("project.env.materialized")
    }

    testGraph("project-libs") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectLibsResolved.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("project.libs.resolved")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("project.libs.resolved")
    }

    testGraph("project-profiles") {
        node("sources/common/EnvPrepared.java")
        node("sources/project/ProjectProfilesResolved.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("project.profiles.resolved")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("project.profiles.resolved")
    }

    /*
     * artifact-dag: the derived-artifact DAG (#100), end to end on real homes.
     *
     * One node per claim the epic makes, ARTI-03 through ARTI-08: a home can
     * NAME what it derived without deriving it; every installer records what it
     * built from; one moved input marks the two artifacts that share it and
     * nothing else; `build <one>` repairs that one and leaves its siblings
     * alone; a lazy clone declares what it did not copy; a cold artifact refuses
     * with the way out; and an uninstall takes its owner's subgraph with it.
     *
     * DOCKER-FREE, on purpose: no postgres, no compose, no registry, so it
     * cannot fail for reasons that are not about skill-manager, and two ticket
     * worktrees can run it at once (which #104 measured they cannot do for the
     * eight graphs that declare PostgresUp).
     *
     * It is NOT network-free end to end, and saying so would be the same
     * comfortable overstatement #124 had to walk back on its own graph:
     * `install` runs EnsureGateway, which builds the virtual-mcp-gateway venv,
     * so on a cold runner this reaches the network like every other
     * install-carrying graph. What is true is narrower — nothing this graph
     * DECLARES has to be fetched.
     *
     * Every node depends only on env.prepared and
     * builds its own home under env.prepared's temp root, so no node reads lock
     * state another wrote and HomeFixpointLaw can find every home they made.
     *
     * The fixture is a skill-script CLI dep whose installer writes
     * cache/skill-script-<unit>-<tool>/ and a bin/cli wrapper that execs into
     * it by absolute path. That is the only backend that installs INTO a home
     * from bytes the graph itself wrote, and it is the shim/tree pair the DAG's
     * interesting edge runs between. See ArtifactDagSupport's javadoc for what
     * that costs in coverage, stated rather than hidden.
     *
     * RUNNING IT WHILE IT IS RED. Three assertions describe ARTI-07 (#108) and
     * ARTI-08 (#109), which are not merged into this branch: a lazy clone still
     * refuses `home verify`, a cold shim still fails with a raw shell error
     * naming no build, and an uninstall still leaves the removed unit's cache
     * tree on disk. Each is measured, documented in its node's javadoc, and
     * left asserted — a graph that dropped them would report those tickets
     * done. A plain run therefore stops at the first one; for the whole sweep:
     *
     *   TESTGRAPH_CONTINUE_AFTER_FAILURE=1 \
     *     python3 skills/test_graph/scripts/run.py artifact-dag
     *
     * Every node then executes against its own home, each reports its own
     * status, and the run still fails.
     */
    testGraph("artifact-dag") {
        node("sources/common/EnvPrepared.java")
        node("sources/artifact-dag/ArtifactsEnumerated.java")
        node("sources/artifact-dag/EveryLockRowFingerprinted.java")
        node("sources/artifact-dag/EditedInputMarksDependentsStale.java")
        node("sources/artifact-dag/BuildRepairsOneArtifact.java")
        node("sources/artifact-dag/LazyCloneDeclaresWithoutBuilding.java")
        node("sources/artifact-dag/ColdArtifactRefusalNamesBuild.java")
        node("sources/artifact-dag/UninstallPrunesTheSubgraph.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("uninstall.prunes.the.subgraph")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("uninstall.prunes.the.subgraph")
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
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("postgres.down")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("postgres.down")
    }

    /*
     * onboarding: the walk a fresh repository takes from "no home" to "a
     * launchable agent with the skills it declared", transcribed from a
     * hand-run enumeration of that path.
     *
     * The walk was run by hand four times before this graph existed. Each pass
     * found the same class of defect and each pass cost a round trip, because
     * the walk crosses two repositories — skill-manager and
     * git-integration-repo — and every finding lived in the seam: a copy or a
     * check that enumerated the places it knew about and missed one. Agent
     * directories but not their sibling `.claude.json`; store symlinks but not
     * agent symlinks; the registration store but not the binding ledger; store
     * bins but not agent-home plugin bins.
     *
     * WHY THE COMPANIONS ARE THE POINT. This project has repeatedly shipped
     * instruments that reported clean while measuring nothing: a free-space
     * check the host decided, a zsh word-splitting bug that made every result
     * vacuous, a fixture that froze a path which was never a home, and four
     * exit codes read off the wrong process because `cmd | head; echo $?`
     * reports head's status. The walk then found three MORE inside the product
     * itself — `bootstrap-home.sh`'s `verified: N skill(s) servable` counts the
     * store rather than the agent-visible links, `install` exits 0 over printed
     * violations, and `ADDED claude (<path>)` names a file the agent does not
     * read. So every assertion here that could pass by not looking carries a
     * companion, in the same run, that proves it can fail. Each node's javadoc
     * names its own.
     *
     * ORDERING IS THE STEP LOG. Strictly ordered from the fixture onward: each
     * node builds on the disk state the previous one left, in the order the
     * hand walk took, because half these properties are only observable at one
     * point in that sequence. `.claude.json` appears at sync, not at bootstrap;
     * the close-out gate only blocks after a unit has been removed from the
     * project home; the foreign claims are only visible in a clone of a
     * polluted source.
     *
     * WHAT IS NOT HERE, deliberately. The gateway singleton's PROCESS half
     * (a home created under a redirected HOME still attaches to whatever owns
     * 127.0.0.1:51717) is not automatable: it needs a live gateway owned by a
     * different home on a fixed port, and two graphs on one machine would
     * contend for that port — which IS the defect, so a node asserting it would
     * be testing the CI scheduler. The FILE half is asserted in
     * onboarding.clone.is.honest; the process half is a documented manual
     * check, recorded in that node's javadoc. Likewise `--onboard`'s execution
     * needs the network; its offline REFUSAL and remedy text are asserted, its
     * execution is not.
     *
     * Exit codes are captured from Process.waitFor, never through a pipeline.
     * That is a harness rule rather than a product assertion, and it is written
     * down because the shell transcription this graph replaces produced five
     * false readings from `cmd | head; echo $?` — which under zsh reports
     * head's status, not the command's.
     *
     * RUNNING IT WHILE IT IS RED. Most of these assertions describe defects
     * that are still open, so a plain run stops at the first one and reveals a
     * single finding. For the whole sweep:
     *
     *   TESTGRAPH_CONTINUE_AFTER_FAILURE=1 \
     *     python3 skills/test_graph/scripts/run.py onboarding
     *
     * Every node then executes in order against the disk state its predecessor
     * left, each reports its own status, and the run still fails. That flag is
     * not a way to make a red graph green: a node whose upstream state was never
     * built fails on its own stated preconditions, which is exactly why those
     * preconditions are separate assertions rather than silent assumptions.
     */
    testGraph("onboarding") {
        node("sources/common/EnvPrepared.java")

        // Cheapest first, and it needs no home at all: does the skill ship the
        // files its own documentation tells you to copy, and does any script
        // resolve a CLI by a path relative to itself?
        node("sources/onboarding/OnboardingDocsAndScriptsStatic.java")

        node("sources/onboarding/OnboardingFixtureBuilt.java")

        // The two refusal paths, before anything has been built on top of them:
        // a source home that is absent (exit 1) and one that is empty (exit 5).
        node("sources/onboarding/OnboardingBootstrapRefusals.java")

        node("sources/onboarding/OnboardingBootstrapped.java")
        node("sources/onboarding/OnboardingCloneIsHonest.java")
        node("sources/onboarding/OnboardingCloneDropsForeignClaims.java")
        node("sources/onboarding/OnboardingProjectionsMaterialized.java")

        node("sources/onboarding/OnboardingSynced.java")
        node("sources/onboarding/OnboardingLeavesWorkTreeClean.java")
        node("sources/onboarding/OnboardingClaudeMcpConfigReadable.java")
        node("sources/onboarding/OnboardingLaunchEnv.java")
        node("sources/onboarding/OnboardingProjectMarkdownImportsChecked.java")
        node("sources/onboarding/OnboardingErrorRecordsCoherent.java")
        node("sources/onboarding/OnboardingImportViolationsAreFatal.java")
        node("sources/onboarding/OnboardingRefusalsAreMessages.java")
        node("sources/onboarding/OnboardingRemediesAreRunnable.java")
        node("sources/onboarding/OnboardingCliProjectionIdempotent.java")

        node("sources/onboarding/OnboardingWorktreeLifecycle.java")
        node("sources/onboarding/OnboardingWtContractLines.java")

        // Last, because it compares the operator's real homes against a
        // baseline taken before any of the walk ran. It carries its own
        // sensitivity proof, so it keeps its meaning even if a node above it
        // failed.
        node("sources/onboarding/OnboardingGlobalHomeUntouched.java")
        // THE FIXPOINT LAW. One shared post-condition, not a bespoke
        // check per graph: every home this graph produced must satisfy
        // `home verify`, and where it refuses, the remedy IT PRINTED must
        // clear it. Six defects of that shape were each found by hand on
        // one home; the graph that would have caught them was always the
        // one nobody had added a check to. Depends on this graph's last
        // node so it runs last, and FAILS if it finds no home — a law
        // that quietly checks nothing is the failure mode being closed.
        node("sources/common/HomeFixpointLaw.java").dependsOn("onboarding.global.home.untouched")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java").dependsOn("onboarding.global.home.untouched")
    }

    /**
     * What a healthy Skill Manager home looks like — one node per invariant,
     * each checked against a home this run provisioned AND against a
     * deliberately damaged copy of it.
     *
     * WHY THIS GRAPH EXISTS. The artifact-DAG epic surfaced ten defects in the
     * operator's own homes, every one found by accident while doing something
     * else. Nothing anywhere said what a healthy home looks like, so each was
     * noticed once and would have recurred silently. ARTI-22 (#124) is that
     * statement, written so it executes.
     *
     * THE DAMAGED FIXTURES ARE THE POINT. Where an invariant is checkable
     * against a home at rest, the node plants the defect it is drawn from,
     * confirms the check catches it, repairs it, and confirms the check passes
     * again. A detector that cannot fail and a detector that cannot pass are
     * both worthless, and this epic has already found one oracle that passed
     * vacuously and one metric that only ever moved in the flattering
     * direction.
     *
     * EIGHT OF THE TWELVE NODES DO THAT; the other four cannot and say so,
     * which an earlier version of this comment glossed over by claiming all of
     * them did. env.prepared and the fixture provision rather than assert;
     * home.fixpoint.law is the shared post-condition; and
     * bootstrap.projects.target plants nothing at all, because its subject is a
     * COMMAND's behaviour rather than a home's state — it pins the two halves of
     * defect 10's contract instead.
     *
     * SEVERAL NODES ALSO ASSERT A NON-DETECTION, and those are not padding.
     * Three of #124's nine candidate invariants were false as written — a
     * brew-backed shim legitimately points outside every home, a dependency the
     * machine already satisfies legitimately has no shim, and 51 of the
     * operator's 106 "foreign" projections are correctly-registered child-home
     * projections. Each correction is kept executable as an assertion that the
     * healthy case is NOT reported, so a later "strengthening" of a check fails
     * here instead of in somebody's home.
     *
     * ONE NODE CARRIES A PINNED DEFECT (#120) and says so in its name. It is
     * expected to go RED when #120 lands. That is deliberate; read the class
     * comment on DeclaredCliIsAttributed before changing it.
     *
     * DOCKER-FREE, and it adds no network dependency of its own: the fixture
     * installs two units from local git repositories with local bare remotes,
     * so nothing this graph declares has to be fetched. It is NOT network-free
     * end to end, and the distinction matters — `skill-manager install` runs
     * EnsureGateway, which builds the virtual-mcp-gateway venv, so on a cold
     * runner this graph reaches the network exactly as the other eight core
     * graphs do (#113's dependency wall was that same call). No postgres, no
     * docker compose, no registry.
     */
    testGraph("home-integrity") {
        node("sources/common/EnvPrepared.java")

        // One real install, with the real CLI, into a private home. Every
        // invariant below relates two things the PRODUCT wrote; a hand-built
        // home would only prove this graph is self-consistent.
        node("sources/home-integrity/HomeIntegrityFixture.java")

        // The record/store relation, and its error-state disjunct.
        node("sources/home-integrity/RecordAgreesWithStore.java")
        // The tracking ref a URL fetch leaves behind — defects 2 and 3, merged.
        node("sources/home-integrity/UpstreamTracksWhatSyncFetched.java")
        // The drift gate, driven through a real record/change/record/ack cycle.
        node("sources/home-integrity/AckIsStable.java")
        // A shim that does not run is broken, wherever it points.
        node("sources/home-integrity/EveryShimResolves.java")
        // Orphan lock rows — the fix is #109's, the assertion is this ticket's.
        node("sources/home-integrity/EveryLockRowHasAClaimant.java")
        // PINNED DEFECT #120. Satisfied holds; attributed does not.
        node("sources/home-integrity/DeclaredCliIsAttributed.java")
        // Harness instances whose template is gone.
        node("sources/home-integrity/InstanceTemplateInstalled.java")
        // The 51-of-106 correction, kept executable.
        node("sources/home-integrity/ProjectionSourceIsDecidable.java")
        // Defect 10: the shim/bootstrap contradiction, and its remedy.
        node("sources/home-integrity/BootstrapProjectsTheTargetHome.java")

        // HIS-7 (#223): a build inside a child home does not write into its
        // parent. Drives a real clone and a real producer, because the defect
        // was in what `cat >` does to an inherited symlink and no hand-built
        // fixture would have contained it.
        node("sources/home-integrity/ParentHomeSurvivesAChildBuild.java")

        // HIS-10 (#227): four readers, one answer, on ONE cloned home, with no
        // `--against`. Drives clone, verify, verify --against and a real sync
        // over the same three-tier topology, and carries its own control:
        // delete the descent record and the readers must split up again.
        node("sources/home-integrity/ReadersAgreeAboutOneClone.java")

        // HIS-9 (#226) / DEF-007: a sync in one home deletes nothing from
        // another. Runs a REAL sync over two cloned homes, one of whose bin/cli
        // IS a link at the other's, and reads the victim's directory before and
        // after -- the unit test drives the pruner, this one shows the exit-0
        // silent data loss an operator would have met. Carries its own control:
        // the same sync without the link must NOT refuse.
        node("sources/home-integrity/SyncStaysInsideItsHome.java")

        // HIS-16 (#237) / DEF-046+DEF-047: a `project` verb run from a working
        // directory inside ANOTHER repository does not touch that repository's
        // home. SKILL_MANAGER_HOME names the driver's home, the confinement
        // covers the driver's root, and cwd is the victim checkout -- the exact
        // arrangement that re-realized a worktree home, removing one unit and
        // installing two. Carries its own control: the byte-identical run with
        // the confinement REMOVED must reproduce the escape, so the claim
        // assertion is shown to be live rather than assumed to be.
        node("sources/home-integrity/ProjectVerbStaysInItsHome.java")

        // THE FIXPOINT LAW, as every home-producing graph ends. FAILS if it
        // finds no home.
        //
        // THE EDGE BELOW IS NOT WHAT DECIDES WHAT IT COVERS, and reading it as
        // though it were cost this graph six homes. The law does not take a
        // home list and does not scan the filesystem: HomeFixpointLaw.
        // candidateHomes walks every UPSTREAM CONTEXT VALUE and offers each
        // existing directory to `home verify`. So a node covers itself by
        // PUBLISHING its homes, not by being named here -- and the planner
        // already orders this node last regardless (plan 15/15).
        //
        // MEASURED, run 20260821-191337: seven store-shaped directories existed
        // under the sandbox when the law ran, production's `home verify` called
        // all seven homes, and the law reported homesChecked = 1. The six it
        // never saw belong to ParentHomeSurvivesAChildBuild,
        // ReadersAgreeAboutOneClone and SyncStaysInsideItsHome -- none of which
        // published a home path. The last of those now does; the other two are
        // DEF-020.
        node("sources/common/HomeFixpointLaw.java")
                .dependsOn("home.integrity.bootstrap.projects.target")
        // THE MEMBERSHIP LAW, the second post-condition and the one a
        // re-realized home does NOT satisfy: `home verify` passes on a
        // home that is internally consistent and wrong about what it
        // holds (DEF-047). Same structural discovery, same "zero homes
        // is a FAILURE" rule, and it carries its own self-test.
        node("sources/common/HomeMembershipLaw.java")
                .dependsOn("home.integrity.bootstrap.projects.target")
    }

    /**
     * sync-settles — the change-management drag, asserted end to end.
     *
     * Its own graph and NOT a node inside `home-integrity`, and the reason is
     * NOT that it is red. IT PASSES. It asserts the right property -- a sync
     * must not strand the installed baseline behind a merge it already
     * committed -- over a ROOT-TIER install/sync fixture, and the measured
     * failure was on a PROJECT-TIER materialized child copy, which it does not
     * reach. Two candidate reproductions were tried and ruled out by
     * measurement; see the node's class comment so nobody re-spends them.
     *
     * So it is UNPROVEN AGAINST THE DEFECT IT IS NAMED FOR, and that is worse
     * in core than a red node would be: a green `sync-settles` in a nightly
     * summary reads as "the drag is covered". It runs in `full`, where the
     * result is visible and nobody's push depends on it.
     *
     * (An earlier version of this comment said "RED until HIS-4 merges". That
     * was written before the node was run and was simply false. Corrected
     * rather than deleted, because a graph comment that has been wrong once
     * about its own colour is worth flagging to the next reader.)
     *
     * TO PROMOTE IT: HIS-4 (#216) owns making it red against the project-tier
     * case. Once it is red before the fix and green after, fold the node into
     * `home-integrity` and delete this graph -- it carries its own fixture and
     * shares no state, so the move is a file rename and a line in
     * `select-graph-set.py`.
     */
    testGraph("sync-settles") {
        node("sources/common/EnvPrepared.java")

        // One node, four assertions, because any single link left in place
        // reproduces the drag. See the class comment for the chain.
        node("sources/sync-settles/ScaffoldTreeDoesNotStrandTheBaseline.java")
    }
}
