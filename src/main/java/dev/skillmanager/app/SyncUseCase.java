package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.effects.StagedProgram;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.store.UnitReadProblem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One program for both {@code sync} and {@code upgrade}: a per-target
 * effect (either {@link SkillEffect.SyncGit} or {@link
 * SkillEffect.SyncFromLocalDir}), then the post-update tail (transitives,
 * tools/CLI/MCP, agents) plus orphan-detection.
 *
 * <p>{@code preMcpDeps} is captured by an in-program {@link
 * SkillEffect.SnapshotMcpDeps} effect — no snapshot argument plumbed
 * through. Per-skill {@link InstalledUnit.InstallSource} is read at
 * use-case-build time and baked into each {@link SkillEffect.SyncGit}
 * record so dry-run output shows the routing arm.
 */
public final class SyncUseCase {

    private SyncUseCase() {}

    /** All non-target sync flags as one record so the buildProgram signature stays small. */
    public record Options(
            String registryOverride,
            boolean gitLatest,
            boolean merge,
            boolean withMcp,
            boolean withAgents,
            boolean yesForFromDir) {}

    /**
     * A single sync target — what {@code skill-manager sync <name>}
     * should do for one unit. The shape depends on what kind of unit
     * we're syncing:
     * <ul>
     *   <li>{@link Git} — skill/plugin sync: fetch + merge against the
     *       unit's git origin (or registry-driven sha).</li>
     *   <li>{@link FromDir} — skill/plugin: apply contents from a
     *       local working directory ({@code --from <dir>}).</li>
     *   <li>{@link DocRepo} — doc-repo: walk the projection ledger
     *       for the unit, route each {@code MANAGED_COPY} through
     *       the four-state drift matrix (#48), reapply
     *       {@code IMPORT_DIRECTIVE} rows idempotently.</li>
     * </ul>
     *
     * <p>Future kinds (harness templates per #47) will add their own
     * Target variants so {@code SyncCommand} stays a single dispatch
     * point.
     */
    public sealed interface Target {
        String skillName();
        record Git(String skillName) implements Target {}
        record FromDir(String skillName, Path dir) implements Target {}
        /**
         * Doc-repo sync (#48). {@code force} clobbers locally-edited
         * and conflict destinations; default is preserve-with-warning.
         */
        record DocRepo(String skillName, boolean force) implements Target {}
        /**
         * Harness instance reconciliation (#47): re-runs the
         * instantiator against {@code instanceId} so changes to the
         * template (added / removed unit references, version bumps,
         * doc-repo source changes) propagate into the live sandbox.
         * Idempotent: stable harness binding ids mean re-applying the
         * same plan rewrites the existing bindings.
         *
         * <p>{@code skillName} carries the harness template name so the
         * existing {@link Target#skillName} surface keeps working for
         * legacy callers that don't know about the instance.
         */
        record Harness(String skillName, String instanceId) implements Target {}
    }

    public record Report(
            int worstRc,
            List<String> refused,
            List<String> conflicted,
            int errorCount,
            Map<McpWriter.ConfigChange, List<String>> agentConfigChanges,
            List<String> orphansUnregistered) {

        public static Report empty() {
            return new Report(0, List.of(), List.of(), 0, Map.of(), List.of());
        }
    }

    /**
     * Sync is a two-stage program. Stage 1 runs the per-target sync (git
     * fetch+merge or apply-from-dir). Stage 2 is built post-merge: it
     * scans the live store for {@code skill_references} that didn't
     * exist before the merge, resolves them, then runs the post-update
     * tail (InstallTools/Cli/RegisterMcp/SyncAgents/UnregisterOrphans)
     * over the union of pre-existing units and the newly-resolved ones.
     *
     * <p>Stage 2's effect list is data-dependent — its shape comes from
     * what stage 1 leaves in the store — so it can't be expressed as a
     * static {@link Program}. The {@link StagedProgram} wrapper carries
     * a stage-2 builder that the interpreter invokes after stage 1
     * completes, with the same {@link EffectContext} threaded through.
     */
    public static StagedProgram<Report> buildProgram(SkillStore store,
                                                     GatewayConfig gw,
                                                     Options options,
                                                     List<Target> targets,
                                                     List<UnitReadProblem> initialReadProblems) throws IOException {
        Program<?> stage1 = buildStage1(store, gw, options, targets, initialReadProblems);
        java.util.function.Function<EffectContext, Program<?>> stage2 =
                ctx -> buildStage2(ctx, gw, options);
        return new StagedProgram<>("sync-" + UUID.randomUUID(), stage1, stage2, SyncUseCase::decode);
    }

    private static Program<?> buildStage1(SkillStore store,
                                          GatewayConfig gw,
                                          Options options,
                                          List<Target> targets,
                                          List<UnitReadProblem> initialReadProblems) throws IOException {
        UnitStore sources = new UnitStore(store);
        List<SkillEffect> effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, options.registryOverride(), options.withMcp()));
        if (initialReadProblems != null && !initialReadProblems.isEmpty()) {
            effects.add(new SkillEffect.ReportUnitReadProblems(initialReadProblems));
        }
        if (options.withMcp()) effects.add(new SkillEffect.SnapshotMcpDeps());
        List<String> targetNames = new ArrayList<>();
        for (Target t : targets) {
            targetNames.add(t.skillName());
            switch (t) {
                case Target.Git g -> {
                    InstalledUnit src = sources.read(g.skillName()).orElse(null);
                    InstalledUnit.InstallSource is = src != null && src.installSource() != null
                            ? src.installSource()
                            : InstalledUnit.InstallSource.UNKNOWN;
                    dev.skillmanager.model.UnitKind kind = src != null && src.unitKind() != null
                            ? src.unitKind()
                            : dev.skillmanager.model.UnitKind.SKILL;
                    effects.add(new SkillEffect.SyncGit(
                            g.skillName(), kind, is, options.gitLatest(), options.merge()));
                }
                case Target.FromDir f -> effects.add(new SkillEffect.SyncFromLocalDir(
                        f.skillName(), f.dir(), options.merge(), options.yesForFromDir()));
                case Target.DocRepo d -> effects.add(new SkillEffect.SyncDocRepo(
                        d.skillName(), d.force()));
                case Target.Harness h -> effects.add(new SkillEffect.SyncHarness(
                        h.skillName(), h.instanceId()));
            }
        }
        effects.add(new SkillEffect.ValidateMarkdownImports(targetNames));
        return new Program<>("sync-stage1-" + UUID.randomUUID(), effects, receipts -> null);
    }

    /**
     * Stage 2 builder, invoked by the interpreter after stage 1 finishes.
     * Reads the post-merge live store, resolves any references that point
     * at units not in the store, commits them, then runs the post-update
     * tail over the union (existing + newly-resolved). The bulk handlers
     * reload manifests from disk per name so updated dep declarations on
     * existing skills are picked up.
     */
    private static Program<?> buildStage2(EffectContext ctx, GatewayConfig gw, Options options) {
        SkillStore store = ctx.store();
        // Kind-aware listing — covers skills under skills/ and plugins
        // under plugins/. Skill-only listInstalled() (legacy) misses
        // plugin-kind units, which silently skipped the post-update
        // tail (RegisterMcp, SyncAgents, RefreshHarnessPlugins).
        List<dev.skillmanager.model.AgentUnit> liveUnits;
        List<SkillEffect> effects = new ArrayList<>();
        try {
            var listed = store.listInstalledUnits();
            liveUnits = new ArrayList<>(listed.units());
            if (!listed.problems().isEmpty()) {
                effects.add(new SkillEffect.ReportUnitReadProblems(listed.problems()));
            }
        } catch (IOException io) {
            // Halt-via-empty-program: the surrounding command will see no
            // tail effects and the sync still reports through stage 1's
            // receipts. Realistically this only fails if the store dir is
            // mid-rename, which is rare enough to warrant a no-op tail.
            return new Program<>("sync-stage2-" + UUID.randomUUID(), List.of(), receipts -> null);
        }

        List<SkillEffect> alwaysAfter = new ArrayList<>();

        // discoverNewlySurfacedRefs still consumes List<Skill> — pull the
        // skill subset out for it. Plugins don't surface new refs through
        // skill_references; their references live on the plugin / contained
        // skills, both of which are already part of liveUnits.
        List<Skill> liveSkills = new ArrayList<>();
        for (var u : liveUnits) {
            if (u instanceof dev.skillmanager.model.SkillUnit su) liveSkills.add(su.skill());
        }
        // Stage 2 owns the unmet-reference resolve via the new effect.
        // The handler walks each live skill's references, resolves the
        // unmet set, and writes the resulting graph into
        // ctx.resolvedGraph() — every downstream effect with a no-arg
        // constructor reads from there. When the resolve finds nothing
        // unmet (steady-state sync), the graph is empty and the commit /
        // plan / provenance / run effects are no-ops. Same shape as the
        // install path's BuildResolveGraphFromSource preamble.
        effects.add(new SkillEffect.BuildResolveGraphFromUnmetReferences(liveSkills));
        effects.add(new SkillEffect.CommitUnitsToStore());
        effects.add(SkillEffect.ValidateMarkdownImports.resolvedGraph());
        effects.add(new SkillEffect.BuildInstallPlan());
        effects.add(new SkillEffect.RecordSourceProvenance());
        effects.add(new SkillEffect.RunInstallPlan(gw));
        // Cleanup the resolver's staged temp dirs no matter how the
        // tail goes — the resolve effect populates ctx with a fresh
        // graph each pass.
        alwaysAfter.add(new SkillEffect.CleanupResolvedGraph());

        effects.add(new SkillEffect.InstallTools(liveUnits));
        effects.add(new SkillEffect.InstallCli(liveUnits));
        if (options.withMcp()) effects.add(new SkillEffect.RegisterMcp(liveUnits, gw));
        if (options.withAgents()) effects.add(new SkillEffect.SyncAgents(liveUnits, gw));
        // Plugin marketplace + harness CLI lifecycle. Sync is the
        // user-stated trigger for "uninstall+reinstall every plugin" so
        // hooks reload from the just-merged bytes — pass every installed
        // plugin name as the reinstall set.
        if (options.withAgents()) {
            List<String> pluginNames = new ArrayList<>();
            for (var u : liveUnits) {
                if (u.kind() == dev.skillmanager.model.UnitKind.PLUGIN) pluginNames.add(u.name());
            }
            effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames));
        }
        if (options.withMcp()) effects.add(new SkillEffect.UnregisterMcpOrphans(gw));

        // Lock flip — last main effect. Targets the post-merge live state.
        // Sync's "bumped sha" rows are the primary thing that changes
        // here: a unit's installed-record gitHash advances after the
        // merge, and the lock follows. Newly-resolved extras (if any)
        // are reflected on the NEXT sync — their installed-records will
        // have been written by RecordSourceProvenance by then.
        effects.add(buildLockUpdate(store, liveUnits));

        Program<?> p = new Program<>("sync-stage2-" + UUID.randomUUID(), effects, receipts -> null);
        for (SkillEffect cleanup : alwaysAfter) p = p.withFinally(cleanup);
        return p;
    }

    /**
     * Compute the post-sync lock target. Reads the current lock and
     * upserts one row per live unit (post-merge installed-record state).
     * Plugins flow through the same path — the only thing the lock
     * cares about is the installed-record per name.
     *
     * <p>Newly-resolved extras (from
     * {@link SkillEffect.BuildResolveGraphFromUnmetReferences}) aren't
     * reflected here: at builder time they don't exist yet, and by the
     * time the lock effect runs, the extras' installed-records have been
     * written by {@link SkillEffect.RecordSourceProvenance}, but {@code
     * live} was captured pre-resolve. They flow into the lock on the
     * next sync via the live-skill loop above — same convergence pattern
     * as drift detection.
     */
    private static SkillEffect.UpdateUnitsLock buildLockUpdate(
            SkillStore store, List<dev.skillmanager.model.AgentUnit> live) {
        try {
            java.nio.file.Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
            dev.skillmanager.lock.UnitsLock current = dev.skillmanager.lock.UnitsLockReader.read(lockPath);
            UnitStore sources = new UnitStore(store);
            dev.skillmanager.lock.UnitsLock target = current;
            for (var u : live) {
                InstalledUnit rec = sources.read(u.name()).orElse(null);
                if (rec == null) continue;
                target = target.withUnit(dev.skillmanager.lock.LockedUnit.fromInstalled(rec));
            }
            return new SkillEffect.UpdateUnitsLock(target, lockPath);
        } catch (IOException io) {
            return new SkillEffect.UpdateUnitsLock(
                    dev.skillmanager.lock.UnitsLock.empty(),
                    dev.skillmanager.lock.UnitsLockReader.defaultPath(store));
        }
    }

    private static Report decode(List<EffectReceipt> receipts) {
        int worstRc = 0;
        List<String> refused = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        int errorCount = 0;
        Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();

        for (EffectReceipt r : receipts) {
            // Each receipt counts at most once. PARTIAL counts on per-target
            // sync (SyncGit / SyncFromLocalDir), RegisterMcp (some skills had
            // MCP registration errors), and SyncAgents (per-(agent,skill)
            // sync failures). Other PARTIAL paths are informational.
            boolean isSyncTarget = r.effect() instanceof SkillEffect.SyncGit
                    || r.effect() instanceof SkillEffect.SyncFromLocalDir;
            boolean isMcpRegister = r.effect() instanceof SkillEffect.RegisterMcp;
            boolean isAgentSync = r.effect() instanceof SkillEffect.SyncAgents;
            if (r.status() == EffectStatus.FAILED) {
                errorCount++;
            } else if (r.status() == EffectStatus.PARTIAL
                    && (isSyncTarget || isMcpRegister || isAgentSync)) {
                errorCount++;
            }
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.SyncGitRefused g -> {
                        refused.add(g.skillName());
                        if (worstRc < 7) worstRc = 7;
                    }
                    case ContextFact.SyncGitConflicted g -> {
                        conflicted.add(g.skillName());
                        if (worstRc < 8) worstRc = 8;
                    }
                    case ContextFact.SyncGitFailed ignored -> {
                        if (worstRc < 1) worstRc = 1;
                    }
                    case ContextFact.AgentMcpConfigChanged c -> agentChanges
                            .computeIfAbsent(c.change(), k -> new ArrayList<>())
                            .add(c.agentId() + " (" + c.configPath() + ")");
                    case ContextFact.OrphanUnregistered o -> orphans.add(o.serverId());
                    default -> {}
                }
            }
        }
        return new Report(worstRc, refused, conflicted, errorCount, agentChanges, orphans);
    }

    public static void printSyncSummary(Report report) {
        if (report.refused().isEmpty() && report.conflicted().isEmpty()) return;
        System.err.println();
        System.err.println("sync summary: "
                + (report.refused().size() + report.conflicted().size()) + " skill(s) need attention");
        if (!report.refused().isEmpty()) {
            System.err.println();
            System.err.println("  Extra local changes — re-run with --merge to bring upstream in:");
            for (String n : report.refused()) {
                System.err.println("    skill-manager sync " + n + " --merge");
            }
        }
        if (!report.conflicted().isEmpty()) {
            System.err.println();
            System.err.println("  Conflicted — resolve in the store dir, then `git commit` or `git merge --abort`:");
            for (String n : report.conflicted()) System.err.println("    " + n);
        }
        System.err.println();
    }
}
