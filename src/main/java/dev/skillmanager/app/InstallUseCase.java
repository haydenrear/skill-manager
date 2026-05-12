package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.effects.StagedProgram;
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
 * Builds the install {@link StagedProgram}. The resolve, the pre-flight,
 * the plan-build, the commit, the post-update tail, the orphan
 * unregister, and the lock flip are ALL effects — the use case just
 * sequences them.
 *
 * <p>Two stages because the post-commit tail (SyncAgents,
 * RefreshHarnessPlugins, UpdateUnitsLock) needs the resolved unit list,
 * which doesn't exist until {@link SkillEffect.BuildResolveGraphFromSource}
 * has run. Stage 1 ends with {@link SkillEffect.RunInstallPlan}; stage 2's
 * builder reads {@link EffectContext#resolvedGraph()} and emits the
 * tail effects with the now-known unit list.
 *
 * <p>Cleanup ({@link SkillEffect.CleanupResolvedGraph}) lives in stage 1's
 * {@code alwaysAfter} so the staged temp dirs always get removed — even
 * when the resolve effect halts (e.g. typed exit code via
 * {@link ContextFact.HaltWithExitCode}) before the commit.
 */
public final class InstallUseCase {

    private InstallUseCase() {}

    public record Report(
            int errorCount,
            int exitCode,
            List<String> committed,
            Map<McpWriter.ConfigChange, List<String>> agentConfigChanges,
            List<String> orphansUnregistered) {

        public static Report empty() { return new Report(0, 0, List.of(), Map.of(), List.of()); }
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

    public static StagedProgram<Report> buildProgram(SkillStore store, GatewayConfig gw,
                                                     String registryOverride,
                                                     String source, String version,
                                                     boolean yes, boolean dryRun) {
        return buildProgram(store, gw, registryOverride, source, version, yes, dryRun, !dryRun, true);
    }

    /**
     * Variant that lets the caller suppress the gateway preflight (used
     * by {@code onboard --skip-gateway} when it routes through this use
     * case so a no-gateway install really stays no-gateway — including
     * the post-update tail's MCP register / agent sync, which are also
     * gated behind {@code withGateway}).
     */
    public static StagedProgram<Report> buildProgram(SkillStore store, GatewayConfig gw,
                                                     String registryOverride,
                                                     String source, String version,
                                                     boolean yes, boolean dryRun,
                                                     boolean withGateway) {
        return buildProgram(store, gw, registryOverride, source, version, yes, dryRun, withGateway, true);
    }

    /**
     * Full variant with {@code bindDefault}. When {@code false}, the
     * install is store-only — the bytes land in the store and a lock
     * entry is written, but no agent projection or default-agent
     * binding is created. Used by harness instantiation and profile
     * sync, which create their own bindings instead of inheriting the
     * implicit one.
     */
    public static StagedProgram<Report> buildProgram(SkillStore store, GatewayConfig gw,
                                                     String registryOverride,
                                                     String source, String version,
                                                     boolean yes, boolean dryRun,
                                                     boolean withGateway,
                                                     boolean bindDefault) {
        String operationId = "install-" + UUID.randomUUID();

        // --- Stage 1: preflight + resolve + plan + policy gate + commit + run ---
        List<SkillEffect> stage1Effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, registryOverride, withGateway && !dryRun));

        // The resolver call now lives inside the program. Halts the
        // program (with a HaltWithExitCode fact) on any failure —
        // preserves InstallCommand's old fail-fast + typed-exit-code
        // shape.
        stage1Effects.add(new SkillEffect.BuildResolveGraphFromSource(source, version));

        stage1Effects.add(new SkillEffect.SnapshotMcpDeps());

        // The "remove first" guard — top-level name comes from ctx after
        // resolve. Halts the program if the user-typed source resolves
        // to a unit already in the store.
        stage1Effects.add(new SkillEffect.RejectIfTopLevelInstalled());

        // Plan-build at exec time so handlers see fresh state.
        stage1Effects.add(new SkillEffect.BuildInstallPlan());

        // Policy gate runs INSIDE the program now — replaces
        // InstallCommand.checkPolicyGate. Halts with HaltWithExitCode
        // 5 (--yes blocked / no TTY) or 6 (user said no) on a gated
        // category.
        if (!dryRun) {
            stage1Effects.add(new SkillEffect.CheckInstallPolicyGate(yes));
        }

        if (!dryRun) {
            stage1Effects.add(new SkillEffect.CommitUnitsToStore());
            stage1Effects.add(new SkillEffect.RecordAuditPlan("install"));
            stage1Effects.add(new SkillEffect.RecordSourceProvenance());
            stage1Effects.add(new SkillEffect.PrintInstalledSummary());
        }

        stage1Effects.add(new SkillEffect.RunInstallPlan(gw));

        Program<?> stage1 = new Program<>(operationId + "-stage1", stage1Effects, receipts -> null)
                .withFinally(new SkillEffect.CleanupResolvedGraph());

        // --- Stage 2: tail effects built from ctx.resolvedGraph() ---
        java.util.function.Function<EffectContext, Program<?>> stage2Builder = ctx -> {
            ResolvedGraph graph = ctx.resolvedGraph().orElse(new ResolvedGraph());
            List<dev.skillmanager.model.AgentUnit> tailUnits = graph.units();
            List<SkillEffect> stage2Effects = new ArrayList<>();
            // --bind-default (the install-time default) projects every
            // unit into the configured agent's dirs and records a
            // DEFAULT_AGENT Binding in the ledger. --no-bind-default
            // skips both, leaving the install store-only.
            if (bindDefault) {
                stage2Effects.add(new SkillEffect.SyncAgents(tailUnits, gw));
                if (!dryRun) {
                    stage2Effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames(tailUnits)));
                }
            }
            stage2Effects.add(new SkillEffect.UnregisterMcpOrphans(gw));
            if (!dryRun) {
                stage2Effects.add(buildLockUpdate(store, graph));
            }
            return new Program<>(operationId + "-stage2", stage2Effects, receipts -> null);
        };

        return new StagedProgram<>(operationId, stage1, stage2Builder, InstallUseCase::decode);
    }

    /**
     * Compute the post-install lock by upserting one {@link dev.skillmanager.lock.LockedUnit}
     * per resolved unit on top of the current lock. The resolved-sha
     * comes from the resolver's {@code Resolved} record; for non-git
     * sources it's null, which is fine — the lock just records what we
     * know about provenance.
     */
    private static SkillEffect.UpdateUnitsLock buildLockUpdate(SkillStore store, ResolvedGraph graph) {
        java.nio.file.Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
        try {
            dev.skillmanager.lock.UnitsLock current = dev.skillmanager.lock.UnitsLockReader.read(lockPath);
            dev.skillmanager.lock.UnitsLock target = current;
            for (var r : graph.resolved()) {
                dev.skillmanager.source.InstalledUnit.InstallSource src = mapSourceKind(r.sourceKind());
                target = target.withUnit(new dev.skillmanager.lock.LockedUnit(
                        r.name(),
                        r.unit().kind(),
                        r.version(),
                        src,
                        null,
                        null,
                        r.sha256()
                ));
            }
            return new SkillEffect.UpdateUnitsLock(target, lockPath);
        } catch (IOException io) {
            // No graceful no-op effect — emit a "no diff" update so the
            // program shape stays consistent. Worst case: lock isn't
            // refreshed; next install/sync retries.
            return new SkillEffect.UpdateUnitsLock(
                    dev.skillmanager.lock.UnitsLock.empty(), lockPath);
        }
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
        int exitCode = 0;
        List<String> committed = new ArrayList<>();
        Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
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
                    case ContextFact.HaltWithExitCode h -> {
                        // The first non-zero exit code wins — typed
                        // halts (resolve failure, policy gate) take
                        // priority over the default "nothing committed"
                        // exit 4.
                        if (exitCode == 0) exitCode = h.code();
                    }
                    default -> {}
                }
            }
        }
        return new Report(errorCount, exitCode, committed, agentChanges, orphans);
    }
}
