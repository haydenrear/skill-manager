package dev.skillmanager.effects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.InstallResult;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
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
 *
 * <h2>What reaches the console, and what only reaches the log</h2>
 *
 * <p>The facts here divide cleanly into two kinds, and until this split they
 * were printed identically:
 *
 * <ul>
 *   <li><b>Per-item successes</b> — "claude: synced acme-widgets", "cli: uv
 *       installed for foo", "marketplace: wrote 4 plugin(s)". There is one per
 *       (unit × agent), so a twenty-unit home across three agents produced
 *       sixty lines carrying one fact: it worked. These go to {@link
 *       Log#detail}: the run log always, the console only under
 *       {@code --verbose}, and a ROLLUP with the counts takes their place.
 *       The counts are the point — "20 unit(s) into claude, codex, gemini" is
 *       checkable in a way that twenty sentences are not.</li>
 *   <li><b>Verdicts, and anything a caller must act on</b> — the install
 *       verdict, every warning, every error, the restart banner, the
 *       violations block. These are unchanged, in every mode.</li>
 * </ul>
 *
 * <p>{@code --json} is untouched: the JSON path returns before any of this and
 * still carries every receipt and every fact.
 */
public final class ConsoleProgramRenderer implements ProgramRenderer {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final SkillStore store;
    private GatewayConfig gateway;
    private final boolean json;
    private final List<Map<String, Object>> jsonReceipts = new ArrayList<>();

    // ---- accumulators for onComplete summaries ----
    private final List<String> refusedSkills = new ArrayList<>();
    private final List<String> conflictedSkills = new ArrayList<>();
    private final Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
    private final List<String> orphans = new ArrayList<>();
    private final List<InstallResult> mcpResults = new ArrayList<>();
    private final List<ContextFact.MarkdownImportViolation> markdownImportViolations = new ArrayList<>();
    private final Map<String, java.util.LinkedHashMap<InstalledUnit.ErrorKind, String>> outstandingErrors =
            new LinkedHashMap<>();

    // ---- rollup counters: what replaces the demoted per-item lines ----
    private final LinkedHashSet<String> syncedAgents = new LinkedHashSet<>();
    private final LinkedHashSet<String> projectedUnits = new LinkedHashSet<>();
    private final LinkedHashSet<String> unlinkedUnits = new LinkedHashSet<>();
    // Name SETS, not counters: one unit can carry two sync facts (it merged
    // AND its origin is local), and a sum over facts would report seven units
    // synced in a six-unit home. The union is the number of units touched.
    private final LinkedHashSet<String> gitMerged = new LinkedHashSet<>();
    private final LinkedHashSet<String> gitCurrent = new LinkedHashSet<>();
    private final LinkedHashSet<String> gitLocalOnly = new LinkedHashSet<>();
    // Provisioning, tallied by outcome rather than counted flat. Fed from BOTH
    // paths that provision a home — `install`'s decomposed EnsureTool /
    // RunCliInstall effects and `sync`'s bulk ToolsInstalledFor /
    // CliInstalledFor facts — so the two report identically without sharing an
    // implementation. `toolsReady`/`cliInstalled` as bare counters could not
    // say whether a run installed anything, which is the one question the
    // rollup exists to answer.
    private dev.skillmanager.cli.installer.ProvisionTally tools =
            dev.skillmanager.cli.installer.ProvisionTally.EMPTY;
    private dev.skillmanager.cli.installer.ProvisionTally clis =
            dev.skillmanager.cli.installer.ProvisionTally.EMPTY;
    private int mcpRegistered;
    private int bindingsCreated;
    private int bindingsRemoved;
    private int projectionsMaterialized;

    public ConsoleProgramRenderer(SkillStore store, GatewayConfig gateway) {
        this(store, gateway, false);
    }

    public ConsoleProgramRenderer(SkillStore store, GatewayConfig gateway, boolean json) {
        this.store = store;
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public void onReceipt(EffectReceipt receipt) {
        if (json) {
            jsonReceipts.add(receiptJson(receipt));
            for (ContextFact f : receipt.facts()) accumulate(f);
            return;
        }
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
            case ContextFact.RegistryConfigured x -> Log.detail("✓ registry: %s", x.url());
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
            case ContextFact.SkillOnboarded x -> Log.detail("onboarded %s (kind=%s)", x.skillName(), x.kind());

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
                    Log.detail("✓ bundled: %s — local source %s", x.publishedName(), x.sourcePath());
            case ContextFact.BundledSkillFromGithub x ->
                    Log.detail("✓ bundled: %s — github %s", x.publishedName(), x.coord());
            case ContextFact.BundledSkillAlreadyInstalled x ->
                    Log.detail("bundled: %s already installed at %s — skipping",
                            x.publishedName(), x.storePath());
            case ContextFact.BundledSkillMissing x ->
                    Log.error("bundled: %s not found (expected %s)",
                            x.publishedName(), x.expectedPath());

            // ---- bulk tool/CLI provisioning (sync / upgrade) ----
            //
            // These used to be silent on the grounds that "per-tool facts cover
            // it". They do not: this path does not emit per-tool facts at all —
            // ToolInstallRecorder and CliInstallRecorder print directly — so
            // the renderer said nothing about provisioning while the backends
            // said 22 lines of it. The tally is now the renderer's, and the
            // backends' per-item lines are Log.detail.
            case ContextFact.ToolsInstalledFor x -> tools = merge(tools, x.tally());
            case ContextFact.CliInstalledFor x -> clis = merge(clis, x.tally());

            // ---- MCP gateway (bulk legacy + per-server new) ----
            case ContextFact.McpRegistered x -> {
                Log.detail("✓ mcp: %s registered for %s", x.serverId(), x.skillName());
                mcpRegistered++;
            }
            case ContextFact.McpRegistrationFailed x ->
                    Log.error("mcp: %s register failed for %s — %s", x.serverId(), x.skillName(), x.message());
            case ContextFact.OrphanUnregistered x -> {
                Log.detail("✓ gateway: unregistered orphan %s", x.serverId());
                orphans.add(x.serverId());
            }
            case ContextFact.McpServerRegistered x -> {
                Log.detail("mcp: %s", x.result().message());
                mcpResults.add(x.result());
            }
            case ContextFact.McpServerRegistrationFailed x -> {
                Log.error("gateway: failed to register %s: %s",
                        x.result().serverId(), x.result().message());
                mcpResults.add(x.result());
            }

            // ---- agents (per-(agent, skill)) ----
            case ContextFact.AgentSkillSynced x -> {
                Log.detail("✓ %s: synced %s", x.agentId(), x.skillName());
                syncedAgents.add(x.agentId());
                projectedUnits.add(x.skillName());
            }
            case ContextFact.AgentSkillSyncFailed x -> Log.warn(
                    "%s: skill sync failed for %s — %s", x.agentId(), x.skillName(), x.message());
            case ContextFact.AgentMcpConfigChanged x -> {
                agentChanges.computeIfAbsent(x.change(), k -> new ArrayList<>())
                        .add(x.agentId() + " (" + x.configPath() + ")");
            }
            case ContextFact.AgentMcpConfigFailed x ->
                    Log.warn("%s: mcp config update failed — %s", x.agentId(), x.message());
            case ContextFact.ProjectSynced x -> {
                String profile = x.profile() == null || x.profile().isBlank()
                        ? ""
                        : " (profile=" + x.profile() + ")";
                Log.ok("project-sync %s%s — %d resolved unit(s), %d binding(s) refreshed",
                        x.projectName(), profile, x.resolvedUnits(), x.bindingsRemoved());
            }
            case ContextFact.ProjectSyncFailed x ->
                    Log.warn("project-sync %s failed — %s", x.projectName(), x.message());
            // DEF-103: a project skipped for its OWN non-durable vendored
            // declarations. Warned, never counted as a sync failure, and the
            // wording says which of the two happened so a reader cannot mistake
            // "your project needs repairing" for "the unit sync broke".
            case ContextFact.ProjectSyncSkippedNonDurable x ->
                    Log.warn("project-sync %s skipped (not refreshed) — %s",
                            x.projectName(), x.message());

            // ---- sync git ----
            case ContextFact.SyncGitUpToDate x -> {
                Log.detail("✓ %s: already at %s", x.skillName(), x.label());
                gitCurrent.add(x.skillName());
            }
            case ContextFact.SyncGitMerged x -> {
                Log.detail("✓ %s: merged %s", x.skillName(), shortHash(x.fetchedHash()));
                gitMerged.add(x.skillName());
            }
            case ContextFact.SyncGitRefused x -> {
                printMergeInstructions(x.skillName(), x.upstream(), x.gitLatest(), x.fromDir(),
                        x.worktreeEdits());
                refusedSkills.add(x.skillName());
            }
            case ContextFact.SyncGitConflicted x -> {
                Path storeDir = unitDirFor(x.skillName());
                Log.error("%s: merge conflict in %d file(s):",
                        x.skillName(), x.conflictedFiles().size());
                Log.errorList("    ", x.conflictedFiles());
                // ONE definition of this remedy, shared with the outstanding-
                // errors banner: dev.skillmanager.app.ReportUseCase.
                //
                // It used to be spelled here as well, and the two spellings had
                // already drifted -- this one claimed "the stash clause is only
                // printed when there IS a stash" in a comment while printing it
                // unconditionally, and both of them printed `git add` + `git
                // commit` for a stash-pop residue with no MERGE_HEAD, which
                // cannot clear it. The remedy now asks the store which state it
                // is in. See ReportUseCase.mergeConflictRemedy.
                Log.error("  %s", dev.skillmanager.app.ReportUseCase
                        .mergeConflictRemedy(storeDir, x.skillName(), store.root(),
                                x.conflictedFiles().size()));
                conflictedSkills.add(x.skillName());
            }
            case ContextFact.SyncGitFailed x ->
                    Log.error("%s: git sync failed — %s", x.skillName(), x.reason());
            // A state, not a fault (D14): the operator asked for a local
            // install and has one. Said once per sync as a COUNT in the
            // rollup rather than once per unit — twenty copies of "this will
            // not update" is how the sentence became invisible, not how it
            // stayed visible.
            case ContextFact.SyncGitNotGitTracked x -> {
                Log.detail("! %s: not git-tracked — file/local installs do not sync; "
                        + "use github: or git+", x.skillName());
                gitLocalOnly.add(x.skillName());
            }
            case ContextFact.SyncGitLocalInstall x -> {
                Log.detail("%s: installed from a local path%s — nothing upstream to sync "
                                + "(reinstall from github: or git+ to track a remote)",
                        x.skillName(),
                        x.origin() == null || x.origin().isBlank() ? "" : " (" + x.origin() + ")");
                gitLocalOnly.add(x.skillName());
            }
            case ContextFact.SyncGitNoOrigin x ->
                    Log.warn("%s: git-tracked but no origin remote configured", x.skillName());
            case ContextFact.SyncGitRegistryUnavailable x ->
                    Log.warn("%s: registry didn't return a git_sha — leaving sync state unchanged", x.skillName());
            case ContextFact.SyncGitAuthRequired x -> Log.warn(
                    "%s: registry refused cached credentials (%s) — run `skill-manager login` and re-sync",
                    x.skillName(), x.message());
            case ContextFact.SyncGitNoUpgradeNeeded x -> {
                Log.detail("✓ %s: at %s (>= registry's latest) — no upgrade needed",
                        x.skillName(), x.version());
                gitCurrent.add(x.skillName());
            }

            // ---- error management ----
            case ContextFact.ErrorAdded ignored -> { /* silent: tracked on source record */ }
            case ContextFact.ErrorCleared ignored -> { /* silent */ }
            case ContextFact.ErrorValidated x -> {
                if (x.cleared()) Log.detail("✓ reconcile: %s cleared %s", x.skillName(), x.kind());
            }
            case ContextFact.OutstandingError x -> {
                outstandingErrors
                        .computeIfAbsent(x.skillName(), k -> new java.util.LinkedHashMap<>())
                        .putIfAbsent(x.kind(), x.message());
            }
            case ContextFact.MarkdownImportViolation x -> markdownImportViolations.add(x);
            case ContextFact.CantReadUnit x ->
                    Log.warn("could not read installed %s %s at %s — %s",
                            x.unitKind(), x.unitName(), x.path(), x.message());

            // ---- skill-store mutations ----
            case ContextFact.SkillRemovedFromStore x -> Log.ok("removed %s from store", x.name());
            case ContextFact.AgentSkillUnlinked x -> {
                Log.detail("✓ %s: unlinked %s", x.agentId(), x.skillName());
                syncedAgents.add(x.agentId());
                unlinkedUnits.add(x.skillName());
            }
            case ContextFact.AgentSkillUnlinkFailed x ->
                    Log.warn("%s: unlink %s failed — %s", x.agentId(), x.skillName(), x.message());
            case ContextFact.AgentMcpEntryRemoved x ->
                    Log.detail("✓ %s: removed virtual-mcp-gateway entry", x.agentId());

            // ---- gateway lifecycle ----
            case ContextFact.GatewayStopped ignored -> Log.ok("gateway stopped");
            case ContextFact.GatewayConfigured x -> Log.ok("gateway URL persisted: %s", x.url());

            // ---- scaffolding / config bootstrap ----
            case ContextFact.SkillScaffolded x -> Log.ok("created skill: %s", x.path());
            case ContextFact.PolicyInitialized x -> Log.ok("policy file: %s", x.path());

            // ---- package-manager runtime ----
            case ContextFact.PackageManagerReady x -> {
                if (x.wasMissing()) Log.detail("✓ pm: %s@%s installed", x.pmId(), x.version());
            }
            case ContextFact.PackageManagerUnavailable x ->
                    Log.warn("pm: %s unavailable — %s", x.pmId(), x.message());
            case ContextFact.PackageManagerInstalled x ->
                    Log.detail("✓ pm: installed %s@%s → %s", x.pmId(), x.version(), x.installPath());

            // ---- decomposed plan-action effects ----
            // The decomposed path's own per-item facts. The backends below them
            // classify the outcome and print it; these two only need to be
            // counted into the same tally so `install` and `sync` report the
            // same shape. `missingOnPath` is the plan's pre-flight reading, so
            // it is the one thing here that distinguishes an event from a state.
            case ContextFact.ToolEnsured x -> {
                String hint = x.bundled() ? "bundled" : (x.missingOnPath() ? "missing" : "on PATH");
                // An EVENT when the plan found it missing (this run provisioned
                // it), a STATE otherwise. The state case is one line per
                // declared tool on every run forever; the event case is work
                // that was done and may need to be known about.
                if (x.missingOnPath()) Log.ok("tool: %s ready (%s)", x.toolId(), hint);
                else Log.detail("✓ tool: %s ready (%s)", x.toolId(), hint);
                tools = tools.plus(x.missingOnPath()
                        ? dev.skillmanager.cli.installer.InstallOutcome.INSTALLED
                        : dev.skillmanager.cli.installer.InstallOutcome.ALREADY_PRESENT);
            }
            // Always an event: this fact is only emitted when an install ran.
            // It was demoted in the first pass, which was wrong — it is the
            // decomposed path's only report that work happened, and a run that
            // fetched and linked a binary should say so per item.
            case ContextFact.CliInstalled x -> {
                Log.ok("cli: %s [%s] installed for %s",
                        x.depName(), x.backend(), x.skillName());
                clis = clis.plus(dev.skillmanager.cli.installer.InstallOutcome.INSTALLED);
            }
            case ContextFact.CliInstallFailed x -> {
                Log.error("cli: %s install failed for %s — %s",
                        x.depName(), x.skillName(), x.message());
                clis = clis.withFailure();
            }
            // Kept on the console: it is the evidence that the run reached its
            // lock write, which is what "the store and the lock agree" rests
            // on, and it is one line however many units there are.
            case ContextFact.UnitsLockUpdated x -> Log.ok(
                    "units.lock.toml: wrote %d unit(s) → %s", x.unitCount(), x.path());
            case ContextFact.UnitsLockRestored x -> Log.warn(
                    "units.lock.toml: restored prior content at %s (rollback)", x.path());

            // ---- harness plugin marketplace + CLI ----
            case ContextFact.PluginMarketplaceRegenerated x -> Log.detail(
                    "✓ marketplace: wrote %d plugin(s) → %s", x.pluginCount(), x.path());
            case ContextFact.HarnessPluginCli x -> {
                if (x.ok()) {
                    if (x.pluginName() == null) Log.detail("✓ %s: %s", x.agentId(), x.op());
                    else Log.detail("✓ %s: %s %s", x.agentId(), x.op(), x.pluginName());
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
                Log.detail("✓ bound %s%s → %s [%s]",
                        x.unitName(), sub, x.targetRoot(), x.bindingId());
                bindingsCreated++;
            }
            case ContextFact.BindingRemoved x -> {
                Log.detail("✓ unbound %s [%s]", x.unitName(), x.bindingId());
                bindingsRemoved++;
            }
            case ContextFact.ProjectionMaterialized x -> {
                Log.detail("projection: %s → %s", x.kind(), x.destPath());
                projectionsMaterialized++;
            }
            case ContextFact.ProjectionUnmaterialized x ->
                    Log.detail("projection: removed %s at %s", x.kind(), x.destPath());
            case ContextFact.ProjectionSkippedConflict x ->
                    Log.warn("projection: %s already exists — skipped (policy=SKIP)", x.destPath());
            case ContextFact.DocBindingSynced x -> {
                String sub = x.subElement() == null ? "" : "/" + x.subElement();
                String prefix = x.unitName() + sub + " [" + shortBindingId(x.bindingId()) + "]";
                switch (x.severity()) {
                    case INFO -> Log.detail("✓ doc-sync %s — %s", prefix, x.description());
                    case WARN -> Log.warn("doc-sync %s — %s", prefix, x.description());
                    case ERROR -> Log.error("doc-sync %s — %s", prefix, x.description());
                }
            }
            case ContextFact.HarnessBindingSynced x -> {
                String prefix = x.harnessName() + ":" + x.instanceId();
                switch (x.action()) {
                    case APPLIED -> Log.detail("✓ harness-sync %s — applied %s", prefix, x.unitName());
                    case UPGRADED -> Log.detail("harness-sync %s — upgraded %s", prefix, x.unitName());
                    case REMOVED -> Log.detail("✓ harness-sync %s — removed %s (orphan)",
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
        if (json) {
            printJsonDocument();
            return;
        }
        printMcpResultsBlock();
        printRollups();
        printSyncSummary();
        // Violations before the agent-config summary, because that summary
        // ends in `ACTION_REQUIRED: Restart Claude / Codex …` — a line that
        // reads as the last word on the run. Printing the violations after it
        // put them past the point a reader stops, at the bottom of a long
        // install, under a success banner. Order is not a substitute for the
        // exit code (MarkdownImportValidator.EXIT_CODE is), but a report whose
        // last line contradicts its own findings costs a round trip anyway.
        printMarkdownImportViolations();
        printAgentConfigSummary();
        printOutstandingErrors();
    }

    private void accumulate(ContextFact f) {
        switch (f) {
            case ContextFact.OrphanUnregistered x -> orphans.add(x.serverId());
            case ContextFact.McpServerRegistered x -> mcpResults.add(x.result());
            case ContextFact.McpServerRegistrationFailed x -> mcpResults.add(x.result());
            case ContextFact.AgentMcpConfigChanged x -> agentChanges
                    .computeIfAbsent(x.change(), k -> new ArrayList<>())
                    .add(x.agentId() + " (" + x.configPath() + ")");
            case ContextFact.SyncGitRefused x -> refusedSkills.add(x.skillName());
            case ContextFact.SyncGitConflicted x -> conflictedSkills.add(x.skillName());
            case ContextFact.OutstandingError x -> {
                outstandingErrors
                        .computeIfAbsent(x.skillName(), k -> new java.util.LinkedHashMap<>())
                        .putIfAbsent(x.kind(), x.message());
            }
            case ContextFact.MarkdownImportViolation x -> markdownImportViolations.add(x);
            case ContextFact.CantReadUnit ignored -> {}
            default -> {}
        }
    }

    private Map<String, Object> receiptJson(EffectReceipt receipt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("effect", receipt.effect().getClass().getSimpleName());
        out.put("status", receipt.status().name());
        out.put("continuation", receipt.continuation().name());
        out.put("errorMessage", receipt.errorMessage());
        List<Map<String, Object>> facts = new ArrayList<>();
        for (ContextFact f : receipt.facts()) facts.add(factJson(f));
        out.put("facts", facts);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> factJson(ContextFact fact) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", fact.getClass().getSimpleName());
        try {
            Map<String, Object> fields = JSON.convertValue(fact, Map.class);
            out.putAll(fields);
        } catch (IllegalArgumentException ex) {
            out.put("serializationError", ex.getMessage());
        }
        return out;
    }

    private void printJsonDocument() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("receipts", jsonReceipts);
        root.put("summary", jsonSummary());
        try {
            System.out.println(JSON.writeValueAsString(root));
        } catch (IOException io) {
            Log.warn("failed to emit program JSON: %s", io.getMessage());
        }
    }

    private Map<String, Object> jsonSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mcpResults", mcpResults);
        out.put("orphansUnregistered", orphans);
        out.put("sync", Map.of(
                "refused", refusedSkills,
                "conflicted", conflictedSkills));
        out.put("agentConfigChanges", agentChanges);
        out.put("markdownImportViolations", markdownImportViolations);
        out.put("outstandingErrors", outstandingErrors);
        return out;
    }

    // ----------------------------------------------- summaries

    /**
     * The counted replacements for the per-item lines that moved to the log.
     *
     * <p>Each line is emitted only when its count is non-zero, and each states
     * a number a caller can check against the disk — "N unit(s) into claude,
     * codex, gemini" is falsifiable by counting links; "claude: synced foo"
     * twenty times is not more evidence than once.
     *
     * <p>These are the ONLY lines added by the quieting. They are not printed
     * under {@code --verbose}: there the per-item lines are already on the
     * console, and a rollup over output the reader can see is noise.
     */
    private void printRollups() {
        if (Log.isVerbose()) return;
        LinkedHashSet<String> touched = new LinkedHashSet<>(gitMerged);
        touched.addAll(gitCurrent);
        touched.addAll(gitLocalOnly);
        if (!touched.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!gitMerged.isEmpty()) parts.add(gitMerged.size() + " merged");
            if (!gitCurrent.isEmpty()) parts.add(gitCurrent.size() + " already current");
            // Kept on the console rather than in the log: a local-only install
            // is the state that silently never updates, and its count is the
            // whole reason the category exists.
            if (!gitLocalOnly.isEmpty()) {
                parts.add(gitLocalOnly.size() + " local-only (no shared upstream)");
            }
            Log.ok("synced %d unit(s) — %s", touched.size(), String.join(", ", parts));
        }
        if (!projectedUnits.isEmpty()) {
            Log.ok("agents: %d unit(s) linked into %s",
                    projectedUnits.size(), String.join(", ", syncedAgents));
        }
        if (!unlinkedUnits.isEmpty()) {
            Log.ok("agents: %d unit(s) unlinked from %s",
                    unlinkedUnits.size(), String.join(", ", syncedAgents));
        }
        // Provisioning gets its own lines rather than a slot in "side effects",
        // because the categories are the point: "cli: 18 already present,
        // 2 installed" says something a bare "20 cli dep(s)" cannot, namely
        // whether this run did any work. Both are emitted only when the surface
        // was touched at all.
        String toolLine = tools.render("tools");
        if (toolLine != null) Log.ok("%s", toolLine);
        String cliLine = clis.render("cli");
        if (cliLine != null) Log.ok("%s", cliLine);

        List<String> side = new ArrayList<>();
        if (mcpRegistered > 0) side.add(mcpRegistered + " mcp server(s)");
        if (bindingsCreated > 0) side.add(bindingsCreated + " binding(s) bound");
        if (bindingsRemoved > 0) side.add(bindingsRemoved + " binding(s) unbound");
        if (projectionsMaterialized > 0) {
            side.add(projectionsMaterialized + " projection(s) materialized");
        }
        if (!side.isEmpty()) Log.ok("side effects: %s", String.join(", ", side));
    }

    /**
     * Sum two tallies. A program can run the bulk provisioning effects more
     * than once (sync's post-update tail after a sub-program), and a sub-program
     * shares this renderer.
     */
    private static dev.skillmanager.cli.installer.ProvisionTally merge(
            dev.skillmanager.cli.installer.ProvisionTally a,
            dev.skillmanager.cli.installer.ProvisionTally b) {
        if (b == null) return a;
        return new dev.skillmanager.cli.installer.ProvisionTally(
                a.alreadyPresent() + b.alreadyPresent(),
                a.installed() + b.installed(),
                a.missing() + b.missing(),
                a.failed() + b.failed());
    }

    private void printMcpResultsBlock() {
        if (mcpResults.isEmpty()) return;
        try {
            System.out.println(McpWriter.RESULTS_START);
            System.out.println(JSON.writeValueAsString(mcpResults));
            System.out.println(McpWriter.RESULTS_END);
        } catch (IOException io) {
            Log.warn("failed to emit install results JSON: %s", io.getMessage());
        }
    }

    private void printSyncSummary() {
        if (refusedSkills.isEmpty() && conflictedSkills.isEmpty()) return;
        Log.error("sync: %d unit(s) need attention",
                refusedSkills.size() + conflictedSkills.size());
        if (!refusedSkills.isEmpty()) {
            Log.error("  extra local changes — `skill-manager sync <name> --merge`: %s",
                    joinBounded(refusedSkills));
        }
        if (!conflictedSkills.isEmpty()) {
            // The ROLLUP for several units, routed through the same definition
            // as the per-unit line above via its store-less branch. It said
            // `git commit` or `git merge --abort` unconditionally, which is the
            // mid-merge remedy and cannot clear a stash-pop residue -- the very
            // wrongness this ticket fixed one line up. Review finding MED-7:
            // the claim that there was ONE definition of this remedy was false
            // while these two rollups still carried their own.
            Log.error("  conflicted — %s: %s",
                    dev.skillmanager.app.ReportUseCase.mergeConflictRemedy(null, null, null),
                    joinBounded(conflictedSkills));
        }
    }

    /**
     * Names on one line, bounded. A refusal list is a set of unit names; the
     * command that clears each one is stated once above rather than repeated
     * per name, which is what turned a five-unit refusal into seventy lines.
     */
    private static String joinBounded(List<String> names) {
        if (names.size() <= Log.ERROR_SAMPLE) return String.join(", ", names);
        return String.join(", ", names.subList(0, Log.ERROR_SAMPLE))
                + ", … " + (names.size() - Log.ERROR_SAMPLE) + " more";
    }

    /**
     * The gateway-entry block: eight lines with three blank ones, compressed to
     * two.
     *
     * <p>{@code ACTION_REQUIRED} stays, on the console, in every mode, and
     * still last — a restart the agent has to perform is the definition of
     * "something the caller must act on", and its position after the
     * markdown-import violations is asserted (both by
     * {@code MarkdownImportValidatorTest} and by the onboarding graph's
     * {@code import.violations.are.fatal} node) because a block printed under
     * a terminal banner is a block nobody reads.
     *
     * <p>The enumeration was demoted to the log once, on the reasoning that
     * "which config file each agent writes is a fact about this machine's
     * layout, identical on every run, and the counts plus the URL carry the
     * claim". <b>The counts do not carry the claim.</b> {@code ADDED 1} was
     * printed by a run that had just written {@code <repo>/.claude.json}, a
     * file no agent reads, because {@code CLAUDE_CONFIG_DIR} was unset and the
     * resolver derived the root from {@code SKILL_MANAGER_HOME} — see
     * {@code AgentHomes#claudeConfigFileFor}. Every word of that line was true
     * and the write had gone nowhere, and there was nothing on the console a
     * reader could have checked it against.
     *
     * <p>So the file names are back on the console, and the demotion's own
     * argument is why that is affordable: this list is not the layout, it is
     * the CHANGES. {@code UNCHANGED} is not in it, so the steady state prints
     * nothing at all, and the ceiling is three lines — one per agent — on the
     * runs where something was actually written. That is the opposite of the
     * per-unit enumerations this renderer demotes, which scale with the home.
     */
    private void printAgentConfigSummary() {
        var added = agentChanges.getOrDefault(McpWriter.ConfigChange.ADDED, List.of());
        var updated = agentChanges.getOrDefault(McpWriter.ConfigChange.UPDATED, List.of());
        if (added.isEmpty() && updated.isEmpty()) return;
        String mcpUrl = gateway == null ? "<gateway>" : gateway.mcpEndpoint().toString();
        // The fact already carried the path (ContextFact.AgentMcpConfigChanged);
        // it simply was not being said out loud. `a` is "<agentId> (<file>)".
        for (String a : added) Log.info("  ADDED    " + a + "  → " + mcpUrl);
        for (String a : updated) Log.info("  UPDATED  " + a + "  → " + mcpUrl);
        Log.info("agent MCP configs: ADDED %d, UPDATED %d → %s",
                added.size(), updated.size(), mcpUrl);
        String actionRequired = "ACTION_REQUIRED: restart Claude / Codex for the "
                + "virtual-mcp-gateway entry to take effect — without a restart the agent "
                + "will not see any MCP tools.";
        System.out.println(actionRequired);
        Log.record(actionRequired);
    }

    private void printMarkdownImportViolations() {
        if (markdownImportViolations.isEmpty()) return;
        String header = "markdown skill-import violations (" + markdownImportViolations.size()
                + ") — fix these references:";
        System.err.println(header);
        Log.record(header);
        List<String> rows = new ArrayList<>();
        for (ContextFact.MarkdownImportViolation v : markdownImportViolations) {
            String kind = v.unitKind() == null || v.unitKind().isBlank()
                    ? ""
                    : " (" + v.unitKind() + ")";
            rows.add("- " + v.unitName() + kind + ": " + v.file());
            rows.add("  " + v.message());
        }
        Log.errorList("  ", rows);
    }

    /**
     * Delegated to {@link dev.skillmanager.app.ReportUseCase#printOutstanding}
     * — one banner, grouped by distinct cause, with a single copy of the
     * remedy table. This method used to hold a verbatim duplicate of that
     * table and printed one block per unit; see the delegate for why ten
     * identical blocks was the wrong report. Issue #144.
     */
    private void printOutstandingErrors() {
        dev.skillmanager.app.ReportUseCase.printOutstanding(outstandingErrors, store);
    }

    // ----------------------------------------------- helpers

    /**
     * Fourteen lines (six of them blank) per refused unit, collapsed to two.
     *
     * <p>The by-hand recipe is deleted rather than demoted: it is three git
     * commands any reader of the first line can write, it was printed once per
     * refused unit so a five-unit refusal cost seventy lines, and the
     * {@code --merge} spelling above it does the same thing correctly
     * including the stash handling the by-hand version silently omits.
     *
     * <h2>What went with it, and had to come back</h2>
     *
     * <p>The deleted recipe read {@code git fetch <upstream> HEAD}, and it was
     * the only place the run named <b>which source the merge would pull
     * from</b>. Losing it left the refusal saying, for every shape of sync,
     * {@code skill-manager sync <name> --merge} — and for a
     * {@code sync <name> --from <dir>} that command is <em>not</em> the one
     * that was refused: run as printed it merges the recorded origin instead
     * of the directory the caller pointed at. A unit installed from github and
     * synced from a {@code skill-dev} worktree — the flow
     * {@code skill-dev-skill} documents — has two different trees there, so
     * the remedy quietly did something else. Measured: with the upstream
     * dropped, the URL appeared neither on the console nor in the run log,
     * and the graph carried three separate nodes asserting the refusal names
     * its source ({@code source.sync.refuses_on_dirty},
     * {@code source.sync.refuses_without_from},
     * {@code hyper.sync.refuses.on.local.commit}).
     *
     * <p>So the source is named again, and {@code --from} is preserved in the
     * re-run — still one line, still no by-hand recipe. The store directory
     * stays too: it is where the reader resolves the conflict.
     */
    private void printMergeInstructions(String skillName, String upstream, boolean gitLatest,
                                        boolean fromDir, boolean worktreeEdits) {
        // Naming the half that fired is the whole point: the two states need
        // different things from the reader. Uncommitted work is theirs to keep
        // or discard; a store carrying commits upstream has not got is a branch
        // that has to go somewhere before it can be overwritten. The old line
        // offered both and settled neither, so every reader ran `git status` in
        // a store directory they had to find first.
        // "extra local changes" is load-bearing: four Test Graph nodes and the
        // spec assert on that phrase. So the diagnosis is APPENDED to it rather
        // than replacing it -- the contract holds, and the reader stops having
        // to run `git status` in a store directory they must find first.
        if (worktreeEdits) {
            Log.error("%s has extra local changes — uncommitted edits in its store — "
                    + "sync would overwrite them.", skillName);
        } else {
            Log.error("%s has extra local changes — commits its installed record does not name, "
                    + "which the sync target does not contain either; the working tree is clean "
                    + "— sync would overwrite them.", skillName);
        }
        String source = upstream == null || upstream.isBlank() ? "<origin>" : upstream;
        // DEF-002 was measured on exactly this line: run against the PROJECT
        // home it printed `/Users/hayde/.skill-manager/bin/cli/skill-manager
        // sync <unit> --merge`, and following that verbatim edits the ROOT
        // home. `cliSpelling` binds the remedy to the home the sync was about.
        HomeDescriptor.CliSpelling spelling = HomeDescriptor.cliSpelling(store.root());
        // CLASS 3: `sync` names no home of its own, so it was given --home
        // (#161). The binding is an ARGUMENT, never the head token: the head is
        // the token every consumer of a printed remedy substitutes.
        Log.error("  re-run with: %s sync %s%s%s --merge %s  (merges %s into %s)",
                spelling.binary(),
                skillName,
                gitLatest ? " --git-latest" : "",
                fromDir ? " --from " + HomeDescriptor.shellQuote(source) : "",
                spelling.homeArg(),
                source, unitDirFor(skillName));
        if (spelling.caveat() != null) Log.error("  note: %s", spelling.caveat());
    }

    /**
     * Where {@code name} actually lives in the store, whatever kind it is.
     *
     * <p>{@code skillDir} hard-codes {@code skills/<name>}, so a refused sync of
     * a PLUGIN named a directory that does not exist — in the one message whose
     * entire job is to tell the reader where to go resolve the conflict. The
     * sync facts carry no {@link UnitKind}, but a unit occupies exactly one
     * kind's directory on disk, so the disk is the authority.
     *
     * <p>Falls back to {@code skills/<name>} when nothing exists, which is the
     * previous behaviour and the right guess for the overwhelmingly common kind
     * — better than printing nothing when the store is in a state this renderer
     * cannot explain.
     */
    private Path unitDirFor(String name) {
        // SKILL first, explicitly rather than by relying on it being declared
        // first in UnitKind: if a name somehow occupies two kinds' directories,
        // this resolves the way it always did, so the fix cannot change an
        // already-correct message. Reordering the enum then stays a harmless
        // edit instead of a silent behaviour change here.
        for (UnitKind kind : List.of(UnitKind.SKILL, UnitKind.PLUGIN, UnitKind.DOC,
                UnitKind.HARNESS)) {
            Path dir = store.unitDir(name, kind);
            if (Files.isDirectory(dir)) return dir;
        }
        return store.skillDir(name);
    }

    private static String shortHash(String hash) {
        if (hash == null) return "?";
        return hash.substring(0, Math.min(7, hash.length()));
    }
}
