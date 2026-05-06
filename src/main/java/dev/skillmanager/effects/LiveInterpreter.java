package dev.skillmanager.effects;

import dev.skillmanager.agent.Agent;
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

    public LiveInterpreter(SkillStore store) { this(store, null); }

    public LiveInterpreter(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    @Override
    public <R> R run(Program<R> program) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        R result = runWithContext(program, ctx);
        renderer.onComplete();
        return result;
    }

    @Override
    public <R> R runStaged(StagedProgram<R> staged) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        List<EffectReceipt> all = new ArrayList<>();
        all.addAll(runEffects(staged.stage1(), ctx));
        Program<?> stage2 = staged.stage2().apply(ctx);
        all.addAll(runEffects(stage2, ctx));
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
            if (r.status() == EffectStatus.HALTED) halted = true;
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
            case SkillEffect.BuildInstallPlan e -> buildInstallPlan(e, ctx);
            case SkillEffect.RunInstallPlan e -> runInstallPlan(e, ctx);
            case SkillEffect.CleanupResolvedGraph e -> cleanupGraph(e);
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
            case SkillEffect.SyncGit e -> SyncGitHandler.run(e, ctx);
            case SkillEffect.RemoveUnitFromStore e -> removeFromStore(e, ctx);
            case SkillEffect.UnlinkAgentUnit e -> unlinkAgentUnit(e);
            case SkillEffect.UnlinkAgentMcpEntry e -> unlinkAgentMcpEntry(e);
            case SkillEffect.ScaffoldSkill e -> scaffoldSkill(e);
            case SkillEffect.InitializePolicy e -> initializePolicy(e, ctx);
            case SkillEffect.LoadOutstandingErrors e -> loadOutstandingErrors(e, ctx);
            case SkillEffect.AddUnitError e -> addError(e, ctx);
            case SkillEffect.ClearUnitError e -> clearError(e, ctx);
            case SkillEffect.ValidateAndClearError e -> validateAndClear(e, ctx);
            case SkillEffect.InstallTools e -> installTools(e);
            case SkillEffect.InstallCli e -> installCli(e);
            case SkillEffect.RegisterMcp e -> registerMcp(e, ctx);
            case SkillEffect.UpdateUnitsLock e -> updateUnitsLock(e);
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
        var graph = e.graph();
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
        SourceProvenanceRecorder.run(e.graph(), ctx);
        ctx.invalidate();
        return EffectReceipt.ok(e, new ContextFact.ProvenanceRecorded(e.graph().resolved().size()));
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
        List<ContextFact> facts = new ArrayList<>();
        int failed = 0;
        for (Agent agent : Agent.all()) {
            SkillSync syncer = new SkillSync(store);
            for (AgentUnit u : units) {
                try {
                    if (u instanceof dev.skillmanager.model.SkillUnit su) {
                        syncer.sync(agent, List.of(su.skill()), true);
                    } else {
                        // Plugin-kind: provisional direct symlink under
                        // agent.pluginsDir(). Ticket 11 (Projector) extracts
                        // both arms into Projector.apply.
                        symlinkPluginInto(agent, u.name(), ctx.store());
                    }
                    facts.add(new ContextFact.AgentSkillSynced(agent.id(), u.name()));
                    tryClearError(ctx, u.name(), InstalledUnit.ErrorKind.AGENT_SYNC_FAILED);
                } catch (Exception ex) {
                    facts.add(new ContextFact.AgentSkillSyncFailed(agent.id(), u.name(), ex.getMessage()));
                    tryAddError(ctx, u.name(), InstalledUnit.ErrorKind.AGENT_SYNC_FAILED,
                            agent.id() + ": " + ex.getMessage());
                    failed++;
                }
            }
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
     * Symlink {@code plugins/<name>} (in the store) into the agent's
     * {@code pluginsDir()/<name>}. Provisional — ticket 11 replaces this
     * with {@code Projector.apply}.
     */
    private static void symlinkPluginInto(Agent agent, String name, SkillStore store) throws IOException {
        Path target = agent.pluginsDir().resolve(name);
        dev.skillmanager.shared.util.Fs.ensureDir(agent.pluginsDir());
        if (java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || java.nio.file.Files.isSymbolicLink(target)) {
            dev.skillmanager.shared.util.Fs.deleteRecursive(target);
        }
        Path src = store.unitDir(name, dev.skillmanager.model.UnitKind.PLUGIN);
        try {
            java.nio.file.Files.createSymbolicLink(target, src);
        } catch (UnsupportedOperationException | IOException fallback) {
            dev.skillmanager.shared.util.Fs.copyRecursive(src, target);
        }
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
                 MCP_REGISTRATION_FAILED, REGISTRY_UNAVAILABLE -> {
                // No cheap "is it really fixed" probe — pinging the gateway
                // tells us nothing about whether THIS skill's MCPs are
                // registered, etc. Handlers clear these on actual success
                // (RegisterMcp / SyncAgents / SyncGit registry lookup).
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
        return EffectReceipt.halted(e,
                "unit '" + e.unitName() + "' is already installed at " + at
                        + " — remove it first (skill-manager remove " + e.unitName() + ")");
    }

    private EffectReceipt buildInstallPlan(SkillEffect.BuildInstallPlan e, EffectContext ctx) {
        try {
            InstallPlan plan = dev.skillmanager.app.InstallUseCase.buildPlan(ctx.store(), e.graph());
            dev.skillmanager.plan.PlanPrinter.print(plan);
            ctx.setPlan(plan);
            if (plan.blocked()) {
                return EffectReceipt.halted(e, "plan has blocked items — see policy at "
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

    private EffectReceipt cleanupGraph(SkillEffect.CleanupResolvedGraph e) {
        try {
            e.graph().cleanup();
            return EffectReceipt.ok(e);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt printInstalledSummary(SkillEffect.PrintInstalledSummary e, EffectContext ctx) {
        for (var r : e.graph().resolved()) {
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
                lock.recordInstall(e.dep().backend(), req.tool(), req.version(),
                        e.dep().spec(), sha, e.unitName());
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
        try {
            Agent agent = Agent.byId(e.agentId());
            // Kind-aware: SKILL units sit under agent.skillsDir(), PLUGIN
            // units under agent.pluginsDir(). Provisional direct-symlink
            // path; ticket 11 swaps both arms for Projector.remove.
            Path base = e.kind() == dev.skillmanager.model.UnitKind.PLUGIN
                    ? agent.pluginsDir()
                    : agent.skillsDir();
            Path link = base.resolve(e.unitName());
            if (!java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !java.nio.file.Files.isSymbolicLink(link)) {
                return EffectReceipt.skipped(e, "not present");
            }
            dev.skillmanager.shared.util.Fs.deleteRecursive(link);
            return EffectReceipt.ok(e, new ContextFact.AgentSkillUnlinked(e.agentId(), e.unitName()));
        } catch (Exception ex) {
            return EffectReceipt.partial(e, "unlink failed",
                    new ContextFact.AgentSkillUnlinkFailed(e.agentId(), e.unitName(), ex.getMessage()));
        }
    }

    // ---------------------------------------------------------- scaffolding

    private EffectReceipt scaffoldSkill(SkillEffect.ScaffoldSkill e) {
        try {
            Path dir = e.dir();
            dev.skillmanager.shared.util.Fs.ensureDir(dir);
            for (var entry : e.files().entrySet()) {
                java.nio.file.Files.writeString(dir.resolve(entry.getKey()), entry.getValue());
            }
            return EffectReceipt.ok(e, new ContextFact.SkillScaffolded(e.skillName(), dir.toString()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
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
}
