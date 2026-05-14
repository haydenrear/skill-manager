package dev.skillmanager.effects;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.InstallResult;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.sync.SkillSync;
import dev.skillmanager.tools.ToolDependency;
import dev.skillmanager.tools.ToolInstallRecorder;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes effects to real services. Each handler emits a typed
 * {@link ContextFact} list on its receipt — no stringly-typed maps. The
 * {@link EffectContext} threads source-record reads/writes through the
 * program so error mutations invalidate the cache exactly once per write.
 */
public final class LiveInterpreter implements ProgramInterpreter {

    private final SkillStore store;
    private final GatewayConfig gateway;
    private final boolean json;

    public LiveInterpreter(SkillStore store) { this(store, null); }

    public LiveInterpreter(SkillStore store, GatewayConfig gateway) {
        this(store, gateway, false);
    }

    public LiveInterpreter(SkillStore store, GatewayConfig gateway, boolean json) {
        this.store = store;
        this.gateway = gateway;
        this.json = json;
    }

    @Override
    public <R> R run(Program<R> program) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway, json);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        R result = runWithContext(program, ctx);
        renderer.onComplete();
        return result;
    }

    @Override
    public <R> R runStaged(StagedProgram<R> staged) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway, json);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        List<EffectReceipt> all = new ArrayList<>();
        List<EffectReceipt> s1 = runEffects(staged.stage1(), ctx);
        all.addAll(s1);
        // Halt is meant to terminate the WHOLE multi-stage program — not
        // just the stage that raised it. If any stage-1 receipt signals
        // {@link Continuation#HALT} (resolve-failed, policy gate
        // rejected, "remove first" precondition fired) running stage 2
        // on top of the half-built state would let the post-update tail
        // fire on nothing.
        boolean halted = s1.stream().anyMatch(r -> r.continuation() == Continuation.HALT);
        if (!halted) {
            Program<?> stage2 = staged.stage2().apply(ctx);
            all.addAll(runEffects(stage2, ctx));
        }
        renderer.onComplete();
        return staged.decoder().decode(all);
    }

    /**
     * Run a (sub-)program against an existing {@link EffectContext} —
     * source-record cache stays warm, error writes from the parent
     * program are visible, and any error writes by the sub-program are
     * visible to subsequent effects in the parent. The renderer is
     * shared with the parent so accumulated state survives the
     * boundary; only the top-level {@link #run} calls
     * {@link ProgramRenderer#onComplete}.
     */
    public <R> R runWithContext(Program<R> program, EffectContext ctx) {
        return program.decoder().decode(runEffects(program, ctx));
    }

    /**
     * Run one effect against {@code ctx}, with the same exception-to-receipt
     * trapping the main loop applies. Used by {@link Executor} so it can
     * interleave compensation tracking between effects without rebuilding
     * the dispatch machinery.
     */
    public EffectReceipt runOne(SkillEffect effect, EffectContext ctx) {
        EffectReceipt r;
        try {
            r = execute(effect, ctx);
        } catch (Exception ex) {
            r = EffectReceipt.failed(effect, ex.getMessage());
        }
        ctx.renderer().onReceipt(r);
        return r;
    }

    /**
     * Drive a program's main effects + alwaysAfter cleanup against the
     * supplied context, returning the flat receipt list. Decoder
     * application is left to the caller so {@link #runStaged} can
     * concatenate two stages' receipts before decoding once.
     */
    private List<EffectReceipt> runEffects(Program<?> program, EffectContext ctx) {
        ProgramRenderer renderer = ctx.renderer();
        List<EffectReceipt> receipts = new ArrayList<>();
        boolean halted = false;
        for (SkillEffect effect : program.effects()) {
            if (halted) {
                EffectReceipt r = EffectReceipt.skipped(effect, "halted");
                receipts.add(r);
                renderer.onReceipt(r);
                continue;
            }
            EffectReceipt r;
            try {
                r = execute(effect, ctx);
            } catch (Exception ex) {
                r = EffectReceipt.failed(effect, ex.getMessage());
            }
            receipts.add(r);
            renderer.onReceipt(r);
            // Halt decision is now exclusively the receipt's continuation.
            // Status FAILED no longer implicitly halts — each effect's
            // continuationOnFail() opts in via its receipt's HALT
            // continuation if downstream effects can't tolerate the
            // failure.
            if (r.continuation() == Continuation.HALT) halted = true;
        }
        // alwaysAfter runs unconditionally — for cleanup that must happen
        // even when the main effect chain halted (e.g. CleanupResolvedGraph).
        for (SkillEffect effect : program.alwaysAfter()) {
            EffectReceipt r;
            try {
                r = execute(effect, ctx);
            } catch (Exception ex) {
                r = EffectReceipt.failed(effect, ex.getMessage());
            }
            receipts.add(r);
            renderer.onReceipt(r);
        }
        return receipts;
    }

    private EffectReceipt execute(SkillEffect effect, EffectContext ctx) throws IOException {
        return switch (effect) {
            case SkillEffect.ConfigureRegistry e -> configureRegistry(e, ctx);
            case SkillEffect.EnsureGateway e -> ensureGateway(e);
            case SkillEffect.StopGateway e -> stopGateway(e);
            case SkillEffect.ConfigureGateway e -> configureGateway(e, ctx);
            case SkillEffect.SetupPackageManagerRuntime e -> setupPmRuntime(e);
            case SkillEffect.InstallPackageManager e -> installPackageManager(e);
            case SkillEffect.SnapshotMcpDeps e -> snapshotMcpDeps(e, ctx);
            case SkillEffect.RejectIfAlreadyInstalled e -> rejectIfInstalled(e, ctx);
            case SkillEffect.RejectIfTopLevelInstalled e -> rejectIfTopLevelInstalled(e, ctx);
            case SkillEffect.CheckInstallPolicyGate e -> checkInstallPolicyGate(e, ctx);
            case SkillEffect.BuildResolveGraphFromSource e -> ResolveGraphHandlers.buildFromSource(e, ctx);
            case SkillEffect.BuildResolveGraphFromBundledSkills e -> ResolveGraphHandlers.buildFromBundledSkills(e, ctx);
            case SkillEffect.BuildResolveGraphFromUnmetReferences e -> ResolveGraphHandlers.buildFromUnmetReferences(e, ctx);
            case SkillEffect.BuildInstallPlan e -> buildInstallPlan(e, ctx);
            case SkillEffect.RunInstallPlan e -> runInstallPlan(e, ctx);
            case SkillEffect.CleanupResolvedGraph e -> cleanupGraph(e, ctx);
            case SkillEffect.PrintInstalledSummary e -> printInstalledSummary(e, ctx);
            case SkillEffect.SyncFromLocalDir e -> syncFromLocalDir(e, ctx);
            case SkillEffect.CommitUnitsToStore e -> commitUnits(e, ctx);
            case SkillEffect.RecordAuditPlan e -> recordAudit(e, ctx);
            case SkillEffect.RecordSourceProvenance e -> recordProvenance(e, ctx);
            case SkillEffect.OnboardUnit e -> onboardUnit(e, ctx);
            case SkillEffect.EnsureTool e -> ensureTool(e);
            case SkillEffect.RunCliInstall e -> runCliInstall(e, ctx);
            case SkillEffect.RegisterMcpServer e -> registerMcpServer(e, ctx);
            case SkillEffect.UnregisterMcpOrphan e -> unregisterOrphan(e);
            case SkillEffect.UnregisterMcpOrphans e -> unregisterOrphans(e, ctx);
            case SkillEffect.SyncAgents e -> syncAgents(e, ctx);
            case SkillEffect.RefreshHarnessPlugins e -> refreshHarnessPlugins(e, ctx);
            case SkillEffect.SyncGit e -> SyncGitHandler.run(e, ctx);
            case SkillEffect.RemoveUnitFromStore e -> removeFromStore(e, ctx);
            case SkillEffect.UnlinkAgentUnit e -> unlinkAgentUnit(e);
            case SkillEffect.UnlinkAgentMcpEntry e -> unlinkAgentMcpEntry(e);
            case SkillEffect.ScaffoldSkill e -> scaffoldSkill(e);
            case SkillEffect.ScaffoldPlugin e -> scaffoldPlugin(e);
            case SkillEffect.InitializePolicy e -> initializePolicy(e, ctx);
            case SkillEffect.LoadOutstandingErrors e -> loadOutstandingErrors(e, ctx);
            case SkillEffect.AddUnitError e -> addError(e, ctx);
            case SkillEffect.ClearUnitError e -> clearError(e, ctx);
            case SkillEffect.ValidateAndClearError e -> validateAndClear(e, ctx);
            case SkillEffect.InstallTools e -> installTools(e);
            case SkillEffect.InstallCli e -> installCli(e);
            case SkillEffect.RegisterMcp e -> registerMcp(e, ctx);
            case SkillEffect.UpdateUnitsLock e -> updateUnitsLock(e);
            case SkillEffect.CreateBinding e -> createBinding(e, ctx);
            case SkillEffect.RemoveBinding e -> removeBinding(e, ctx);
            case SkillEffect.MaterializeProjection e -> materializeProjection(e);
            case SkillEffect.UnmaterializeProjection e -> unmaterializeProjection(e);
            case SkillEffect.SyncDocRepo e -> syncDocRepo(e, ctx);
            case SkillEffect.SyncHarness e -> syncHarness(e, ctx);
        };
    }

    private EffectReceipt updateUnitsLock(SkillEffect.UpdateUnitsLock e) {
        try {
            dev.skillmanager.lock.UnitsLockWriter.atomicWrite(e.target(), e.path());
            return EffectReceipt.ok(e, new ContextFact.UnitsLockUpdated(
                    e.path().toString(), e.target().units().size()));
        } catch (IOException io) {
            return EffectReceipt.failed(e, "could not write " + e.path() + ": " + io.getMessage());
        }
    }

    private EffectReceipt configureRegistry(SkillEffect.ConfigureRegistry e, EffectContext ctx) {
        if (e.url() == null || e.url().isBlank()) {
            return EffectReceipt.skipped(e, "no override");
        }
        try {
            dev.skillmanager.registry.RegistryConfig.resolve(ctx.store(), e.url());
            return EffectReceipt.ok(e, new ContextFact.RegistryConfigured(e.url()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, "invalid registry URL " + e.url() + ": " + ex.getMessage());
        }
    }

    private EffectReceipt ensureGateway(SkillEffect.EnsureGateway e) {
        if (e.gateway() == null) return EffectReceipt.skipped(e, "no gateway configured");
        GatewayClient ping = new GatewayClient(e.gateway());
        if (ping.ping()) {
            return EffectReceipt.ok(e, new ContextFact.GatewayAlreadyRunning());
        }
        java.net.URI base = e.gateway().baseUrl();
        String host = base.getHost();
        boolean isLocal = "127.0.0.1".equals(host) || "localhost".equals(host) || "0.0.0.0".equals(host);
        if (!isLocal) {
            return EffectReceipt.partial(e, "remote gateway unreachable",
                    new ContextFact.GatewayUnreachable(host));
        }
        int port = base.getPort() > 0 ? base.getPort() : 51717;
        dev.skillmanager.mcp.GatewayRuntime rt = new dev.skillmanager.mcp.GatewayRuntime(store);
        try {
            if (!rt.isRunning()) {
                rt.ensureVenv();
                rt.start(host, port);
            }
            java.time.Duration wait = e.timeout() == null
                    ? java.time.Duration.ofSeconds(20)
                    : e.timeout();
            if (rt.waitForHealthy(base.toString(), wait)) {
                GatewayConfig.persist(store, base.toString());
                return EffectReceipt.ok(e, new ContextFact.GatewayStarted(host, port));
            }
            return EffectReceipt.failed(e,
                    "gateway did not become healthy within " + wait.toSeconds()
                            + "s; see " + rt.logFile());
        } catch (Exception ex) {
            return EffectReceipt.failed(e, "failed to start gateway: " + ex.getMessage());
        }
    }

    private EffectReceipt commitUnits(SkillEffect.CommitUnitsToStore e, EffectContext ctx) {
        var graph = e.graph() != null
                ? e.graph()
                : ctx.resolvedGraph().orElse(null);
        if (graph == null) {
            return EffectReceipt.skipped(e, "no resolved graph in context");
        }
        // Keep ctx in sync so downstream effects that read from ctx see
        // the same graph the handler is operating on.
        ctx.setResolvedGraph(graph);
        List<ContextFact> facts = new ArrayList<>();
        // Track every (name, kind) tuple we may have touched (deleted-old +
        // about-to-copy) so a mid-copy failure rolls back the partially-
        // written destination too — committed.add was previously gated on
        // copy success, leaving a half-written dir behind on failure.
        // Recording the kind alongside the name means rollback resolves the
        // same dst directory the forward path used (skills/ for SKILL,
        // plugins/ for PLUGIN).
        record Touched(String name, dev.skillmanager.model.UnitKind kind) {}
        List<Touched> touched = new ArrayList<>();
        try {
            for (var r : graph.resolved()) {
                dev.skillmanager.model.UnitKind k = r.unit().kind();
                Path dst = ctx.store().unitDir(r.name(), k);
                if (java.nio.file.Files.exists(dst)) {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(dst);
                }
                dev.skillmanager.shared.util.Fs.ensureDir(dst.getParent());
                touched.add(new Touched(r.name(), k));
                Path unitRoot = r.unit().sourcePath();
                dev.skillmanager.shared.util.Fs.copyRecursive(unitRoot, dst);
                facts.add(new ContextFact.SkillCommitted(r.name()));
            }
        } catch (Exception ex) {
            for (Touched t : touched) {
                try {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(ctx.store().unitDir(t.name(), t.kind()));
                    facts.add(new ContextFact.CommitRolledBack(t.name()));
                } catch (Exception cleanupErr) {
                    Log.warn("rollback: could not delete %s — %s", t.name(), cleanupErr.getMessage());
                }
            }
            return EffectReceipt.failed(e, facts, "commit failed: " + ex.getMessage());
        }
        return EffectReceipt.ok(e, facts);
    }

    private EffectReceipt recordAudit(SkillEffect.RecordAuditPlan e, EffectContext ctx) {
        InstallPlan plan = ctx.plan();
        if (plan == null) {
            return EffectReceipt.skipped(e, "no plan in context (BuildInstallPlan didn't run)");
        }
        try {
            new dev.skillmanager.plan.AuditLog(ctx.store()).recordPlan(plan, e.verb());
            return EffectReceipt.ok(e, new ContextFact.AuditRecorded(e.verb()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt recordProvenance(SkillEffect.RecordSourceProvenance e, EffectContext ctx) {
        var graph = e.graph() != null
                ? e.graph()
                : ctx.resolvedGraph().orElse(null);
        if (graph == null) {
            return EffectReceipt.skipped(e, "no resolved graph in context");
        }
        SourceProvenanceRecorder.run(graph, ctx);
        ctx.invalidate();
        return EffectReceipt.ok(e, new ContextFact.ProvenanceRecorded(graph.resolved().size()));
    }

    private EffectReceipt onboardUnit(SkillEffect.OnboardUnit e, EffectContext ctx) throws IOException {
        AgentUnit unit = e.unit();
        if (ctx.source(unit.name()).isPresent()) {
            return EffectReceipt.skipped(e, "installed-record already present");
        }
        Path unitDir = ctx.store().unitDir(unit.name(), unit.kind());
        InstalledUnit.Kind transport;
        String origin = null, hash = null, gitRef = null;
        if (GitOps.isGitRepo(unitDir)) {
            transport = InstalledUnit.Kind.GIT;
            origin = GitOps.originUrl(unitDir);
            hash = GitOps.headHash(unitDir);
            gitRef = GitOps.detectInstallRef(unitDir);
        } else {
            transport = InstalledUnit.Kind.LOCAL_DIR;
        }
        InstalledUnit source = new InstalledUnit(
                unit.name(), unit.version(), transport, InstalledUnit.InstallSource.UNKNOWN,
                origin, hash, gitRef, UnitStore.nowIso(), null,
                unit.kind());
        if (transport == InstalledUnit.Kind.LOCAL_DIR
                && !dev.skillmanager.lifecycle.BundledSkills.isBundled(unit.name())) {
            source = source.withErrorAdded(new InstalledUnit.UnitError(
                    InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION,
                    "unit is not git-tracked — sync/upgrade unavailable until reinstalled from a git source",
                    UnitStore.nowIso()));
        } else if (transport == InstalledUnit.Kind.GIT && (origin == null || origin.isBlank())) {
            source = source.withErrorAdded(new InstalledUnit.UnitError(
                    InstalledUnit.ErrorKind.NO_GIT_REMOTE,
                    "git-tracked but no origin remote configured",
                    UnitStore.nowIso()));
        }
        ctx.writeSource(source);
        return EffectReceipt.ok(e, new ContextFact.SkillOnboarded(unit.name(), transport));
    }

    private EffectReceipt installTools(SkillEffect.InstallTools e) throws IOException {
        List<AgentUnit> units = freshen(e.units());
        InstallPlan plan = buildPlan(units);
        ToolInstallRecorder.run(plan, store);
        return EffectReceipt.ok(e, new ContextFact.ToolsInstalledFor(units.size()));
    }

    private EffectReceipt installCli(SkillEffect.InstallCli e) throws IOException {
        List<AgentUnit> units = freshen(e.units());
        InstallPlan plan = buildPlan(units);
        CliInstallRecorder.run(plan, store);
        return EffectReceipt.ok(e, new ContextFact.CliInstalledFor(units.size()));
    }

    /**
     * Reload skill-kind units from disk so the handler sees manifest
     * changes from a sync's merge step. Plugin-kind units pass through
     * unchanged (kind-aware reload lands in ticket 11). Skills whose
     * dirs vanished (rare — concurrent uninstall) keep the supplied
     * stale value.
     */
    private List<AgentUnit> freshen(List<AgentUnit> stale) {
        List<AgentUnit> out = new ArrayList<>(stale.size());
        for (AgentUnit u : stale) {
            if (u instanceof dev.skillmanager.model.SkillUnit) {
                try {
                    Skill reloaded = store.load(u.name()).orElse(null);
                    out.add(reloaded != null ? reloaded.asUnit() : u);
                } catch (IOException io) {
                    out.add(u);
                }
            } else {
                out.add(u);
            }
        }
        return out;
    }

    private InstallPlan buildPlan(List<AgentUnit> units) throws IOException {
        Policy policy = Policy.load(store);
        CliLock lock = CliLock.load(store);
        PackageManagerRuntime pmRuntime = new PackageManagerRuntime(store);
        return new PlanBuilder(policy, lock, pmRuntime)
                .plan(units, true, true, store.cliBinDir());
    }

    private EffectReceipt registerMcp(SkillEffect.RegisterMcp e, EffectContext ctx) throws IOException {
        List<AgentUnit> units = freshen(e.units());
        if (!new GatewayClient(e.gateway()).ping()) {
            for (AgentUnit u : units) {
                if (u.mcpDependencies().isEmpty()) continue;
                ctx.addError(u.name(), InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE,
                        "gateway at " + e.gateway().baseUrl() + " unreachable");
            }
            return EffectReceipt.skipped(e, "gateway unreachable");
        }

        // McpWriter.registerAll still consumes List<Skill> — wrap each unit
        // in a Skill carrier with just its name + MCP deps so plugin-kind
        // units flow through. Pure-typing wrap; no on-disk lookup. The
        // McpWriter widening lands in ticket 11.
        List<Skill> mcpCarriers = new ArrayList<>(units.size());
        for (AgentUnit u : units) mcpCarriers.add(asMcpCarrier(u));
        McpWriter writer = new McpWriter(e.gateway());
        List<InstallResult> results = writer.registerAll(mcpCarriers);

        for (AgentUnit u : units) {
            if (u.mcpDependencies().isEmpty()) continue;
            ctx.clearError(u.name(), InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE);
        }
        List<ContextFact> facts = new ArrayList<>();
        int erroredCount = 0;
        for (InstallResult r : results) {
            String owner = ownerOf(units, r.serverId());
            if (owner == null) continue;
            if (InstallResult.Status.ERROR.code.equals(r.status())) {
                ctx.addError(owner, InstalledUnit.ErrorKind.MCP_REGISTRATION_FAILED,
                        r.serverId() + ": " + r.message());
                facts.add(new ContextFact.McpServerRegistrationFailed(owner, r));
                erroredCount++;
            } else {
                ctx.clearError(owner, InstalledUnit.ErrorKind.MCP_REGISTRATION_FAILED);
                facts.add(new ContextFact.McpServerRegistered(owner, r));
            }
        }
        return erroredCount == 0
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, erroredCount + " unit(s) had MCP errors");
    }

    private static Skill asMcpCarrier(AgentUnit u) {
        return new Skill(u.name(), u.description(), u.version(),
                List.of(), List.of(), u.mcpDependencies(),
                java.util.Map.of(), "", null);
    }

    private EffectReceipt unregisterOrphans(SkillEffect.UnregisterMcpOrphans e, EffectContext ctx) {
        var preMcpDeps = ctx.preMcpDeps();
        if (preMcpDeps.isEmpty()) return EffectReceipt.skipped(e, "no snapshot in context");
        try {
            List<String> orphans = dev.skillmanager.app.PostUpdateUseCase.computeOrphans(
                    preMcpDeps, ctx.store().listInstalled());
            if (orphans.isEmpty()) return EffectReceipt.ok(e);
            // Inline the per-orphan calls so facts land on this single
            // receipt — running them through a sub-program would let the
            // shared renderer print each OrphanUnregistered line, then
            // print them again when this wrapper receipt renders.
            GatewayClient client = new GatewayClient(e.gateway());
            if (!client.ping()) return EffectReceipt.skipped(e, "gateway unreachable");
            List<ContextFact> facts = new ArrayList<>();
            int failed = 0;
            for (String id : orphans) {
                try {
                    if (client.unregister(id)) {
                        facts.add(new ContextFact.OrphanUnregistered(id));
                    }
                } catch (Exception ex) {
                    failed++;
                    Log.warn("orphan %s: %s", id, ex.getMessage());
                }
            }
            return failed == 0
                    ? EffectReceipt.ok(e, facts)
                    : EffectReceipt.partial(e, facts, failed + " orphan(s) failed to unregister");
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt unregisterOrphan(SkillEffect.UnregisterMcpOrphan e) {
        GatewayClient client = new GatewayClient(e.gateway());
        if (!client.ping()) return EffectReceipt.skipped(e, "gateway unreachable");
        try {
            if (client.unregister(e.serverId())) {
                return EffectReceipt.ok(e, new ContextFact.OrphanUnregistered(e.serverId()));
            }
            return EffectReceipt.skipped(e, "not registered");
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    /**
     * Per-(agent, skill) sync. Each pair gets its own try/catch and its own
     * {@link ContextFact}. A failure for one (agent, skill) records
     * {@link InstalledUnit.ErrorKind#AGENT_SYNC_FAILED} on the skill so the
     * outstanding-errors banner surfaces it; other (agent, skill) pairs
     * keep going.
     */
    private EffectReceipt syncAgents(SkillEffect.SyncAgents e, EffectContext ctx) {
        List<AgentUnit> units = freshen(e.units());
        McpWriter writer = new McpWriter(e.gateway());
        dev.skillmanager.project.ProjectorRegistry projectors =
                dev.skillmanager.project.ProjectorRegistry.defaultRegistry();
        List<ContextFact> facts = new ArrayList<>();
        int failed = 0;
        // Per-projector x per-unit fan-out — replaces the ticket-08 split
        // path (SkillSync vs direct plugin symlink) with one strategy
        // call. Each projector's planProjection decides whether the unit
        // even projects into its agent (e.g. CodexProjector returns empty
        // for plugins). Per-(agent, unit) try/catch so one failure doesn't
        // sink the whole sweep.
        for (dev.skillmanager.project.Projector proj : projectors.projectors()) {
            for (AgentUnit u : units) {
                try {
                    List<dev.skillmanager.project.Projection> planned = proj.planProjection(u, ctx.store());
                    for (dev.skillmanager.project.Projection p : planned) {
                        proj.apply(p);
                    }
                    // No projection (e.g. Codex skipping a plugin) is not a
                    // failure — but we also don't emit an "AgentSkillSynced"
                    // fact for it since no work happened. Only fact when we
                    // actually projected.
                    if (!planned.isEmpty()) {
                        facts.add(new ContextFact.AgentSkillSynced(proj.agentId(), u.name()));
                        tryClearError(ctx, u.name(), InstalledUnit.ErrorKind.AGENT_SYNC_FAILED);
                        // Record a DEFAULT_AGENT binding in the projection
                        // ledger so uninstall and `bindings list` see this
                        // implicit projection. Idempotent: re-syncing
                        // overwrites the existing default binding for the
                        // same (agent, unit) pair.
                        recordDefaultAgentBinding(ctx, proj, u, planned);
                    }
                } catch (Exception ex) {
                    facts.add(new ContextFact.AgentSkillSyncFailed(proj.agentId(), u.name(), ex.getMessage()));
                    tryAddError(ctx, u.name(), InstalledUnit.ErrorKind.AGENT_SYNC_FAILED,
                            proj.agentId() + ": " + ex.getMessage());
                    failed++;
                }
            }
        }
        // MCP config write is per-agent (not per-unit) and orthogonal to
        // the projection — every agent that has skills/plugins also wants
        // the virtual-mcp-gateway entry. Iterate Agent.all() directly so
        // adding a Projector-less agent (display-only) wouldn't drop its
        // MCP config write.
        for (Agent agent : Agent.all()) {
            try {
                McpWriter.ConfigChange change = writer.writeAgentEntry(agent);
                facts.add(new ContextFact.AgentMcpConfigChanged(
                        agent.id(), change, agent.mcpConfigPath().toString()));
            } catch (Exception ex) {
                facts.add(new ContextFact.AgentMcpConfigFailed(agent.id(), ex.getMessage()));
                failed++;
            }
        }
        return failed == 0
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, failed + " agent step(s) failed");
    }

    /**
     * Reconcile the skill-manager plugin marketplace + each harness CLI's
     * view of it with the current installed-plugin set. Idempotent across
     * reruns; failures on one harness don't block the other.
     *
     * <h3>Order</h3>
     * <ol>
     *   <li>Regenerate {@code <store>/plugin-marketplace/}.</li>
     *   <li>For each {@link dev.skillmanager.project.HarnessPluginCli.Driver}:
     *       if available, marketplace add → marketplace update → for each
     *       reinstall name run uninstall+install, for each uninstall name
     *       run uninstall. Clear {@code HARNESS_CLI_UNAVAILABLE} on every
     *       plugin once a successful add lands.</li>
     *   <li>For each missing driver, record {@code HARNESS_CLI_UNAVAILABLE}
     *       on every plugin in the marketplace with the {@code brew
     *       install} hint so the closing report surfaces it.</li>
     *   <li>One-time cleanup of legacy
     *       {@code <agentPluginsDir>/<name>} entries.</li>
     * </ol>
     */
    private EffectReceipt refreshHarnessPlugins(SkillEffect.RefreshHarnessPlugins e, EffectContext ctx) {
        List<ContextFact> facts = new ArrayList<>();
        int failed = 0;
        List<String> currentPlugins;
        dev.skillmanager.project.PluginMarketplace mp =
                new dev.skillmanager.project.PluginMarketplace(ctx.store());
        try {
            currentPlugins = mp.regenerate();
            facts.add(new ContextFact.PluginMarketplaceRegenerated(
                    mp.manifestPath().toString(), currentPlugins.size()));
        } catch (IOException io) {
            return EffectReceipt.failed(e, "marketplace regeneration: " + io.getMessage());
        }

        // Plugins that should end up known to each harness CLI: the
        // current set the user has installed. {@code uninstall} list is
        // for plugins removed in the same program — the marketplace.json
        // already excludes them, so we just need the harness to drop its
        // own record.
        List<String> reinstall = e.reinstall();
        List<String> uninstall = e.uninstall();

        // Legacy cleanup: pre-marketplace builds left symlinks under
        // <agentPluginsDir>/<name>. Drop them so the harness doesn't
        // try to load the plugin from the wrong namespace.
        for (dev.skillmanager.agent.Agent agent : dev.skillmanager.agent.Agent.all()) {
            try {
                dev.skillmanager.project.PluginMarketplace.cleanupLegacyAgentPluginEntries(
                        agent.pluginsDir(), currentPlugins);
            } catch (IOException io) {
                Log.warn("legacy plugin cleanup for %s: %s", agent.id(), io.getMessage());
            }
        }

        // Resolve availability up front so HARNESS_CLI_UNAVAILABLE is set
        // ONCE based on the union of missing drivers, not flickered per
        // iteration. Iteration order would otherwise determine whether
        // the error is set or cleared at end-of-loop — a real missing
        // driver could be erased by a later available driver's success.
        List<dev.skillmanager.project.HarnessPluginCli.Driver> drivers =
                dev.skillmanager.project.HarnessPluginCli.defaultDrivers();
        List<dev.skillmanager.project.HarnessPluginCli.Driver> missing = new ArrayList<>();
        List<dev.skillmanager.project.HarnessPluginCli.Driver> available = new ArrayList<>();
        for (var d : drivers) {
            if (d.available()) available.add(d); else missing.add(d);
        }
        if (!missing.isEmpty()) {
            StringBuilder msg = new StringBuilder("missing harness CLI on PATH: ");
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) msg.append(", ");
                var d = missing.get(i);
                msg.append(d.binary()).append(" (try: ").append(d.installHint()).append(")");
                facts.add(new ContextFact.HarnessCliMissing(
                        d.agentId(), d.binary(), d.installHint()));
            }
            for (String pluginName : currentPlugins) {
                tryAddError(ctx, pluginName,
                        InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE, msg.toString());
            }
        } else {
            // Every required harness CLI is on PATH — drop any stale
            // unavailability error from a prior sync.
            for (String pluginName : currentPlugins) {
                tryClearError(ctx, pluginName, InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE);
            }
        }

        // Per-plugin failure tracking across drivers. A plugin is "agent
        // sync failed" only when at least one driver actually failed for
        // it in this run; clearing a prior AGENT_SYNC_FAILED waits until
        // every driver has attempted, so a vacuous success on one driver
        // (Codex's reinstallPlugin is a no-op) can't erase the failure
        // recorded by a real driver (Claude) earlier in the same loop.
        java.util.Set<String> reinstallFailures = new java.util.HashSet<>();

        for (var driver : available) {
            try {
                dev.skillmanager.project.HarnessPluginCli.Result added =
                        driver.ensureMarketplaceAdded(mp.root());
                facts.add(new ContextFact.HarnessPluginCli(
                        driver.agentId(), null, "marketplace-add", added.ok(),
                        added.ok() ? null : truncate(added.stderr().isBlank() ? added.stdout() : added.stderr())));
                if (!added.ok()) failed++;

                if (added.ok()) {
                    dev.skillmanager.project.HarnessPluginCli.Result updated =
                            driver.refreshMarketplace(mp.root());
                    facts.add(new ContextFact.HarnessPluginCli(
                            driver.agentId(), null, "marketplace-update", updated.ok(),
                            updated.ok() ? null : truncate(updated.stderr())));
                    if (!updated.ok()) failed++;
                }

                for (String name : reinstall) {
                    dev.skillmanager.project.HarnessPluginCli.Result r = driver.reinstallPlugin(name);
                    facts.add(new ContextFact.HarnessPluginCli(
                            driver.agentId(), name, "install", r.ok(),
                            r.ok() ? null : truncate(r.stderr())));
                    if (!r.ok()) {
                        reinstallFailures.add(name);
                        tryAddError(ctx, name, InstalledUnit.ErrorKind.AGENT_SYNC_FAILED,
                                driver.agentId() + " plugin install: " + truncate(r.stderr()));
                        failed++;
                    }
                }
                for (String name : uninstall) {
                    dev.skillmanager.project.HarnessPluginCli.Result r = driver.uninstallPlugin(name);
                    facts.add(new ContextFact.HarnessPluginCli(
                            driver.agentId(), name, "uninstall", r.ok(),
                            r.ok() ? null : truncate(r.stderr())));
                }
            } catch (Exception ex) {
                facts.add(new ContextFact.HarnessPluginCli(
                        driver.agentId(), null, "marketplace-add", false, ex.getMessage()));
                failed++;
            }
        }

        // Now that every available driver has attempted its installs,
        // clear AGENT_SYNC_FAILED only on plugins where no driver
        // failed in this run.
        for (String name : reinstall) {
            if (!reinstallFailures.contains(name)) {
                tryClearError(ctx, name, InstalledUnit.ErrorKind.AGENT_SYNC_FAILED);
            }
        }

        return failed == 0
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, failed + " harness CLI step(s) failed");
    }

    private static String truncate(String s) {
        if (s == null) return null;
        String trimmed = s.strip();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240) + "...";
    }

    private static void tryAddError(EffectContext ctx, String skillName,
                                    InstalledUnit.ErrorKind kind, String message) {
        try { ctx.addError(skillName, kind, message); }
        catch (IOException io) { Log.warn("could not record %s for %s: %s", kind, skillName, io.getMessage()); }
    }

    private static void tryClearError(EffectContext ctx, String skillName, InstalledUnit.ErrorKind kind) {
        try { ctx.clearError(skillName, kind); }
        catch (IOException io) { Log.warn("could not clear %s on %s: %s", kind, skillName, io.getMessage()); }
    }

    private EffectReceipt addError(SkillEffect.AddUnitError e, EffectContext ctx) throws IOException {
        ctx.addError(e.unitName(), e.kind(), e.message());
        return EffectReceipt.ok(e, new ContextFact.ErrorAdded(e.unitName(), e.kind()));
    }

    private EffectReceipt clearError(SkillEffect.ClearUnitError e, EffectContext ctx) throws IOException {
        ctx.clearError(e.unitName(), e.kind());
        return EffectReceipt.ok(e, new ContextFact.ErrorCleared(e.unitName(), e.kind()));
    }

    private EffectReceipt validateAndClear(SkillEffect.ValidateAndClearError e, EffectContext ctx) throws IOException {
        Path dir = ctx.store().skillDir(e.unitName());
        boolean cleared = false;
        switch (e.kind()) {
            case MERGE_CONFLICT -> {
                if (GitOps.isGitRepo(dir) && GitOps.unmergedFiles(dir).isEmpty()) {
                    ctx.clearError(e.unitName(), InstalledUnit.ErrorKind.MERGE_CONFLICT);
                    cleared = true;
                }
            }
            case NO_GIT_REMOTE -> {
                if (GitOps.isGitRepo(dir) && GitOps.originUrl(dir) != null) {
                    ctx.clearError(e.unitName(), InstalledUnit.ErrorKind.NO_GIT_REMOTE);
                    cleared = true;
                }
            }
            case NEEDS_GIT_MIGRATION -> {
                if (GitOps.isGitRepo(dir)) {
                    ctx.clearError(e.unitName(), InstalledUnit.ErrorKind.NEEDS_GIT_MIGRATION);
                    cleared = true;
                }
            }
            case GATEWAY_UNAVAILABLE, AGENT_SYNC_FAILED,
                 MCP_REGISTRATION_FAILED, REGISTRY_UNAVAILABLE,
                 AUTHENTICATION_NEEDED -> {
                // No cheap "is it really fixed" probe — pinging the gateway
                // tells us nothing about whether THIS skill's MCPs are
                // registered, etc., and a registry-side auth-validity probe
                // costs a real HTTP round-trip per unit. Handlers clear
                // these on actual success (RegisterMcp / SyncAgents /
                // SyncGit registry lookup → AUTHENTICATION_NEEDED clears
                // the moment the next describeVersion succeeds).
            }
            case HARNESS_CLI_UNAVAILABLE -> {
                // Probe is cheap: are both harness CLIs on PATH? Fully
                // available → drop the error. RefreshHarnessPlugins will
                // also clear it on successful driver runs, but the
                // reconciler runs without forcing the full plugin
                // refresh, so we offer a fast self-clear path here.
                boolean allAvailable = true;
                for (var d : dev.skillmanager.project.HarnessPluginCli.defaultDrivers()) {
                    if (!d.available()) { allAvailable = false; break; }
                }
                if (allAvailable) {
                    ctx.clearError(e.unitName(), InstalledUnit.ErrorKind.HARNESS_CLI_UNAVAILABLE);
                    cleared = true;
                }
            }
        }
        return EffectReceipt.ok(e, new ContextFact.ErrorValidated(e.unitName(), e.kind(), cleared));
    }

    private static String ownerOf(List<AgentUnit> units, String mcpServerId) {
        for (AgentUnit u : units) {
            for (McpDependency d : u.mcpDependencies()) {
                if (d.name().equals(mcpServerId)) return u.name();
            }
        }
        return null;
    }

    // -------------------------------------------------- pre-flight precondition checks

    private EffectReceipt snapshotMcpDeps(SkillEffect.SnapshotMcpDeps e, EffectContext ctx) {
        try {
            ctx.setPreMcpDeps(dev.skillmanager.app.PostUpdateUseCase.snapshotMcpDeps(ctx.store()));
            return EffectReceipt.ok(e);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt rejectIfTopLevelInstalled(SkillEffect.RejectIfTopLevelInstalled e, EffectContext ctx) {
        var graph = ctx.resolvedGraph().orElse(null);
        if (graph == null || graph.resolved().isEmpty()) {
            return EffectReceipt.skipped(e, "no resolved graph in context");
        }
        // The resolver lists top-level coords first (matching the input
        // order); the first resolved unit is the one the user asked for.
        String name = graph.resolved().get(0).name();
        return rejectIfInstalled(new SkillEffect.RejectIfAlreadyInstalled(name), ctx);
    }

    private EffectReceipt checkInstallPolicyGate(SkillEffect.CheckInstallPolicyGate e, EffectContext ctx) {
        var graph = ctx.resolvedGraph().orElse(null);
        InstallPlan plan = ctx.plan();
        if (graph == null || plan == null) {
            return EffectReceipt.skipped(e, "no resolved graph or plan in context");
        }
        try {
            Policy policy = Policy.load(ctx.store());
            List<String> categorization = PlanBuilder.categorize(graph.units(), plan);
            List<dev.skillmanager.policy.PolicyGate.Category> violations =
                    dev.skillmanager.policy.PolicyGate.violations(categorization, policy.install());
            if (violations.isEmpty()) return EffectReceipt.ok(e);

            // --yes auto-accept blocked by policy → cooperative halt
            // (the effect "succeeded" at the gate's job by detecting a
            // violation; the program just shouldn't continue) — exit
            // code 5 is carried through HaltWithExitCode.
            if (e.yes()) {
                String msg = dev.skillmanager.policy.PolicyGate.formatViolationMessage(violations);
                Log.error("%s", msg);
                return EffectReceipt.okAndHalt(e, msg,
                        new ContextFact.HaltWithExitCode(5, "policy.install gate rejected --yes"));
            }
            // No TTY (CI / pipe / test harness) → same exit code as --yes
            // since a prompt would block on EOF. Surface the same
            // remediation so automation gets a clear signal.
            if (System.console() == null) {
                Log.error("install needs interactive confirmation but no TTY is attached");
                String msg = dev.skillmanager.policy.PolicyGate.formatViolationMessage(violations);
                Log.error("%s", msg);
                return EffectReceipt.okAndHalt(e, "no TTY for policy confirmation",
                        new ContextFact.HaltWithExitCode(5, "policy.install gate without TTY"));
            }
            // Interactive confirmation — print the categorization so
            // the user sees what they're approving, prompt once.
            System.out.println();
            System.out.println("install will perform actions in these gated categories:");
            for (var c : violations) System.out.println("  ! " + c.name());
            System.out.println();
            System.out.print("proceed? [y/N] ");
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in));
            String line = r.readLine();
            if (line == null || !line.trim().equalsIgnoreCase("y")) {
                Log.warn("install aborted at policy gate");
                return EffectReceipt.okAndHalt(e, "user rejected at policy gate",
                        new ContextFact.HaltWithExitCode(6, "user said no at prompt"));
            }
            return EffectReceipt.ok(e);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt rejectIfInstalled(SkillEffect.RejectIfAlreadyInstalled e, EffectContext ctx) {
        if (e.unitName() == null || e.unitName().isBlank()) return EffectReceipt.skipped(e, "no name");
        if (!ctx.store().containsUnit(e.unitName())) return EffectReceipt.ok(e);
        // Resolve the halt-message path from the actual on-disk layout —
        // a missing InstalledUnit record (e.g. plugin onboarded before
        // ticket 03's record-write landed) shouldn't make us point at the
        // wrong directory.
        Path at = ctx.store().contains(e.unitName())
                ? ctx.store().skillDir(e.unitName())
                : ctx.store().unitDir(e.unitName(), dev.skillmanager.model.UnitKind.PLUGIN);
        // Cooperative halt — the precondition check fired correctly,
        // it just found the unit already present and wants the program
        // to stop rather than overwrite.
        return EffectReceipt.okAndHalt(e,
                "unit '" + e.unitName() + "' is already installed at " + at
                        + " — remove it first (skill-manager remove " + e.unitName() + ")");
    }

    private EffectReceipt buildInstallPlan(SkillEffect.BuildInstallPlan e, EffectContext ctx) {
        try {
            var graph = e.graph() != null
                    ? e.graph()
                    : ctx.resolvedGraph().orElse(null);
            if (graph == null) {
                // Empty plan — preserves the program shape so downstream
                // effects see ctx.plan() != null. No blocked items
                // because there are no actions to plan.
                ctx.setPlan(new InstallPlan());
                return EffectReceipt.skipped(e, "no resolved graph in context");
            }
            InstallPlan plan = dev.skillmanager.app.InstallUseCase.buildPlan(ctx.store(), graph);
            dev.skillmanager.plan.PlanPrinter.print(plan);
            ctx.setPlan(plan);
            if (plan.blocked()) {
                // Cooperative halt — the plan was built successfully but
                // policy blocks some actions. Same shape as the policy
                // gate: OK status + HALT continuation.
                return EffectReceipt.okAndHalt(e, "plan has blocked items — see policy at "
                        + ctx.store().root().resolve("policy.toml"));
            }
            return EffectReceipt.ok(e);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt runInstallPlan(SkillEffect.RunInstallPlan e, EffectContext ctx) {
        InstallPlan plan = ctx.plan();
        if (plan == null) return EffectReceipt.skipped(e, "no plan in context");
        List<SkillEffect> sub = dev.skillmanager.app.PlanExpander.expand(plan, e.gateway());
        // Run as a sub-program through the same context so source-cache /
        // error-state / plan slot stay shared. The shared renderer already
        // prints each sub-receipt as it lands, so we only roll up an
        // ok/partial summary — re-emitting sub-facts on the parent receipt
        // would cause every plan-action line to print twice.
        Program<Integer> subProgram = new Program<>(
                "plan-expand-" + java.util.UUID.randomUUID(),
                sub,
                receipts -> {
                    int failed = 0;
                    for (EffectReceipt r : receipts) {
                        if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) failed++;
                    }
                    return failed;
                });
        EffectContext.Snapshot snap = ctx.snapshot();
        int failed;
        try { failed = runWithContext(subProgram, ctx); }
        finally { ctx.restore(snap); }
        return failed == 0
                ? EffectReceipt.ok(e)
                : EffectReceipt.partial(e, failed + " plan-action effect(s) failed");
    }

    private EffectReceipt cleanupGraph(SkillEffect.CleanupResolvedGraph e, EffectContext ctx) {
        try {
            var graph = e.graph() != null
                    ? e.graph()
                    : ctx.resolvedGraph().orElse(null);
            if (graph == null) return EffectReceipt.skipped(e, "no resolved graph");
            graph.cleanup();
            return EffectReceipt.ok(e);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt printInstalledSummary(SkillEffect.PrintInstalledSummary e, EffectContext ctx) {
        var graph = e.graph() != null
                ? e.graph()
                : ctx.resolvedGraph().orElse(null);
        if (graph == null) return EffectReceipt.skipped(e, "no resolved graph");
        for (var r : graph.resolved()) {
            System.out.println("INSTALLED: " + r.name()
                    + (r.version() == null ? "" : "@" + r.version())
                    + " -> " + ctx.store().skillDir(r.name()));
        }
        return EffectReceipt.ok(e);
    }

    private EffectReceipt syncFromLocalDir(SkillEffect.SyncFromLocalDir e, EffectContext ctx) {
        return SyncFromLocalDirHandler.run(e, ctx);
    }

    // -------------------------------------------------- gateway lifecycle

    private EffectReceipt stopGateway(SkillEffect.StopGateway e) {
        if (e.gateway() == null) return EffectReceipt.skipped(e, "no gateway configured");
        dev.skillmanager.mcp.GatewayRuntime rt = new dev.skillmanager.mcp.GatewayRuntime(store);
        try {
            if (!rt.isRunning()) return EffectReceipt.skipped(e, "not running");
            rt.stop(java.time.Duration.ofSeconds(10));
            return EffectReceipt.ok(e, new ContextFact.GatewayStopped());
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt configureGateway(SkillEffect.ConfigureGateway e, EffectContext ctx) {
        if (e.url() == null || e.url().isBlank()) return EffectReceipt.skipped(e, "no URL");
        try {
            GatewayConfig.persist(ctx.store(), e.url());
            return EffectReceipt.ok(e, new ContextFact.GatewayConfigured(e.url()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, "could not persist: " + ex.getMessage());
        }
    }

    // ----------------------------------------------- package-manager runtime

    private EffectReceipt setupPmRuntime(SkillEffect.SetupPackageManagerRuntime e) {
        PackageManagerRuntime rt = new PackageManagerRuntime(store);
        List<ContextFact> facts = new ArrayList<>();
        int failed = 0;
        for (ToolDependency tool : e.tools()) {
            if (tool instanceof ToolDependency.Bundled) {
                try {
                    boolean wasMissing = rt.bundledPath(tool.id()) == null;
                    rt.ensureBundled(tool.id());
                    facts.add(new ContextFact.PackageManagerReady(tool.id(),
                            tool.pm().defaultVersion, wasMissing));
                } catch (Exception ex) {
                    facts.add(new ContextFact.PackageManagerUnavailable(tool.id(), ex.getMessage()));
                    failed++;
                }
            } else if (tool instanceof ToolDependency.External ext) {
                String onPath = rt.systemPath(tool.id());
                if (onPath != null) {
                    facts.add(new ContextFact.PackageManagerReady(tool.id(), "external", false));
                } else {
                    facts.add(new ContextFact.PackageManagerUnavailable(tool.id(),
                            ext.installHint() == null ? "not on PATH" : ext.installHint()));
                    failed++;
                }
            }
        }
        return failed == 0 ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, failed + " package manager(s) unavailable");
    }

    private EffectReceipt installPackageManager(SkillEffect.InstallPackageManager e) {
        try {
            PackageManagerRuntime rt = new PackageManagerRuntime(store);
            Path installed = rt.install(e.pm(), e.version());
            String version = e.version() == null ? e.pm().defaultVersion : e.version();
            return EffectReceipt.ok(e,
                    new ContextFact.PackageManagerInstalled(e.pm().id, version, installed.toString()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt ensureTool(SkillEffect.EnsureTool e) {
        PackageManagerRuntime rt = new PackageManagerRuntime(store);
        boolean bundled = e.tool() instanceof ToolDependency.Bundled;
        try {
            if (bundled) rt.ensureBundled(e.tool().id());
            return EffectReceipt.ok(e,
                    new ContextFact.ToolEnsured(e.tool().id(), e.missingOnPath(), bundled));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt runCliInstall(SkillEffect.RunCliInstall e, EffectContext ctx) {
        // Single-dep, single-unit case — call the installer directly so the
        // failure surfaces. Going through CliInstallRecorder.run swallows
        // per-action exceptions (resilience for the bulk path) and the
        // handler would always report ok regardless of whether the install
        // actually succeeded.
        try {
            dev.skillmanager.cli.installer.InstallerRegistry registry =
                    new dev.skillmanager.cli.installer.InstallerRegistry();
            registry.installOne(e.dep(), store, e.unitName());
            try {
                CliLock lock = CliLock.load(store);
                var req = dev.skillmanager.lock.RequestedVersion.of(e.dep());
                String sha = null;
                for (var t : e.dep().install().values()) {
                    if (t.sha256() != null) { sha = t.sha256(); break; }
                }
                // Record the post-install scripts-tree fingerprint for
                // skill-script deps so the next install / sync /
                // upgrade pass can detect "scripts edited" and re-fire
                // — see SkillScriptBackend's javadoc on rerun
                // semantics. Other backends pass null (they don't
                // currently use the fingerprint column).
                String fingerprint = "skill-script".equals(e.dep().backend())
                        ? dev.skillmanager.cli.installer.SkillScriptBackend
                                .fingerprintFor(store, e.unitName(), e.dep())
                        : null;
                lock.recordInstall(e.dep().backend(), req.tool(), req.version(),
                        e.dep().spec(), sha, e.unitName(), fingerprint);
                lock.save(store);
            } catch (Exception lockErr) {
                Log.warn("cli: %s installed but lock-record failed: %s",
                        e.dep().name(), lockErr.getMessage());
            }
            return EffectReceipt.ok(e,
                    new ContextFact.CliInstalled(e.unitName(), e.dep().name(), e.dep().backend()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e,
                    List.of(new ContextFact.CliInstallFailed(e.unitName(), e.dep().name(), ex.getMessage())),
                    ex.getMessage());
        }
    }

    private static Skill singleMcpSkill(String unitName, McpDependency dep) {
        return new Skill(unitName, unitName, null,
                List.of(), List.of(), List.of(dep),
                java.util.Map.of(), "", null);
    }

    private EffectReceipt registerMcpServer(SkillEffect.RegisterMcpServer e, EffectContext ctx) {
        if (!new GatewayClient(e.gateway()).ping()) {
            try {
                ctx.addError(e.unitName(), InstalledUnit.ErrorKind.GATEWAY_UNAVAILABLE,
                        "gateway at " + e.gateway().baseUrl() + " unreachable");
            } catch (IOException io) {
                Log.warn("could not record gateway-unavailable for %s: %s", e.unitName(), io.getMessage());
            }
            return EffectReceipt.skipped(e, "gateway unreachable");
        }
        try {
            McpWriter writer = new McpWriter(e.gateway());
            // Synthesize a single-dep skill so registerAll has exactly one
            // server to register. No on-disk unit lookup — works the same
            // whether unitName names a skill (skills/<n>) or a plugin
            // (plugins/<n>).
            Skill solo = singleMcpSkill(e.unitName(), e.dep());
            List<InstallResult> results = writer.registerAll(List.of(solo));
            List<ContextFact> facts = new ArrayList<>();
            int errored = 0;
            for (InstallResult r : results) {
                if (InstallResult.Status.ERROR.code.equals(r.status())) {
                    ctx.addError(e.unitName(), InstalledUnit.ErrorKind.MCP_REGISTRATION_FAILED,
                            r.serverId() + ": " + r.message());
                    facts.add(new ContextFact.McpServerRegistrationFailed(e.unitName(), r));
                    errored++;
                } else {
                    facts.add(new ContextFact.McpServerRegistered(e.unitName(), r));
                }
            }
            if (errored == 0) ctx.clearError(e.unitName(), InstalledUnit.ErrorKind.MCP_REGISTRATION_FAILED);
            return errored == 0
                    ? EffectReceipt.ok(e, facts)
                    : EffectReceipt.partial(e, facts, "register failed");
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    // --------------------------------------------------- store / agent removal

    private EffectReceipt removeFromStore(SkillEffect.RemoveUnitFromStore e, EffectContext ctx) {
        try {
            Path dir = ctx.store().unitDir(e.unitName(), e.kind());
            if (!java.nio.file.Files.exists(dir)) return EffectReceipt.skipped(e, "not in store");
            dev.skillmanager.shared.util.Fs.deleteRecursive(dir);
            try { ctx.sourceStore().delete(e.unitName()); } catch (Exception ignored) {}
            ctx.invalidate();
            return EffectReceipt.ok(e, new ContextFact.SkillRemovedFromStore(e.unitName()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt unlinkAgentMcpEntry(SkillEffect.UnlinkAgentMcpEntry e) {
        try {
            Agent agent = Agent.byId(e.agentId());
            new McpWriter(e.gateway()).removeAgentEntry(agent);
            return EffectReceipt.ok(e, new ContextFact.AgentMcpEntryRemoved(e.agentId()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt unlinkAgentUnit(SkillEffect.UnlinkAgentUnit e) {
        // Routed through the matching Projector — same strategy as
        // SyncAgents.apply, just inverted. Synthesize a transient
        // AgentUnit so planProjection can pick the right target dir.
        AgentUnit u = unitForUnlink(e.unitName(), e.kind());
        for (dev.skillmanager.project.Projector proj :
                dev.skillmanager.project.ProjectorRegistry.defaultRegistry().projectors()) {
            if (!proj.agentId().equalsIgnoreCase(e.agentId())) continue;
            try {
                List<dev.skillmanager.project.Projection> ps = proj.planProjection(u, store);
                if (ps.isEmpty()) {
                    return EffectReceipt.skipped(e, "no projection for kind " + e.kind() + " on " + proj.agentId());
                }
                boolean anyPresent = false;
                for (dev.skillmanager.project.Projection p : ps) {
                    if (java.nio.file.Files.exists(p.target(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            || java.nio.file.Files.isSymbolicLink(p.target())) {
                        anyPresent = true;
                        proj.remove(p);
                    }
                }
                if (!anyPresent) return EffectReceipt.skipped(e, "not present");
                return EffectReceipt.ok(e, new ContextFact.AgentSkillUnlinked(e.agentId(), e.unitName()));
            } catch (Exception ex) {
                return EffectReceipt.partial(e, "unlink failed",
                        new ContextFact.AgentSkillUnlinkFailed(e.agentId(), e.unitName(), ex.getMessage()));
            }
        }
        return EffectReceipt.skipped(e, "no projector for agent " + e.agentId());
    }

    /**
     * Synthesize a transient {@link AgentUnit} carrying just the name +
     * kind needed for {@link dev.skillmanager.project.Projector#planProjection}.
     * Avoids forcing a disk re-parse for a unit we're about to unlink —
     * the projector only reads the kind from the unit interface.
     */
    private static AgentUnit unitForUnlink(String name, dev.skillmanager.model.UnitKind kind) {
        return switch (kind) {
            case SKILL -> new Skill(name, name, null,
                    List.of(), List.of(), List.of(),
                    java.util.Map.of(), "", null).asUnit();
            case PLUGIN -> new dev.skillmanager.model.PluginUnit(
                    name, null, name,
                    List.of(), List.of(), List.of(), List.of(),
                    java.util.Map.of(), List.of(), null);
            case DOC, HARNESS -> throw new IllegalStateException(
                    "doc-repos and harness templates do not project into agent dirs — "
                            + "no projector unlink path");
        };
    }

    // ---------------------------------------------------------- scaffolding

    private EffectReceipt scaffoldSkill(SkillEffect.ScaffoldSkill e) {
        return writeScaffold(e, e.dir(), e.skillName(), e.files());
    }

    private EffectReceipt scaffoldPlugin(SkillEffect.ScaffoldPlugin e) {
        return writeScaffold(e, e.dir(), e.pluginName(), e.files());
    }

    /**
     * Shared scaffold-writer for {@link SkillEffect.ScaffoldSkill} and
     * {@link SkillEffect.ScaffoldPlugin}. Creates parent dirs for each
     * file in the map (so {@code "skills/.gitkeep"} works), and writes
     * the rendered content as-is.
     */
    private EffectReceipt writeScaffold(SkillEffect effect, Path dir, String name,
                                         java.util.Map<String, String> files) {
        try {
            dev.skillmanager.shared.util.Fs.ensureDir(dir);
            for (var entry : files.entrySet()) {
                Path target = dir.resolve(entry.getKey());
                dev.skillmanager.shared.util.Fs.ensureDir(target.getParent());
                java.nio.file.Files.writeString(target, entry.getValue());
            }
            return EffectReceipt.ok(effect, new ContextFact.SkillScaffolded(name, dir.toString()));
        } catch (Exception ex) {
            return EffectReceipt.failed(effect, ex.getMessage());
        }
    }

    private EffectReceipt initializePolicy(SkillEffect.InitializePolicy e, EffectContext ctx) {
        try {
            Policy.writeDefaultIfMissing(ctx.store());
            Path path = ctx.store().root().resolve("policy.toml");
            return EffectReceipt.ok(e, new ContextFact.PolicyInitialized(path.toString()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    // -------------------------------------------------------- error report

    private EffectReceipt loadOutstandingErrors(SkillEffect.LoadOutstandingErrors e, EffectContext ctx) {
        List<ContextFact> facts = new ArrayList<>();
        try {
            for (Skill s : ctx.store().listInstalled()) {
                ctx.source(s.name()).ifPresent(src -> {
                    if (!src.hasErrors()) return;
                    for (InstalledUnit.UnitError err : src.errors()) {
                        facts.add(new ContextFact.OutstandingError(s.name(), err.kind(), err.message()));
                    }
                });
            }
            return EffectReceipt.ok(e, facts);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, facts, ex.getMessage());
        }
    }

    // ============================================================ bindings (ticket 49)

    private EffectReceipt createBinding(SkillEffect.CreateBinding e, EffectContext ctx) {
        Binding b = e.binding();
        try {
            BindingStore bs = ctx.bindingStore();
            ProjectionLedger cur = bs.read(b.unitName());
            bs.write(cur.withBinding(b));
            return EffectReceipt.ok(e, new ContextFact.BindingCreated(
                    b.unitName(), b.bindingId(),
                    b.targetRoot() == null ? null : b.targetRoot().toString(),
                    b.subElement()));
        } catch (IOException io) {
            return EffectReceipt.failed(e, "could not write binding ledger for "
                    + b.unitName() + ": " + io.getMessage());
        }
    }

    private EffectReceipt removeBinding(SkillEffect.RemoveBinding e, EffectContext ctx) {
        try {
            BindingStore bs = ctx.bindingStore();
            ProjectionLedger cur = bs.read(e.unitName());
            if (cur.findById(e.bindingId()).isEmpty()) {
                return EffectReceipt.skipped(e, "no binding " + e.bindingId() + " for " + e.unitName());
            }
            bs.write(cur.withoutBinding(e.bindingId()));
            return EffectReceipt.ok(e, new ContextFact.BindingRemoved(e.unitName(), e.bindingId()));
        } catch (IOException io) {
            return EffectReceipt.failed(e, "could not update binding ledger for "
                    + e.unitName() + ": " + io.getMessage());
        }
    }

    private EffectReceipt materializeProjection(SkillEffect.MaterializeProjection e) {
        Projection p = e.projection();
        try {
            switch (p.kind()) {
                case SYMLINK -> {
                    var skipped = applyConflictPolicy(p.destPath(), e.conflictPolicy());
                    if (skipped) {
                        return EffectReceipt.ok(e, new ContextFact.ProjectionSkippedConflict(
                                p.bindingId(), p.destPath().toString()));
                    }
                    java.nio.file.Files.createDirectories(p.destPath().getParent());
                    try {
                        java.nio.file.Files.createSymbolicLink(p.destPath(), p.sourcePath());
                    } catch (java.nio.file.FileAlreadyExistsException fae) {
                        // ERROR policy + race; or RENAME_EXISTING but the rename projection
                        // didn't run first (planner shape bug). Surface explicitly.
                        return EffectReceipt.failed(e, "destination exists: " + p.destPath());
                    } catch (UnsupportedOperationException | IOException sym) {
                        // FS refuses symlinks → recursive copy fallback.
                        dev.skillmanager.shared.util.Fs.copyRecursive(p.sourcePath(), p.destPath());
                    }
                }
                case COPY -> {
                    var skipped = applyConflictPolicy(p.destPath(), e.conflictPolicy());
                    if (skipped) {
                        return EffectReceipt.ok(e, new ContextFact.ProjectionSkippedConflict(
                                p.bindingId(), p.destPath().toString()));
                    }
                    java.nio.file.Files.createDirectories(p.destPath().getParent());
                    dev.skillmanager.shared.util.Fs.copyRecursive(p.sourcePath(), p.destPath());
                }
                case RENAMED_ORIGINAL_BACKUP -> {
                    // Move the existing file at backupOf to destPath (the
                    // backup location). The planner emits this BEFORE the
                    // SYMLINK / COPY for the same binding so the SYMLINK
                    // step sees an empty destination.
                    java.nio.file.Path original = java.nio.file.Path.of(p.backupOf());
                    if (!java.nio.file.Files.exists(original)) {
                        // Nothing to back up — the planner saw something at
                        // plan time but it's gone now. Skip the rename;
                        // primary projection will see an empty dest.
                        return EffectReceipt.skipped(e,
                                "no original to back up at " + original);
                    }
                    java.nio.file.Files.createDirectories(p.destPath().getParent());
                    java.nio.file.Files.move(original, p.destPath());
                }
                case MANAGED_COPY -> {
                    var skipped = applyConflictPolicy(p.destPath(), e.conflictPolicy());
                    if (skipped) {
                        return EffectReceipt.ok(e, new ContextFact.ProjectionSkippedConflict(
                                p.bindingId(), p.destPath().toString()));
                    }
                    java.nio.file.Files.createDirectories(p.destPath().getParent());
                    // Bytes-only copy — no recursive walk (managed-copy is
                    // one file). The binder set boundHash at plan time
                    // against the source bytes; we don't recompute here
                    // (the source hasn't moved between plan and apply).
                    java.nio.file.Files.copy(p.sourcePath(), p.destPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                case IMPORT_DIRECTIVE -> {
                    // For IMPORT_DIRECTIVE, sourcePath holds the relative
                    // @-import path (stored as a Path purely for record
                    // homogeneity); destPath is the markdown file to edit.
                    // Conflict policy doesn't apply — the section editor
                    // is idempotent and merges into existing content.
                    String line = "@" + p.sourcePath().toString();
                    java.nio.file.Path md = p.destPath();
                    java.nio.file.Files.createDirectories(md.getParent());
                    String current = java.nio.file.Files.exists(md)
                            ? java.nio.file.Files.readString(md)
                            : "";
                    String next = dev.skillmanager.bindings.ManagedImports.upsertLine(current, line);
                    java.nio.file.Files.writeString(md, next);
                }
            }
            return EffectReceipt.ok(e, new ContextFact.ProjectionMaterialized(
                    p.bindingId(), p.destPath().toString(), p.kind().name()));
        } catch (IOException io) {
            return EffectReceipt.failed(e, "could not materialize projection at "
                    + p.destPath() + ": " + io.getMessage());
        }
    }

    /**
     * Apply the {@link ConflictPolicy} to {@code destPath}. Returns
     * {@code true} if the projection should be skipped entirely
     * ({@link ConflictPolicy#SKIP} when {@code destPath} is occupied).
     */
    private boolean applyConflictPolicy(java.nio.file.Path destPath, ConflictPolicy policy) throws IOException {
        if (!java.nio.file.Files.exists(destPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        switch (policy) {
            case ERROR -> throw new IOException("destination already exists: " + destPath);
            case RENAME_EXISTING -> {
                // The planner should have emitted a RENAMED_ORIGINAL_BACKUP
                // before us, which already moved the dest aside. If we
                // still see something here, fall through to ERROR so the
                // ledger doesn't get out of sync.
                throw new IOException(
                        "destination still occupied after RENAME_EXISTING — "
                        + "planner did not emit a backup projection: " + destPath);
            }
            case SKIP -> { return true; }
            case OVERWRITE -> {
                if (java.nio.file.Files.isDirectory(destPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(destPath);
                } else {
                    java.nio.file.Files.delete(destPath);
                }
                return false;
            }
        }
        return false;
    }

    private EffectReceipt unmaterializeProjection(SkillEffect.UnmaterializeProjection e) {
        Projection p = e.projection();
        try {
            reverseProjection(p);
            return EffectReceipt.ok(e, new ContextFact.ProjectionUnmaterialized(
                    p.bindingId(), p.destPath().toString(), p.kind().name()));
        } catch (IOException io) {
            // Failures fail forward — log and move on so a partial rollback
            // doesn't block the rest of the teardown.
            Log.warn("unmaterialize %s at %s: %s", p.kind(), p.destPath(), io.getMessage());
            return EffectReceipt.ok(e, new ContextFact.ProjectionUnmaterialized(
                    p.bindingId(), p.destPath().toString(), p.kind().name()));
        }
    }

    /**
     * Default-agent binding id is deterministic — {@code default:<agentId>:<unitName>}.
     * Re-running install / sync against the same (agent, unit) replaces
     * the existing record instead of accumulating duplicates.
     */
    public static String defaultBindingId(String agentId, String unitName) {
        return "default:" + agentId + ":" + unitName;
    }

    /**
     * Build a {@link BindingSource#DEFAULT_AGENT} Binding from a
     * projector's planned entries and persist it into the per-unit
     * ledger. Called from the SyncAgents handler after the
     * projector's {@code apply} has succeeded; one binding per
     * (agent, unit) pair, each carrying SYMLINK projections for
     * every entry the projector planned.
     */
    private void recordDefaultAgentBinding(EffectContext ctx,
                                           dev.skillmanager.project.Projector proj,
                                           AgentUnit unit,
                                           List<dev.skillmanager.project.Projection> entries) {
        if (entries.isEmpty()) return;
        String bindingId = defaultBindingId(proj.agentId(), unit.name());
        // Pick a reasonable targetRoot: the projector's containing dir
        // for this unit's kind. Skills land under skillsDir(); plugins
        // under pluginsDir(). Same projector strategy used at materialize.
        java.nio.file.Path targetRoot = switch (unit.kind()) {
            case SKILL -> proj.skillsDir();
            case PLUGIN -> proj.pluginsDir();
            case DOC, HARNESS -> throw new IllegalStateException(
                    "doc-repos and harness templates never reach SyncAgents — "
                            + "they don't project into agent dirs");
        };
        List<Projection> ledgerProjections = new ArrayList<>(entries.size());
        for (dev.skillmanager.project.Projection e : entries) {
            ledgerProjections.add(new Projection(
                    bindingId, e.source(), e.target(), ProjectionKind.SYMLINK, null));
        }
        Binding b = new Binding(
                bindingId,
                unit.name(),
                unit.kind(),
                null,                                       // subElement: whole-unit binding
                targetRoot,
                ConflictPolicy.ERROR,
                BindingStore.nowIso(),
                dev.skillmanager.bindings.BindingSource.DEFAULT_AGENT,
                ledgerProjections);
        try {
            BindingStore bs = ctx.bindingStore();
            ProjectionLedger cur = bs.read(unit.name());
            bs.write(cur.withBinding(b));
        } catch (IOException io) {
            // Don't fail SyncAgents over a ledger write — the projection
            // already succeeded. Surface as a warning so the reconciler
            // can backfill later.
            Log.warn("could not record default-agent binding for %s on %s: %s",
                    unit.name(), proj.agentId(), io.getMessage());
        }
    }

    private EffectReceipt syncDocRepo(SkillEffect.SyncDocRepo e, EffectContext ctx) {
        // DocSync.run is the pure logic — walks the ledger, applies the
        // four-state matrix, reapplies IMPORT_DIRECTIVE rows. The
        // handler maps each Action into a typed ContextFact so the
        // renderer treats doc-repo sync uniformly with the rest of the
        // post-update tail.
        dev.skillmanager.bindings.DocSync.Outcome out =
                dev.skillmanager.bindings.DocSync.run(ctx.store(), e.unitName(), e.force());
        List<ContextFact> facts = new ArrayList<>(out.actions().size());
        for (var a : out.actions()) {
            ContextFact.DocBindingSynced.Severity sev = switch (a.severity()) {
                case INFO -> ContextFact.DocBindingSynced.Severity.INFO;
                case WARN -> ContextFact.DocBindingSynced.Severity.WARN;
                case ERROR -> ContextFact.DocBindingSynced.Severity.ERROR;
            };
            facts.add(new ContextFact.DocBindingSynced(
                    a.unitName(), a.bindingId(), a.subElement(),
                    a.description(), sev));
        }
        if (out.errors() > 0) {
            return EffectReceipt.partial(e, facts,
                    out.errors() + " binding(s) errored, " + out.warnings() + " warning(s)");
        }
        return EffectReceipt.ok(e, facts);
    }

    private EffectReceipt syncHarness(SkillEffect.SyncHarness e, EffectContext ctx) {
        // Reconcile a harness instance: re-run the planner, replace
        // ledger rows in place (stable ids — withBinding overwrites),
        // re-materialize each projection (OVERWRITE policy on the
        // planned bindings), and tear down any ledger rows that the
        // new plan no longer contains.
        java.util.List<ContextFact> facts = new java.util.ArrayList<>();
        try {
            dev.skillmanager.model.HarnessUnit harness;
            try {
                harness = dev.skillmanager.model.HarnessParser.load(
                        ctx.store().unitDir(e.harnessName(),
                                dev.skillmanager.model.UnitKind.HARNESS));
            } catch (IOException io) {
                return EffectReceipt.failed(e,
                        "could not load harness template " + e.harnessName() + ": " + io.getMessage());
            }
            java.nio.file.Path sandboxRoot = ctx.store().harnessesDir()
                    .resolve(dev.skillmanager.commands.HarnessCommand.INSTANCES_DIR);
            // Read the resolved paths from the per-instance lock file
            // the instantiator wrote. Falls back to the sandbox-subdir
            // defaults when missing (covers instances that predate the
            // lock file or were created with the old API).
            var lock = dev.skillmanager.bindings.HarnessInstanceLock.read(sandboxRoot, e.instanceId());
            java.nio.file.Path instanceDir = sandboxRoot.resolve(e.instanceId());
            java.nio.file.Path claudeConfigDir = lock.map(
                    dev.skillmanager.bindings.HarnessInstanceLock::claudeConfigDir)
                    .orElse(instanceDir.resolve("claude"));
            java.nio.file.Path codexHome = lock.map(
                    dev.skillmanager.bindings.HarnessInstanceLock::codexHome)
                    .orElse(instanceDir.resolve("codex"));
            java.nio.file.Path projectDir = lock.map(
                    dev.skillmanager.bindings.HarnessInstanceLock::projectDir)
                    .orElse(instanceDir);
            dev.skillmanager.bindings.HarnessInstantiator.Plan plan =
                    dev.skillmanager.bindings.HarnessInstantiator.plan(
                            harness, e.instanceId(),
                            claudeConfigDir, codexHome, projectDir,
                            ctx.store());

            java.util.Set<String> plannedIds = new java.util.LinkedHashSet<>();
            for (var b : plan.bindings()) plannedIds.add(b.bindingId());

            // 1) Apply each planned binding (idempotent: same id replaces).
            BindingStore bs = ctx.bindingStore();
            for (var b : plan.bindings()) {
                ProjectionLedger cur = bs.read(b.unitName());
                boolean isNew = cur.findById(b.bindingId()).isEmpty();
                for (Projection p : b.projections()) {
                    try {
                        applyProjectionOverwrite(p);
                    } catch (IOException io) {
                        facts.add(new ContextFact.HarnessBindingSynced(
                                e.harnessName(), e.instanceId(), b.bindingId(),
                                b.unitName(), ContextFact.HarnessBindingSynced.Action.FAILED,
                                "materialize failed at " + p.destPath() + ": " + io.getMessage()));
                    }
                }
                bs.write(cur.withBinding(b));
                facts.add(new ContextFact.HarnessBindingSynced(
                        e.harnessName(), e.instanceId(), b.bindingId(), b.unitName(),
                        isNew ? ContextFact.HarnessBindingSynced.Action.APPLIED
                              : ContextFact.HarnessBindingSynced.Action.UPGRADED,
                        b.bindingId()));
            }

            // 2) Tear down orphan harness:<instanceId>:* bindings.
            String prefix = "harness:" + e.instanceId() + ":";
            for (var existing : bs.listAll()) {
                if (!existing.bindingId().startsWith(prefix)) continue;
                if (plannedIds.contains(existing.bindingId())) continue;
                java.util.List<Projection> projs = new java.util.ArrayList<>(existing.projections());
                java.util.Collections.reverse(projs);
                for (Projection p : projs) {
                    try { reverseProjection(p); } catch (IOException ignored) {}
                }
                ProjectionLedger cur = bs.read(existing.unitName());
                bs.write(cur.withoutBinding(existing.bindingId()));
                facts.add(new ContextFact.HarnessBindingSynced(
                        e.harnessName(), e.instanceId(), existing.bindingId(),
                        existing.unitName(), ContextFact.HarnessBindingSynced.Action.REMOVED,
                        "no longer referenced by template"));
            }
            return EffectReceipt.ok(e, facts);
        } catch (IOException io) {
            return EffectReceipt.failed(e, facts, "sync-harness failed: " + io.getMessage());
        }
    }

    /**
     * Materialize one projection with {@link
     * dev.skillmanager.bindings.ConflictPolicy#OVERWRITE} semantics —
     * used by SyncHarness to refresh existing instance bindings.
     * Pulled out of {@link #materializeProjection} so the
     * reconcile path doesn't have to construct fake effects.
     */
    private void applyProjectionOverwrite(Projection p) throws IOException {
        switch (p.kind()) {
            case SYMLINK -> {
                if (java.nio.file.Files.exists(p.destPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (java.nio.file.Files.isDirectory(p.destPath(),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && !java.nio.file.Files.isSymbolicLink(p.destPath())) {
                        dev.skillmanager.shared.util.Fs.deleteRecursive(p.destPath());
                    } else {
                        java.nio.file.Files.delete(p.destPath());
                    }
                }
                java.nio.file.Files.createDirectories(p.destPath().getParent());
                try {
                    java.nio.file.Files.createSymbolicLink(p.destPath(), p.sourcePath());
                } catch (UnsupportedOperationException | IOException sym) {
                    dev.skillmanager.shared.util.Fs.copyRecursive(p.sourcePath(), p.destPath());
                }
            }
            case COPY -> {
                if (java.nio.file.Files.exists(p.destPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(p.destPath());
                }
                java.nio.file.Files.createDirectories(p.destPath().getParent());
                dev.skillmanager.shared.util.Fs.copyRecursive(p.sourcePath(), p.destPath());
            }
            case MANAGED_COPY -> {
                java.nio.file.Files.createDirectories(p.destPath().getParent());
                java.nio.file.Files.copy(p.sourcePath(), p.destPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            case IMPORT_DIRECTIVE -> {
                String line = "@" + p.sourcePath().toString();
                java.nio.file.Path md = p.destPath();
                java.nio.file.Files.createDirectories(md.getParent());
                String current = java.nio.file.Files.exists(md)
                        ? java.nio.file.Files.readString(md)
                        : "";
                String next = dev.skillmanager.bindings.ManagedImports.upsertLine(current, line);
                java.nio.file.Files.writeString(md, next);
            }
            case RENAMED_ORIGINAL_BACKUP -> {
                // Harness rebinds don't produce RENAMED_ORIGINAL_BACKUP
                // projections (instances use OVERWRITE policy) — this
                // arm exists for switch exhaustiveness only.
                throw new IllegalStateException(
                        "RENAMED_ORIGINAL_BACKUP not expected in harness sync flow");
            }
        }
    }

    /**
     * After deleting a {@link ProjectionKind#MANAGED_COPY} file, walk
     * up the {@code docs/agents/} → {@code docs/} chain and delete
     * any segment we own that is now empty. Stops at the first
     * non-owned dir, the first non-empty dir, or the filesystem
     * root — never deletes arbitrary user dirs.
     *
     * <p>"Owned" is identified by the segment basename: {@code agents}
     * (the conventional subdir under {@link DocRepoBinder#DOCS_SUBDIR})
     * and {@code docs} (its parent). A project that put real content
     * in a sibling under {@code <root>/docs/} keeps it — we only
     * delete dirs that are empty.
     */
    private static void pruneOwnedEmptyDocsDirs(java.nio.file.Path dir) {
        try {
            if (dir == null) return;
            // First the docs/agents/ dir itself.
            if ("agents".equals(dir.getFileName().toString())
                    && java.nio.file.Files.isDirectory(dir)
                    && isEmptyDir(dir)) {
                java.nio.file.Files.delete(dir);
                java.nio.file.Path parent = dir.getParent();
                if (parent != null
                        && "docs".equals(parent.getFileName().toString())
                        && java.nio.file.Files.isDirectory(parent)
                        && isEmptyDir(parent)) {
                    java.nio.file.Files.delete(parent);
                }
            }
        } catch (IOException ignored) {
            // Best-effort prune — a failed cleanup is not a failure of
            // the unbind. The next operation in this directory will
            // either succeed against the empty stub or surface its own
            // error.
        }
    }

    private static boolean isEmptyDir(java.nio.file.Path dir) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            return s.findFirst().isEmpty();
        }
    }

    /**
     * Package-private so {@link Executor#applyCompensation} can drive
     * the same dispatch.
     */
    static void reverseProjection(Projection p) throws IOException {
        switch (p.kind()) {
            case SYMLINK, COPY -> {
                if (java.nio.file.Files.exists(p.destPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (java.nio.file.Files.isSymbolicLink(p.destPath())) {
                        java.nio.file.Files.delete(p.destPath());
                    } else if (java.nio.file.Files.isDirectory(p.destPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        dev.skillmanager.shared.util.Fs.deleteRecursive(p.destPath());
                    } else {
                        java.nio.file.Files.delete(p.destPath());
                    }
                }
            }
            case RENAMED_ORIGINAL_BACKUP -> {
                // Move the backup at destPath back to its original location (backupOf).
                java.nio.file.Path original = java.nio.file.Path.of(p.backupOf());
                if (!java.nio.file.Files.exists(p.destPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    // Backup already gone — nothing to restore.
                    return;
                }
                java.nio.file.Files.createDirectories(original.getParent());
                java.nio.file.Files.move(p.destPath(), original,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            case MANAGED_COPY -> {
                // Plain delete — the tracked-copy bytes are skill-manager-
                // owned at the moment of unmaterialize. Callers that want
                // to preserve local edits use `unbind --keep-content`
                // (a higher-level surface that drops the ledger row
                // without emitting this effect).
                if (java.nio.file.Files.exists(p.destPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    java.nio.file.Files.delete(p.destPath());
                }
                // Prune the docs/agents/ dir (and its docs/ parent) if
                // they're now empty — those dirs are owned by the
                // doc-repo binder (see DocRepoBinder.DOCS_SUBDIR), so
                // leaving an empty stub behind would litter the
                // project tree. We only prune the segments we own —
                // never walk past "docs" toward the project root.
                pruneOwnedEmptyDocsDirs(p.destPath().getParent());
            }
            case IMPORT_DIRECTIVE -> {
                String line = "@" + p.sourcePath().toString();
                java.nio.file.Path md = p.destPath();
                if (!java.nio.file.Files.exists(md)) return;
                String current = java.nio.file.Files.readString(md);
                String next = dev.skillmanager.bindings.ManagedImports.removeLine(current, line);
                if (next.isEmpty()) {
                    // Section was the only content; remove the file rather
                    // than leave an empty stub.
                    java.nio.file.Files.delete(md);
                } else {
                    java.nio.file.Files.writeString(md, next);
                }
            }
        }
    }
}
