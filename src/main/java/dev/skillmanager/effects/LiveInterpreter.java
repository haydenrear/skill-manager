package dev.skillmanager.effects;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.InstallResult;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillReference;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.source.GitOps;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
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
        return runWithContext(program, new EffectContext(store, gateway));
    }

    /**
     * Run a (sub-)program against an existing {@link EffectContext} —
     * source-record cache stays warm, error writes from the parent
     * program are visible, and any error writes by the sub-program are
     * visible to subsequent effects in the parent. Use this when a handler
     * legitimately needs to run another program (e.g. transitive resolution
     * or a future compensation pass) instead of constructing a new
     * interpreter.
     */
    public <R> R runWithContext(Program<R> program, EffectContext ctx) {
        List<EffectReceipt> receipts = new ArrayList<>();
        boolean halted = false;
        for (SkillEffect effect : program.effects()) {
            if (halted) {
                receipts.add(EffectReceipt.skipped(effect, "halted"));
                continue;
            }
            EffectReceipt r;
            try {
                r = execute(effect, ctx);
            } catch (Exception ex) {
                Log.warn("effect %s failed: %s", effect.getClass().getSimpleName(), ex.getMessage());
                r = EffectReceipt.failed(effect, ex.getMessage());
            }
            receipts.add(r);
            if (r.status() == EffectStatus.HALTED) halted = true;
        }
        // alwaysAfter runs unconditionally — for cleanup that must happen
        // even when the main effect chain halted (e.g. CleanupResolvedGraph).
        for (SkillEffect effect : program.alwaysAfter()) {
            try {
                receipts.add(execute(effect, ctx));
            } catch (Exception ex) {
                Log.warn("cleanup effect %s failed: %s",
                        effect.getClass().getSimpleName(), ex.getMessage());
                receipts.add(EffectReceipt.failed(effect, ex.getMessage()));
            }
        }
        return program.decoder().decode(receipts);
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
            case SkillEffect.CommitSkillsToStore e -> commitSkills(e, ctx);
            case SkillEffect.RecordAuditPlan e -> recordAudit(e, ctx);
            case SkillEffect.RecordSourceProvenance e -> recordProvenance(e, ctx);
            case SkillEffect.OnboardSource e -> onboardSource(e, ctx);
            case SkillEffect.ResolveTransitives e -> resolveTransitives(e, ctx);
            case SkillEffect.EnsureTool e -> ensureTool(e);
            case SkillEffect.RunCliInstall e -> runCliInstall(e, ctx);
            case SkillEffect.RegisterMcpServer e -> registerMcpServer(e, ctx);
            case SkillEffect.UnregisterMcpOrphan e -> unregisterOrphan(e);
            case SkillEffect.UnregisterMcpOrphans e -> unregisterOrphans(e, ctx);
            case SkillEffect.SyncAgents e -> syncAgents(e, ctx);
            case SkillEffect.SyncGit e -> SyncGitHandler.run(e, ctx);
            case SkillEffect.RemoveSkillFromStore e -> removeFromStore(e, ctx);
            case SkillEffect.UnlinkAgentSkill e -> unlinkAgentSkill(e);
            case SkillEffect.UnlinkAgentMcpEntry e -> unlinkAgentMcpEntry(e);
            case SkillEffect.ScaffoldSkill e -> scaffoldSkill(e);
            case SkillEffect.InitializePolicy e -> initializePolicy(e, ctx);
            case SkillEffect.LoadOutstandingErrors e -> loadOutstandingErrors(e, ctx);
            case SkillEffect.AddSkillError e -> addError(e, ctx);
            case SkillEffect.ClearSkillError e -> clearError(e, ctx);
            case SkillEffect.ValidateAndClearError e -> validateAndClear(e, ctx);
            case SkillEffect.InstallTools e -> installTools(e);
            case SkillEffect.InstallCli e -> installCli(e);
            case SkillEffect.RegisterMcp e -> registerMcp(e, ctx);
        };
    }

    private EffectReceipt configureRegistry(SkillEffect.ConfigureRegistry e, EffectContext ctx) {
        if (e.url() == null || e.url().isBlank()) {
            return EffectReceipt.skipped(e, "no override");
        }
        try {
            dev.skillmanager.registry.RegistryConfig.resolve(ctx.store(), e.url());
            Log.ok("registry: %s", e.url());
            return EffectReceipt.ok(e, new ContextFact.RegistryConfigured(e.url()));
        } catch (Exception ex) {
            Log.error("invalid registry URL %s: %s", e.url(), ex.getMessage());
            return EffectReceipt.failed(e, "invalid URL: " + ex.getMessage());
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
            Log.warn("gateway at %s is unreachable and not local — not attempting to start", base);
            return EffectReceipt.partial(e, "remote gateway unreachable",
                    new ContextFact.GatewayUnreachable(host));
        }
        int port = base.getPort() > 0 ? base.getPort() : 51717;
        dev.skillmanager.mcp.GatewayRuntime rt = new dev.skillmanager.mcp.GatewayRuntime(store);
        try {
            if (!rt.isRunning()) {
                rt.ensureVenv();
                Log.step("starting virtual MCP gateway on %s:%d", host, port);
                rt.start(host, port);
            }
            java.time.Duration wait = e.timeout() == null
                    ? java.time.Duration.ofSeconds(20)
                    : e.timeout();
            if (rt.waitForHealthy(base.toString(), wait)) {
                Log.ok("gateway up at %s", base);
                GatewayConfig.persist(store, base.toString());
                return EffectReceipt.ok(e, new ContextFact.GatewayStarted(host, port));
            }
            Log.error("gateway did not become healthy within %ds; see %s", wait.toSeconds(), rt.logFile());
            return EffectReceipt.failed(e, "health check timed out");
        } catch (Exception ex) {
            Log.error("failed to start gateway: %s", ex.getMessage());
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt commitSkills(SkillEffect.CommitSkillsToStore e, EffectContext ctx) {
        var graph = e.graph();
        List<ContextFact> facts = new ArrayList<>();
        List<String> committed = new ArrayList<>();
        try {
            for (var r : graph.resolved()) {
                Path dst = ctx.store().skillDir(r.name());
                if (java.nio.file.Files.exists(dst)) {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(dst);
                }
                dev.skillmanager.shared.util.Fs.ensureDir(dst.getParent());
                Path skillRoot = r.skill().sourcePath();
                dev.skillmanager.shared.util.Fs.copyRecursive(skillRoot, dst);
                committed.add(r.name());
                facts.add(new ContextFact.SkillCommitted(r.name()));
                Log.ok("installed %s", r.name());
            }
        } catch (Exception ex) {
            Log.error("commit failed after %d skill(s): %s — rolling back", committed.size(), ex.getMessage());
            for (String name : committed) {
                try {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(ctx.store().skillDir(name));
                    facts.add(new ContextFact.CommitRolledBack(name));
                } catch (Exception cleanupErr) {
                    Log.warn("rollback: could not delete %s — %s", name, cleanupErr.getMessage());
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

    private EffectReceipt onboardSource(SkillEffect.OnboardSource e, EffectContext ctx) throws IOException {
        Skill skill = e.skill();
        if (ctx.source(skill.name()).isPresent()) {
            return EffectReceipt.skipped(e, "source record already present");
        }
        Path skillDir = ctx.store().skillDir(skill.name());
        SkillSource.Kind kind;
        String origin = null, hash = null, gitRef = null;
        if (GitOps.isGitRepo(skillDir)) {
            kind = SkillSource.Kind.GIT;
            origin = GitOps.originUrl(skillDir);
            hash = GitOps.headHash(skillDir);
            gitRef = GitOps.detectInstallRef(skillDir);
        } else {
            kind = SkillSource.Kind.LOCAL_DIR;
        }
        SkillSource source = new SkillSource(
                skill.name(), skill.version(), kind, SkillSource.InstallSource.UNKNOWN,
                origin, hash, gitRef, SkillSourceStore.nowIso(), null);
        if (kind == SkillSource.Kind.LOCAL_DIR) {
            source = source.withErrorAdded(new SkillSource.SkillError(
                    SkillSource.ErrorKind.NEEDS_GIT_MIGRATION,
                    "skill is not git-tracked — sync/upgrade unavailable until reinstalled from a git source",
                    SkillSourceStore.nowIso()));
        } else if (origin == null || origin.isBlank()) {
            source = source.withErrorAdded(new SkillSource.SkillError(
                    SkillSource.ErrorKind.NO_GIT_REMOTE,
                    "git-tracked but no origin remote configured",
                    SkillSourceStore.nowIso()));
        }
        ctx.writeSource(source);
        Log.info("onboarded %s (kind=%s)", skill.name(), kind);
        return EffectReceipt.ok(e, new ContextFact.SkillOnboarded(skill.name(), kind));
    }

    /**
     * Walk every installed skill's {@code skill_references}, gather the
     * unmet ones into a single {@link dev.skillmanager.resolve.ResolvedGraph}
     * via {@link dev.skillmanager.resolve.Resolver#resolveAll}, and run a
     * sub-{@link dev.skillmanager.app.InstallUseCase} program against the
     * shared {@link EffectContext} via {@link #runWithContext}.
     *
     * <p>Reads live skills from the store at exec time so a sync that
     * brought new {@code skill_references} via merge sees them — the
     * {@code e.skills()} list is informational (pre-merge snapshot).
     */
    private EffectReceipt resolveTransitives(SkillEffect.ResolveTransitives e, EffectContext ctx) {
        List<ContextFact> facts = new ArrayList<>();
        try {
            List<dev.skillmanager.resolve.Resolver.Coord> unmet = new ArrayList<>();
            java.util.Set<String> seenName = new java.util.LinkedHashSet<>();
            for (Skill s : ctx.store().listInstalled()) {
                for (SkillReference ref : s.skillReferences()) {
                    String coord = referenceToCoord(ref, ctx.store(), s.name());
                    String name = ref.name() != null ? ref.name() : guessName(coord);
                    if (name == null || name.isBlank() || ctx.store().contains(name)) continue;
                    if (!seenName.add(name)) continue;
                    Log.step("transitive: %s declares unmet skill_reference %s", s.name(), coord);
                    unmet.add(new dev.skillmanager.resolve.Resolver.Coord(coord, ref.version()));
                }
            }
            if (unmet.isEmpty()) return EffectReceipt.ok(e, facts);

            dev.skillmanager.resolve.Resolver resolver = new dev.skillmanager.resolve.Resolver(ctx.store());
            dev.skillmanager.resolve.ResolvedGraph graph = resolver.resolveAll(unmet);
            try {
                Program<dev.skillmanager.app.InstallUseCase.Report> sub =
                        dev.skillmanager.app.InstallUseCase.buildProgram(
                                ctx.gateway(), null, graph, false);
                // Save+restore ctx slots so the sub-program's BuildInstallPlan
                // and SnapshotMcpDeps don't clobber the parent program's
                // plan / pre-snapshot.
                EffectContext.Snapshot snap = ctx.snapshot();
                dev.skillmanager.app.InstallUseCase.Report report;
                try { report = runWithContext(sub, ctx); }
                finally { ctx.restore(snap); }
                for (String name : report.committed()) {
                    facts.add(new ContextFact.TransitiveInstalled(name));
                }
                int failed = report.errorCount();
                return failed == 0
                        ? EffectReceipt.ok(e, facts)
                        : EffectReceipt.partial(e, facts, failed + " transitive sub-effect(s) failed");
            } finally {
                graph.cleanup();
            }
        } catch (Exception ex) {
            Log.warn("resolveTransitives failed: %s", ex.getMessage());
            return EffectReceipt.failed(e, facts, ex.getMessage());
        }
    }

    private EffectReceipt installTools(SkillEffect.InstallTools e) throws IOException {
        InstallPlan plan = buildPlan(e.skills());
        ToolInstallRecorder.run(plan, store);
        return EffectReceipt.ok(e, new ContextFact.ToolsInstalledFor(e.skills().size()));
    }

    private EffectReceipt installCli(SkillEffect.InstallCli e) throws IOException {
        InstallPlan plan = buildPlan(e.skills());
        CliInstallRecorder.run(plan, store);
        return EffectReceipt.ok(e, new ContextFact.CliInstalledFor(e.skills().size()));
    }

    private InstallPlan buildPlan(List<Skill> skills) throws IOException {
        Policy policy = Policy.load(store);
        CliLock lock = CliLock.load(store);
        PackageManagerRuntime pmRuntime = new PackageManagerRuntime(store);
        return new PlanBuilder(policy, lock, pmRuntime)
                .plan(skills, true, true, store.cliBinDir());
    }

    private EffectReceipt registerMcp(SkillEffect.RegisterMcp e, EffectContext ctx) throws IOException {
        if (!new GatewayClient(e.gateway()).ping()) {
            for (Skill s : e.skills()) {
                if (s.mcpDependencies().isEmpty()) continue;
                ctx.addError(s.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE,
                        "gateway at " + e.gateway().baseUrl() + " unreachable");
            }
            return EffectReceipt.skipped(e, "gateway unreachable");
        }

        McpWriter writer = new McpWriter(e.gateway());
        List<InstallResult> results = writer.registerAll(e.skills());
        writer.printInstallResults(results);

        for (Skill s : e.skills()) {
            if (s.mcpDependencies().isEmpty()) continue;
            ctx.clearError(s.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE);
        }
        List<ContextFact> facts = new ArrayList<>();
        int erroredCount = 0;
        for (InstallResult r : results) {
            String owner = ownerOf(e.skills(), r.serverId());
            if (owner == null) continue;
            if (InstallResult.Status.ERROR.code.equals(r.status())) {
                ctx.addError(owner, SkillSource.ErrorKind.MCP_REGISTRATION_FAILED,
                        r.serverId() + ": " + r.message());
                facts.add(new ContextFact.McpRegistrationFailed(owner, r.serverId(), r.message()));
                erroredCount++;
            } else {
                ctx.clearError(owner, SkillSource.ErrorKind.MCP_REGISTRATION_FAILED);
                facts.add(new ContextFact.McpRegistered(owner, r.serverId()));
            }
        }
        return erroredCount == 0
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, erroredCount + " skill(s) had MCP errors");
    }

    private EffectReceipt unregisterOrphans(SkillEffect.UnregisterMcpOrphans e, EffectContext ctx) {
        var preMcpDeps = ctx.preMcpDeps();
        if (preMcpDeps.isEmpty()) return EffectReceipt.skipped(e, "no snapshot in context");
        try {
            List<String> orphans = dev.skillmanager.app.PostUpdateUseCase.computeOrphans(
                    preMcpDeps, ctx.store().listInstalled());
            if (orphans.isEmpty()) return EffectReceipt.ok(e);
            List<SkillEffect> sub = new ArrayList<>();
            for (String id : orphans) sub.add(new SkillEffect.UnregisterMcpOrphan(id, e.gateway()));
            Program<List<ContextFact>> subProgram = new Program<>(
                    "orphans-" + java.util.UUID.randomUUID(),
                    sub,
                    receipts -> {
                        List<ContextFact> all = new ArrayList<>();
                        for (EffectReceipt r : receipts) all.addAll(r.facts());
                        return all;
                    });
            EffectContext.Snapshot snap = ctx.snapshot();
            List<ContextFact> all;
            try { all = runWithContext(subProgram, ctx); }
            finally { ctx.restore(snap); }
            return EffectReceipt.ok(e, all);
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt unregisterOrphan(SkillEffect.UnregisterMcpOrphan e) {
        GatewayClient client = new GatewayClient(e.gateway());
        if (!client.ping()) return EffectReceipt.skipped(e, "gateway unreachable");
        try {
            if (client.unregister(e.serverId())) {
                Log.ok("gateway: unregistered orphan %s", e.serverId());
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
     * {@link SkillSource.ErrorKind#AGENT_SYNC_FAILED} on the skill so the
     * outstanding-errors banner surfaces it; other (agent, skill) pairs
     * keep going.
     */
    private EffectReceipt syncAgents(SkillEffect.SyncAgents e, EffectContext ctx) {
        McpWriter writer = new McpWriter(e.gateway());
        List<ContextFact> facts = new ArrayList<>();
        int failed = 0;
        for (Agent agent : Agent.all()) {
            SkillSync syncer = new SkillSync(store);
            for (Skill s : e.skills()) {
                try {
                    syncer.sync(agent, List.of(s), true);
                    facts.add(new ContextFact.AgentSkillSynced(agent.id(), s.name()));
                    tryClearError(ctx, s.name(), SkillSource.ErrorKind.AGENT_SYNC_FAILED);
                } catch (Exception ex) {
                    Log.warn("%s: skill sync failed for %s — %s", agent.id(), s.name(), ex.getMessage());
                    facts.add(new ContextFact.AgentSkillSyncFailed(agent.id(), s.name(), ex.getMessage()));
                    tryAddError(ctx, s.name(), SkillSource.ErrorKind.AGENT_SYNC_FAILED,
                            agent.id() + ": " + ex.getMessage());
                    failed++;
                }
            }
            try {
                McpWriter.ConfigChange change = writer.writeAgentEntry(agent);
                facts.add(new ContextFact.AgentMcpConfigChanged(
                        agent.id(), change, agent.mcpConfigPath().toString()));
            } catch (Exception ex) {
                Log.warn("%s: mcp config update failed — %s", agent.id(), ex.getMessage());
                facts.add(new ContextFact.AgentMcpConfigFailed(agent.id(), ex.getMessage()));
                failed++;
            }
        }
        return failed == 0
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, failed + " agent step(s) failed");
    }

    private static void tryAddError(EffectContext ctx, String skillName,
                                    SkillSource.ErrorKind kind, String message) {
        try { ctx.addError(skillName, kind, message); }
        catch (IOException io) { Log.warn("could not record %s for %s: %s", kind, skillName, io.getMessage()); }
    }

    private static void tryClearError(EffectContext ctx, String skillName, SkillSource.ErrorKind kind) {
        try { ctx.clearError(skillName, kind); }
        catch (IOException io) { Log.warn("could not clear %s on %s: %s", kind, skillName, io.getMessage()); }
    }

    private EffectReceipt addError(SkillEffect.AddSkillError e, EffectContext ctx) throws IOException {
        ctx.addError(e.skillName(), e.kind(), e.message());
        return EffectReceipt.ok(e, new ContextFact.ErrorAdded(e.skillName(), e.kind()));
    }

    private EffectReceipt clearError(SkillEffect.ClearSkillError e, EffectContext ctx) throws IOException {
        ctx.clearError(e.skillName(), e.kind());
        return EffectReceipt.ok(e, new ContextFact.ErrorCleared(e.skillName(), e.kind()));
    }

    private EffectReceipt validateAndClear(SkillEffect.ValidateAndClearError e, EffectContext ctx) throws IOException {
        Path dir = ctx.store().skillDir(e.skillName());
        boolean cleared = false;
        switch (e.kind()) {
            case MERGE_CONFLICT -> {
                if (GitOps.isGitRepo(dir) && GitOps.unmergedFiles(dir).isEmpty()) {
                    ctx.clearError(e.skillName(), SkillSource.ErrorKind.MERGE_CONFLICT);
                    cleared = true;
                }
            }
            case GATEWAY_UNAVAILABLE -> {
                if (ctx.gateway() != null && new GatewayClient(ctx.gateway()).ping()) {
                    ctx.clearError(e.skillName(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE);
                    cleared = true;
                }
            }
            case NO_GIT_REMOTE -> {
                if (GitOps.isGitRepo(dir) && GitOps.originUrl(dir) != null) {
                    ctx.clearError(e.skillName(), SkillSource.ErrorKind.NO_GIT_REMOTE);
                    cleared = true;
                }
            }
            case NEEDS_GIT_MIGRATION -> {
                if (GitOps.isGitRepo(dir)) {
                    ctx.clearError(e.skillName(), SkillSource.ErrorKind.NEEDS_GIT_MIGRATION);
                    cleared = true;
                }
            }
            case AGENT_SYNC_FAILED, MCP_REGISTRATION_FAILED, REGISTRY_UNAVAILABLE -> {
                // No cheap probe — handlers clear these directly on success
                // (RegisterMcp / SyncAgents / SyncGit registry lookup).
            }
        }
        if (cleared) Log.ok("reconcile: %s cleared %s", e.skillName(), e.kind());
        return EffectReceipt.ok(e, new ContextFact.ErrorValidated(e.skillName(), e.kind(), cleared));
    }

    private static String ownerOf(List<Skill> skills, String mcpServerId) {
        for (Skill s : skills) {
            for (McpDependency d : s.mcpDependencies()) {
                if (d.name().equals(mcpServerId)) return s.name();
            }
        }
        return null;
    }

    private static String referenceToCoord(SkillReference ref, SkillStore store, String parentSkillName) {
        if (ref.isLocal()) {
            Path rel = Path.of(ref.path());
            if (rel.isAbsolute()) return rel.toString();
            return store.skillDir(parentSkillName).resolve(rel).normalize().toString();
        }
        return ref.version() != null && !ref.version().isBlank()
                ? ref.name() + "@" + ref.version()
                : ref.name();
    }

    private static String guessName(String coord) {
        if (coord == null) return null;
        String s = coord;
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        if (s.startsWith("file:")) s = s.substring("file:".length());
        if (s.startsWith("github:")) {
            int slash = s.lastIndexOf('/');
            return slash >= 0 ? s.substring(slash + 1) : null;
        }
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        int slash = s.lastIndexOf('/');
        String tail = slash >= 0 ? s.substring(slash + 1) : s;
        return tail.isBlank() ? null : tail;
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
        if (e.skillName() == null || e.skillName().isBlank()) return EffectReceipt.skipped(e, "no name");
        if (ctx.store().contains(e.skillName())) {
            Log.error("skill '%s' is already installed at %s — remove it first (skill-manager remove %s)",
                    e.skillName(), ctx.store().skillDir(e.skillName()), e.skillName());
            return EffectReceipt.halted(e, "already installed: " + e.skillName());
        }
        return EffectReceipt.ok(e);
    }

    private EffectReceipt buildInstallPlan(SkillEffect.BuildInstallPlan e, EffectContext ctx) {
        try {
            InstallPlan plan = dev.skillmanager.app.InstallUseCase.buildPlan(ctx.store(), e.graph());
            dev.skillmanager.plan.PlanPrinter.print(plan);
            ctx.setPlan(plan);
            if (plan.blocked()) {
                Log.error("plan has blocked items — see policy at %s",
                        ctx.store().root().resolve("policy.toml"));
                return EffectReceipt.halted(e, "plan has blocked items");
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
        // error-state / plan slot stay shared. The sub-program's facts roll
        // up into the parent receipt — decoders see them as if the plan
        // actions had been emitted directly.
        Program<SubResult> subProgram = new Program<>(
                "plan-expand-" + java.util.UUID.randomUUID(),
                sub,
                receipts -> {
                    List<ContextFact> all = new ArrayList<>();
                    int failed = 0;
                    for (EffectReceipt r : receipts) {
                        all.addAll(r.facts());
                        if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) failed++;
                    }
                    return new SubResult(all, failed);
                });
        EffectContext.Snapshot snap = ctx.snapshot();
        SubResult sr;
        try { sr = runWithContext(subProgram, ctx); }
        finally { ctx.restore(snap); }
        return sr.failed == 0
                ? EffectReceipt.ok(e, sr.facts)
                : EffectReceipt.partial(e, sr.facts, sr.failed + " plan-action effect(s) failed");
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

    private record SubResult(List<ContextFact> facts, int failed) {}

    // -------------------------------------------------- gateway lifecycle

    private EffectReceipt stopGateway(SkillEffect.StopGateway e) {
        if (e.gateway() == null) return EffectReceipt.skipped(e, "no gateway configured");
        dev.skillmanager.mcp.GatewayRuntime rt = new dev.skillmanager.mcp.GatewayRuntime(store);
        try {
            if (!rt.isRunning()) return EffectReceipt.skipped(e, "not running");
            rt.stop(java.time.Duration.ofSeconds(10));
            Log.ok("gateway stopped");
            return EffectReceipt.ok(e, new ContextFact.GatewayStopped());
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt configureGateway(SkillEffect.ConfigureGateway e, EffectContext ctx) {
        if (e.url() == null || e.url().isBlank()) return EffectReceipt.skipped(e, "no URL");
        try {
            GatewayConfig.persist(ctx.store(), e.url());
            Log.ok("gateway URL persisted: %s", e.url());
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
                    String installed = rt.ensureBundled(tool.id());
                    boolean wasMissing = installed != null;
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
            Log.ok("installed package manager %s@%s at %s", e.pm().id, version, installed);
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
        try {
            // Build a CLI-only plan for the owning skill and feed it through
            // the existing recorder — keeps the lock-file write logic in one
            // place. PlanBuilder.plan(skills, withCli, withMcp, cliBinDir):
            // withCli=true so RunCliInstall actions land in the plan.
            Policy policy = Policy.load(store);
            CliLock lock = CliLock.load(store);
            PackageManagerRuntime pmRuntime = new PackageManagerRuntime(store);
            List<Skill> single = List.of(skillFromName(e.skillName()));
            InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                    .plan(single, true, false, store.cliBinDir());
            CliInstallRecorder.run(plan, store);
            return EffectReceipt.ok(e,
                    new ContextFact.CliInstalled(e.skillName(), e.dep().name(), e.dep().backend()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e,
                    List.of(new ContextFact.CliInstallFailed(e.skillName(), e.dep().name(), ex.getMessage())),
                    ex.getMessage());
        }
    }

    private Skill skillFromName(String name) throws IOException {
        return store.load(name).orElseThrow(() -> new IOException("skill not in store: " + name));
    }

    private EffectReceipt registerMcpServer(SkillEffect.RegisterMcpServer e, EffectContext ctx) {
        if (!new GatewayClient(e.gateway()).ping()) {
            try {
                ctx.addError(e.skillName(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE,
                        "gateway at " + e.gateway().baseUrl() + " unreachable");
            } catch (IOException io) {
                Log.warn("could not record gateway-unavailable for %s: %s", e.skillName(), io.getMessage());
            }
            return EffectReceipt.skipped(e, "gateway unreachable");
        }
        try {
            McpWriter writer = new McpWriter(e.gateway());
            Skill carrier = skillFromName(e.skillName());
            // Filter to just this dep — registerAll loops, so we synthesize a
            // single-dep skill and let it run.
            Skill solo = withSingleMcpDep(carrier, e.dep());
            List<InstallResult> results = writer.registerAll(List.of(solo));
            writer.printInstallResults(results);
            for (InstallResult r : results) {
                if (InstallResult.Status.ERROR.code.equals(r.status())) {
                    ctx.addError(e.skillName(), SkillSource.ErrorKind.MCP_REGISTRATION_FAILED,
                            r.serverId() + ": " + r.message());
                    return EffectReceipt.partial(e, "register failed",
                            new ContextFact.McpServerRegistrationFailed(e.skillName(), r.serverId(), r.message()));
                }
            }
            ctx.clearError(e.skillName(), SkillSource.ErrorKind.MCP_REGISTRATION_FAILED);
            return EffectReceipt.ok(e,
                    new ContextFact.McpServerRegistered(e.skillName(), e.dep().name()));
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private static Skill withSingleMcpDep(Skill original, McpDependency only) {
        return new Skill(original.name(), original.description(), original.version(),
                original.cliDependencies(), original.skillReferences(), List.of(only),
                original.rawFrontmatter(), original.body(), original.sourcePath());
    }

    // --------------------------------------------------- store / agent removal

    private EffectReceipt removeFromStore(SkillEffect.RemoveSkillFromStore e, EffectContext ctx) {
        try {
            Path dir = ctx.store().skillDir(e.skillName());
            if (!java.nio.file.Files.exists(dir)) return EffectReceipt.skipped(e, "not in store");
            dev.skillmanager.shared.util.Fs.deleteRecursive(dir);
            try { ctx.sourceStore().delete(e.skillName()); } catch (Exception ignored) {}
            ctx.invalidate();
            Log.ok("removed %s from store", e.skillName());
            return EffectReceipt.ok(e, new ContextFact.SkillRemovedFromStore(e.skillName()));
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

    private EffectReceipt unlinkAgentSkill(SkillEffect.UnlinkAgentSkill e) {
        try {
            Agent agent = Agent.byId(e.agentId());
            Path link = agent.skillsDir().resolve(e.skillName());
            if (!java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !java.nio.file.Files.isSymbolicLink(link)) {
                return EffectReceipt.skipped(e, "not present");
            }
            dev.skillmanager.shared.util.Fs.deleteRecursive(link);
            return EffectReceipt.ok(e, new ContextFact.AgentSkillUnlinked(e.agentId(), e.skillName()));
        } catch (Exception ex) {
            return EffectReceipt.partial(e, "unlink failed",
                    new ContextFact.AgentSkillUnlinkFailed(e.agentId(), e.skillName(), ex.getMessage()));
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
                    for (SkillSource.SkillError err : src.errors()) {
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
