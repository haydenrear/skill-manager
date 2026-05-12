package dev.skillmanager.effects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.InstallResult;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The single user-facing renderer for every program. Walks every
 * {@link ContextFact} in an exhaustive switch — handlers emit facts, this
 * class is the only place that prints. Sub-programs share one renderer
 * via {@link EffectContext} so accumulated state (refused-list,
 * agent-config rollup, MCP-register results, outstanding-error banner)
 * survives the boundary.
 *
 * <p>{@link #onReceipt} renders per-fact output as effects complete (so
 * the user sees progress for long operations); {@link #onComplete}
 * emits the summaries that depend on cross-receipt aggregation.
 */
public final class ConsoleProgramRenderer implements ProgramRenderer {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final SkillStore store;
    private GatewayConfig gateway;

    // ---- accumulators for onComplete summaries ----
    private final List<String> refusedSkills = new ArrayList<>();
    private final List<String> conflictedSkills = new ArrayList<>();
    private final Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
    private final List<String> orphans = new ArrayList<>();
    private final List<InstallResult> mcpResults = new ArrayList<>();
    private final Map<String, java.util.LinkedHashMap<InstalledUnit.ErrorKind, String>> outstandingErrors =
            new LinkedHashMap<>();

    public ConsoleProgramRenderer(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    @Override
    public void onReceipt(EffectReceipt receipt) {
        if (receipt.status() == EffectStatus.FAILED && receipt.errorMessage() != null) {
            Log.error("× %s: %s",
                    receipt.effect().getClass().getSimpleName(), receipt.errorMessage());
        }
        // Halt banner is rendered whenever the program is stopping
        // here — regardless of whether the status is OK (cooperative)
        // or FAILED (transient + halt). Status drives the failure
        // diagnostic; continuation drives the "we're stopping" line.
        if (receipt.continuation() == Continuation.HALT && receipt.errorMessage() != null
                && receipt.status() != EffectStatus.FAILED) {
            Log.error("✋ halted: %s", receipt.errorMessage());
        }
        for (ContextFact f : receipt.facts()) render(f);
    }

    private void render(ContextFact f) {
        switch (f) {
            // ---- pre-flight ----
            case ContextFact.DryRun ignored -> {
                // dry-run output is owned by DryRunInterpreter's describe() —
                // we just log nothing here.
            }
            case ContextFact.HaltWithExitCode ignored -> {
                // Halt message is rendered by the HALTED receipt itself
                // (Log.error "✋ halted: ..."). The exit code is for the
                // decoder; no extra user-visible line needed.
            }
            case ContextFact.RegistryConfigured x -> Log.ok("registry: %s", x.url());
            case ContextFact.GatewayAlreadyRunning ignored -> { /* silent: not noteworthy */ }
            case ContextFact.GatewayStarted x -> Log.ok("gateway up at %s:%d", x.host(), x.port());
            case ContextFact.GatewayUnreachable x -> Log.warn(
                    "gateway at %s is unreachable and not local — not attempting to start", x.host());

            // ---- commit / audit / provenance ----
            case ContextFact.SkillCommitted x -> Log.ok("installed %s", x.name());
            case ContextFact.CommitRolledBack x -> Log.warn("rollback: removed partially-committed %s", x.name());
            case ContextFact.AuditRecorded ignored -> { /* silent */ }
            case ContextFact.ProvenanceRecorded ignored -> { /* silent */ }

            // ---- onboard (reconciler) ----
            case ContextFact.SkillOnboarded x -> Log.info("onboarded %s (kind=%s)", x.skillName(), x.kind());

            // ---- transitives ----
            case ContextFact.TransitiveInstalled x -> Log.ok("transitive: installed %s", x.name());
            case ContextFact.TransitiveFailed x -> {
                String who = x.requestedBy() == null || x.requestedBy().isBlank()
                        ? "(top-level)"
                        : "needed by " + x.requestedBy();
                String why = x.reason() == null || x.reason().isBlank() ? "resolve failed" : x.reason();
                Log.warn("transitive: %s [%s] — %s", x.coord(), who, why);
            }
            case ContextFact.GraphResolved x -> {
                if (x.failures() == 0) Log.ok("resolve: %d unit(s)", x.resolved());
                else Log.warn("resolve: %d unit(s) resolved, %d failure(s)", x.resolved(), x.failures());
            }
            case ContextFact.BundledSkillFound x ->
                    Log.ok("bundled: %s — local source %s", x.publishedName(), x.sourcePath());
            case ContextFact.BundledSkillFromGithub x ->
                    Log.ok("bundled: %s — github %s", x.publishedName(), x.coord());
            case ContextFact.BundledSkillAlreadyInstalled x ->
                    Log.info("bundled: %s already installed at %s — skipping",
                            x.publishedName(), x.storePath());
            case ContextFact.BundledSkillMissing x ->
                    Log.error("bundled: %s not found (expected %s)",
                            x.publishedName(), x.expectedPath());

            // ---- bulk tool/CLI counters (legacy effects) ----
            case ContextFact.ToolsInstalledFor ignored -> { /* silent: per-tool facts cover it */ }
            case ContextFact.CliInstalledFor ignored -> { /* silent: per-dep facts cover it */ }

            // ---- MCP gateway (bulk legacy + per-server new) ----
            case ContextFact.McpRegistered x -> Log.ok("mcp: %s registered for %s", x.serverId(), x.skillName());
            case ContextFact.McpRegistrationFailed x ->
                    Log.error("mcp: %s register failed for %s — %s", x.serverId(), x.skillName(), x.message());
            case ContextFact.OrphanUnregistered x -> {
                Log.ok("gateway: unregistered orphan %s", x.serverId());
                orphans.add(x.serverId());
            }
            case ContextFact.McpServerRegistered x -> {
                Log.info("mcp: %s", x.result().message());
                mcpResults.add(x.result());
            }
            case ContextFact.McpServerRegistrationFailed x -> {
                Log.error("gateway: failed to register %s: %s",
                        x.result().serverId(), x.result().message());
                mcpResults.add(x.result());
            }

            // ---- agents (per-(agent, skill)) ----
            case ContextFact.AgentSkillSynced x -> Log.ok("%s: synced %s", x.agentId(), x.skillName());
            case ContextFact.AgentSkillSyncFailed x -> Log.warn(
                    "%s: skill sync failed for %s — %s", x.agentId(), x.skillName(), x.message());
            case ContextFact.AgentMcpConfigChanged x -> {
                agentChanges.computeIfAbsent(x.change(), k -> new ArrayList<>())
                        .add(x.agentId() + " (" + x.configPath() + ")");
            }
            case ContextFact.AgentMcpConfigFailed x ->
                    Log.warn("%s: mcp config update failed — %s", x.agentId(), x.message());

            // ---- sync git ----
            case ContextFact.SyncGitUpToDate x -> Log.ok("%s: already at %s", x.skillName(), x.label());
            case ContextFact.SyncGitMerged x -> Log.ok("%s: merged %s",
                    x.skillName(), shortHash(x.fetchedHash()));
            case ContextFact.SyncGitRefused x -> {
                printMergeInstructions(x.skillName(), x.upstream(), x.gitLatest());
                refusedSkills.add(x.skillName());
            }
            case ContextFact.SyncGitConflicted x -> {
                Path storeDir = store.skillDir(x.skillName());
                Log.error("%s: merge conflict in %d file(s):",
                        x.skillName(), x.conflictedFiles().size());
                for (String cf : x.conflictedFiles()) System.err.println("    " + cf);
                System.err.println();
                System.err.println("Resolve in " + storeDir + ", then `git add` + `git commit`.");
                System.err.println("To back out: `git merge --abort` (or `git reset --hard HEAD` after a stash-pop conflict).");
                System.err.println("If sync stashed local changes, they're preserved at `stash@{0}` — run `git stash pop`");
                System.err.println("once the working tree is clean. Only run `git stash drop` if you want to discard them.");
                conflictedSkills.add(x.skillName());
            }
            case ContextFact.SyncGitFailed x ->
                    Log.error("%s: git sync failed — %s", x.skillName(), x.reason());
            case ContextFact.SyncGitNotGitTracked x ->
                    Log.warn("%s: not git-tracked — sync/upgrade unavailable", x.skillName());
            case ContextFact.SyncGitNoOrigin x ->
                    Log.warn("%s: git-tracked but no origin remote configured", x.skillName());
            case ContextFact.SyncGitRegistryUnavailable x ->
                    Log.warn("%s: registry didn't return a git_sha — leaving sync state unchanged", x.skillName());
            case ContextFact.SyncGitAuthRequired x -> Log.warn(
                    "%s: registry refused cached credentials (%s) — run `skill-manager login` and re-sync",
                    x.skillName(), x.message());
            case ContextFact.SyncGitNoUpgradeNeeded x ->
                    Log.ok("%s: at %s (>= registry's latest) — no upgrade needed", x.skillName(), x.version());

            // ---- error management ----
            case ContextFact.ErrorAdded ignored -> { /* silent: tracked on source record */ }
            case ContextFact.ErrorCleared ignored -> { /* silent */ }
            case ContextFact.ErrorValidated x -> {
                if (x.cleared()) Log.ok("reconcile: %s cleared %s", x.skillName(), x.kind());
            }
            case ContextFact.OutstandingError x -> {
                // Bundled skills (skill-manager / skill-publisher) ship
                // in-tree with no .git/ — suppress NEEDS_GIT_MIGRATION
                // for them so the closing banner doesn't keep nagging.
                // Tracked in #44; once they move to their own repos this
                // suppression goes away.
                if (x.kind() == InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION
                        && dev.skillmanager.lifecycle.BundledSkills.isBundled(x.skillName())) {
                    break;
                }
                outstandingErrors
                        .computeIfAbsent(x.skillName(), k -> new java.util.LinkedHashMap<>())
                        .putIfAbsent(x.kind(), x.message());
            }

            // ---- skill-store mutations ----
            case ContextFact.SkillRemovedFromStore x -> Log.ok("removed %s from store", x.name());
            case ContextFact.AgentSkillUnlinked x -> Log.ok("%s: unlinked %s", x.agentId(), x.skillName());
            case ContextFact.AgentSkillUnlinkFailed x ->
                    Log.warn("%s: unlink %s failed — %s", x.agentId(), x.skillName(), x.message());
            case ContextFact.AgentMcpEntryRemoved x ->
                    Log.ok("%s: removed virtual-mcp-gateway entry", x.agentId());

            // ---- gateway lifecycle ----
            case ContextFact.GatewayStopped ignored -> Log.ok("gateway stopped");
            case ContextFact.GatewayConfigured x -> Log.ok("gateway URL persisted: %s", x.url());

            // ---- scaffolding / config bootstrap ----
            case ContextFact.SkillScaffolded x -> Log.ok("created skill: %s", x.path());
            case ContextFact.PolicyInitialized x -> Log.ok("policy file: %s", x.path());

            // ---- package-manager runtime ----
            case ContextFact.PackageManagerReady x -> {
                if (x.wasMissing()) Log.ok("pm: %s@%s installed", x.pmId(), x.version());
            }
            case ContextFact.PackageManagerUnavailable x ->
                    Log.warn("pm: %s unavailable — %s", x.pmId(), x.message());
            case ContextFact.PackageManagerInstalled x ->
                    Log.ok("pm: installed %s@%s → %s", x.pmId(), x.version(), x.installPath());

            // ---- decomposed plan-action effects ----
            case ContextFact.ToolEnsured x -> {
                String hint = x.bundled() ? "bundled" : (x.missingOnPath() ? "missing" : "on PATH");
                Log.ok("tool: %s ready (%s)", x.toolId(), hint);
            }
            case ContextFact.CliInstalled x -> Log.ok("cli: %s [%s] installed for %s",
                    x.depName(), x.backend(), x.skillName());
            case ContextFact.CliInstallFailed x -> Log.error(
                    "cli: %s install failed for %s — %s", x.depName(), x.skillName(), x.message());
            case ContextFact.UnitsLockUpdated x -> Log.ok(
                    "units.lock.toml: wrote %d unit(s) → %s", x.unitCount(), x.path());
            case ContextFact.UnitsLockRestored x -> Log.warn(
                    "units.lock.toml: restored prior content at %s (rollback)", x.path());

            // ---- harness plugin marketplace + CLI ----
            case ContextFact.PluginMarketplaceRegenerated x -> Log.ok(
                    "marketplace: wrote %d plugin(s) → %s", x.pluginCount(), x.path());
            case ContextFact.HarnessPluginCli x -> {
                if (x.ok()) {
                    if (x.pluginName() == null) Log.ok("%s: %s", x.agentId(), x.op());
                    else Log.ok("%s: %s %s", x.agentId(), x.op(), x.pluginName());
                } else {
                    if (x.pluginName() == null) Log.warn("%s: %s failed — %s",
                            x.agentId(), x.op(), x.message());
                    else Log.warn("%s: %s %s failed — %s",
                            x.agentId(), x.op(), x.pluginName(), x.message());
                }
            }
            case ContextFact.HarnessCliMissing x -> Log.warn(
                    "%s: CLI %s not on PATH — install with: %s",
                    x.agentId(), x.binary(), x.installHint());

            // ---- bindings (ticket 49) ----
            case ContextFact.BindingCreated x -> {
                String sub = x.subElement() == null ? "" : " (" + x.subElement() + ")";
                Log.ok("bound %s%s → %s [%s]", x.unitName(), sub, x.targetRoot(), x.bindingId());
            }
            case ContextFact.BindingRemoved x ->
                    Log.ok("unbound %s [%s]", x.unitName(), x.bindingId());
            case ContextFact.ProjectionMaterialized x ->
                    Log.info("projection: %s → %s", x.kind(), x.destPath());
            case ContextFact.ProjectionUnmaterialized x ->
                    Log.info("projection: removed %s at %s", x.kind(), x.destPath());
            case ContextFact.ProjectionSkippedConflict x ->
                    Log.warn("projection: %s already exists — skipped (policy=SKIP)", x.destPath());
            case ContextFact.DocBindingSynced x -> {
                String sub = x.subElement() == null ? "" : "/" + x.subElement();
                String prefix = x.unitName() + sub + " [" + shortBindingId(x.bindingId()) + "]";
                switch (x.severity()) {
                    case INFO -> Log.ok("doc-sync %s — %s", prefix, x.description());
                    case WARN -> Log.warn("doc-sync %s — %s", prefix, x.description());
                    case ERROR -> Log.error("doc-sync %s — %s", prefix, x.description());
                }
            }
            case ContextFact.HarnessBindingSynced x -> {
                String prefix = x.harnessName() + ":" + x.instanceId();
                switch (x.action()) {
                    case APPLIED -> Log.ok("harness-sync %s — applied %s", prefix, x.unitName());
                    case UPGRADED -> Log.info("harness-sync %s — upgraded %s", prefix, x.unitName());
                    case REMOVED -> Log.ok("harness-sync %s — removed %s (orphan)",
                            prefix, x.unitName());
                    case FAILED -> Log.error("harness-sync %s — %s failed: %s",
                            prefix, x.unitName(), x.description());
                }
            }
        }
    }

    private static String shortBindingId(String id) {
        if (id == null) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    @Override
    public void onComplete() {
        printMcpResultsBlock();
        printSyncSummary();
        printAgentConfigSummary();
        printOutstandingErrors();
    }

    // ----------------------------------------------- summaries

    private void printMcpResultsBlock() {
        if (mcpResults.isEmpty()) return;
        try {
            System.out.println(McpWriter.RESULTS_START);
            System.out.println(JSON.writeValueAsString(mcpResults));
            System.out.println(McpWriter.RESULTS_END);
        } catch (Exception e) {
            Log.warn("failed to emit install results JSON: %s", e.getMessage());
        }
    }

    private void printSyncSummary() {
        if (refusedSkills.isEmpty() && conflictedSkills.isEmpty()) return;
        System.err.println();
        System.err.println("sync summary: " + (refusedSkills.size() + conflictedSkills.size())
                + " skill(s) need attention");
        if (!refusedSkills.isEmpty()) {
            System.err.println();
            System.err.println("  Extra local changes — re-run with --merge to bring upstream in:");
            for (String n : refusedSkills) System.err.println("    skill-manager sync " + n + " --merge");
        }
        if (!conflictedSkills.isEmpty()) {
            System.err.println();
            System.err.println("  Conflicted — resolve in the store dir, then `git commit` or `git merge --abort`:");
            for (String n : conflictedSkills) System.err.println("    " + n);
        }
        System.err.println();
    }

    private void printAgentConfigSummary() {
        var added = agentChanges.getOrDefault(McpWriter.ConfigChange.ADDED, List.of());
        var updated = agentChanges.getOrDefault(McpWriter.ConfigChange.UPDATED, List.of());
        if (added.isEmpty() && updated.isEmpty()) return;
        String mcpUrl = gateway == null ? "<gateway>" : gateway.mcpEndpoint().toString();
        System.out.println();
        System.out.println("agent MCP configs:");
        for (String a : added) System.out.println("  ADDED    " + a + "  → " + mcpUrl);
        for (String a : updated) System.out.println("  UPDATED  " + a + "  → " + mcpUrl);
        System.out.println();
        System.out.println("ACTION_REQUIRED: Restart Claude / Codex for the virtual-mcp-gateway entry");
        System.out.println("to take effect — without a restart the agent will not see any MCP tools.");
        System.out.println();
    }

    private void printOutstandingErrors() {
        if (outstandingErrors.isEmpty()) return;
        System.err.println();
        System.err.println("⚠ skills with outstanding errors (" + outstandingErrors.size()
                + ") — re-run after fixing:");
        for (var entry : outstandingErrors.entrySet()) {
            String skillName = entry.getKey();
            Path dir = store.skillDir(skillName);
            System.err.println();
            System.err.println("  " + skillName + ":");
            LinkedHashSet<InstalledUnit.ErrorKind> seen = new LinkedHashSet<>();
            for (var err : entry.getValue().entrySet()) {
                if (!seen.add(err.getKey())) continue;
                System.err.println("    - " + err.getKey() + ": " + err.getValue());
                System.err.println("      → " + outstandingHint(err.getKey(), skillName, dir));
            }
        }
        System.err.println();
    }

    private static String outstandingHint(InstalledUnit.ErrorKind kind, String skillName, Path storeDir) {
        return switch (kind) {
            case GATEWAY_UNAVAILABLE -> "start the gateway: skill-manager gateway up";
            case MCP_REGISTRATION_FAILED -> "retry: skill-manager sync " + skillName;
            case MERGE_CONFLICT -> "resolve in " + storeDir + ", then `git add` + `git commit`";
            case NO_GIT_REMOTE -> "set origin: cd " + storeDir + " && git remote add origin <url>";
            case NEEDS_GIT_MIGRATION -> "reinstall from a git source: skill-manager uninstall "
                    + skillName + " && skill-manager install github:<owner>/<repo>";
            case REGISTRY_UNAVAILABLE -> "ensure the registry is reachable, then re-run sync/upgrade "
                    + "(or use --git-latest to bypass the registry for git-tracked skills)";
            case AGENT_SYNC_FAILED -> "retry: skill-manager sync " + skillName
                    + " (will re-attempt the agent symlink)";
            case HARNESS_CLI_UNAVAILABLE -> "install the missing harness CLI, then re-run "
                    + "skill-manager sync " + skillName;
            case AUTHENTICATION_NEEDED -> "run `skill-manager login`, then re-run "
                    + "`skill-manager sync " + skillName + "`";
            case TRANSITIVE_RESOLVE_FAILED -> "fix the failing transitive (see the listed reason) "
                    + "and re-run: skill-manager sync " + skillName;
        };
    }

    // ----------------------------------------------- helpers

    private void printMergeInstructions(String skillName, String upstream, boolean gitLatest) {
        Log.error("%s has extra local changes (working tree edits or commits ahead of installed baseline).",
                skillName);
        System.err.println();
        System.err.println("Sync would overwrite them. Re-run with --merge:");
        System.err.println();
        System.err.println("    skill-manager sync " + skillName
                + (gitLatest ? " --git-latest" : "") + " --merge");
        System.err.println();
        Path storeDir = store.skillDir(skillName);
        System.err.println("Or merge by hand:");
        System.err.println();
        System.err.println("    cd " + storeDir);
        System.err.println("    git fetch " + (upstream == null ? "<origin>" : upstream) + " HEAD");
        System.err.println("    git merge FETCH_HEAD");
        System.err.println();
    }

    private static String shortHash(String hash) {
        if (hash == null) return "?";
        return hash.substring(0, Math.min(7, hash.length()));
    }
}
