package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the install {@link Program}. Every side effect is its own
 * effect — pre-flight (registry override, gateway up), pre-condition
 * checks ({@link SkillEffect.RejectIfAlreadyInstalled}), plan-build
 * ({@link SkillEffect.BuildInstallPlan}), commit, audit, provenance,
 * transitive resolution, plan expansion + execution
 * ({@link SkillEffect.RunInstallPlan}), agent sync, and orphan
 * unregister.
 *
 * <p>{@link SkillEffect.CleanupResolvedGraph} is wired into
 * {@link Program#alwaysAfter()} so the staged temp dirs always get
 * removed — even when {@link SkillEffect.BuildInstallPlan} or any
 * earlier effect halts the program.
 */
public final class InstallUseCase {

    private InstallUseCase() {}

    public record Report(
            int errorCount,
            List<String> committed,
            Map<McpWriter.ConfigChange, List<String>> agentConfigChanges,
            List<String> orphansUnregistered) {

        public static Report empty() { return new Report(0, List.of(), Map.of(), List.of()); }
    }

    /**
     * Helper still used by the {@link SkillEffect.BuildInstallPlan} handler
     * — owns the {@link Policy} / {@link CliLock} / {@link
     * PackageManagerRuntime} wiring.
     */
    public static InstallPlan buildPlan(SkillStore store, ResolvedGraph graph) throws IOException {
        Policy policy = Policy.load(store);
        CliLock lock = CliLock.load(store);
        PackageManagerRuntime pmRuntime = new PackageManagerRuntime(store);
        return new PlanBuilder(policy, lock, pmRuntime)
                .plan(graph, true, true, store.cliBinDir());
    }

    public static Program<Report> buildProgram(SkillStore store, GatewayConfig gw, String registryOverride,
                                               ResolvedGraph graph, boolean dryRun) throws IOException {
        return buildProgram(store, gw, registryOverride, graph, dryRun, !dryRun);
    }

    /**
     * Variant that lets the caller suppress the gateway preflight (used by
     * {@code onboard --skip-gateway} so a no-gateway install really stays
     * no-gateway — including the post-update tail's MCP register / agent
     * sync, which are also gated behind {@code withGateway}).
     */
    public static Program<Report> buildProgram(SkillStore store, GatewayConfig gw, String registryOverride,
                                               ResolvedGraph graph, boolean dryRun, boolean withGateway)
            throws IOException {
        List<SkillEffect> effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, registryOverride, withGateway && !dryRun));
        effects.add(new SkillEffect.SnapshotMcpDeps());

        // The "remove first" guard for the top-level skill — was inline in
        // InstallCommand.
        String top = graph.resolved().isEmpty() ? null : graph.resolved().get(0).name();
        if (top != null) effects.add(new SkillEffect.RejectIfAlreadyInstalled(top));

        // Plan-build at exec time so handlers see fresh state.
        effects.add(new SkillEffect.BuildInstallPlan(graph));

        if (!dryRun) {
            effects.add(new SkillEffect.CommitUnitsToStore(graph));
            effects.add(new SkillEffect.RecordAuditPlan("install"));
            effects.add(new SkillEffect.RecordSourceProvenance(graph));
            effects.add(new SkillEffect.PrintInstalledSummary(graph));
        }

        // Tail effects fan out over the unit list. SyncAgents keeps its
        // skill-only symlink path until ticket 11 — plugin-kind units pass
        // through and the handler skips them, but typing them as AgentUnit
        // avoids the deprecated graph.skills() down-cast.
        //
        // No ResolveTransitives here: Resolver.resolveAll already walks
        // references transitively at use-case-build time, so the program's
        // graph is the closed transitive set. The exec-time resolution
        // pass exists only for sync, where post-merge content can surface
        // new references that weren't visible at build time.
        List<dev.skillmanager.model.AgentUnit> tailUnits = graph.units();
        effects.add(new SkillEffect.RunInstallPlan(gw));
        effects.add(new SkillEffect.SyncAgents(tailUnits, gw));
        // Plugin marketplace + harness CLI lifecycle. The reinstall list
        // is the names of every plugin in this resolved graph — sync
        // walks the full installed set, install only walks what's new
        // (which is the same thing for a fresh install).
        if (!dryRun) effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames(tailUnits)));
        effects.add(new SkillEffect.UnregisterMcpOrphans(gw));

        // Lock flip — last main effect. If anything before fails, the lock
        // is never written. If this itself fails (rare — atomic move), no
        // post-update state has been corrupted because there are no main
        // effects after it. The Executor's RestoreUnitsLock compensation
        // captures the pre-image at preState time so a hypothetical
        // future trailing effect would still walk back cleanly.
        if (!dryRun) {
            effects.add(buildLockUpdate(store, graph));
        }

        Program<Report> p = new Program<>("install-" + UUID.randomUUID(), effects, InstallUseCase::decode);
        // Always cleanup staged temp dirs, even if the program halted.
        return p.withFinally(new SkillEffect.CleanupResolvedGraph(graph));
    }

    /**
     * Compute the post-install lock by upserting one {@link dev.skillmanager.lock.LockedUnit}
     * per resolved unit on top of the current lock. The resolved-sha
     * comes from the resolver's {@code Resolved} record; for non-git
     * sources it's null, which is fine — the lock just records what we
     * know about provenance.
     */
    private static SkillEffect.UpdateUnitsLock buildLockUpdate(SkillStore store, ResolvedGraph graph)
            throws IOException {
        java.nio.file.Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
        dev.skillmanager.lock.UnitsLock current = dev.skillmanager.lock.UnitsLockReader.read(lockPath);
        dev.skillmanager.lock.UnitsLock target = current;
        for (var r : graph.resolved()) {
            // Map the resolver's source-kind onto InstallSource. The mapping
            // is approximate — the resolver doesn't currently distinguish
            // git-via-registry vs git-direct on the Resolved record. The
            // installed-record (written by RecordSourceProvenance) carries
            // the authoritative InstallSource; the lock just snapshots it.
            dev.skillmanager.source.InstalledUnit.InstallSource src = mapSourceKind(r.sourceKind());
            target = target.withUnit(new dev.skillmanager.lock.LockedUnit(
                    r.name(),
                    r.unit().kind(),
                    r.version(),
                    src,
                    null,            // origin discovered post-commit by RecordSourceProvenance
                    null,            // ref same — captured by provenance
                    r.sha256()       // best available at resolve time
            ));
        }
        return new SkillEffect.UpdateUnitsLock(target, lockPath);
    }

    /**
     * Filter the resolved-graph unit list down to plugin names — what
     * {@link SkillEffect.RefreshHarnessPlugins} drives through the
     * harness CLIs. Skills aren't harness-CLI-managed and pass through
     * the existing per-agent symlink path in {@code SyncAgents}.
     */
    private static List<String> pluginNames(List<dev.skillmanager.model.AgentUnit> units) {
        List<String> out = new ArrayList<>();
        for (var u : units) {
            if (u.kind() == dev.skillmanager.model.UnitKind.PLUGIN) out.add(u.name());
        }
        return out;
    }

    private static dev.skillmanager.source.InstalledUnit.InstallSource mapSourceKind(
            ResolvedGraph.SourceKind k) {
        return switch (k) {
            case REGISTRY -> dev.skillmanager.source.InstalledUnit.InstallSource.REGISTRY;
            case GIT -> dev.skillmanager.source.InstalledUnit.InstallSource.GIT;
            case LOCAL -> dev.skillmanager.source.InstalledUnit.InstallSource.LOCAL_FILE;
        };
    }

    private static Report decode(List<EffectReceipt> receipts) {
        int errorCount = 0;
        List<String> committed = new ArrayList<>();
        Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
            // CommitUnitsToStore emits SkillCommitted as each unit copies
            // and CommitRolledBack on failure. A FAILED commit means every
            // SkillCommitted fact in that receipt was rolled back — don't
            // count them as committed (would otherwise return exit 0 from
            // a rolled-back install).
            boolean commitFailed = r.status() == EffectStatus.FAILED
                    && r.effect() instanceof SkillEffect.CommitUnitsToStore;
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.SkillCommitted c -> {
                        if (!commitFailed) committed.add(c.name());
                    }
                    case ContextFact.AgentMcpConfigChanged c -> agentChanges
                            .computeIfAbsent(c.change(), k -> new ArrayList<>())
                            .add(c.agentId() + " (" + c.configPath() + ")");
                    case ContextFact.OrphanUnregistered o -> orphans.add(o.serverId());
                    default -> {}
                }
            }
        }
        return new Report(errorCount, committed, agentChanges, orphans);
    }
}
