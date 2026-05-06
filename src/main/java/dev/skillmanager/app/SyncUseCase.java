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
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.resolve.Resolver;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

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

    /** A single sync target — either a git fetch+merge against origin, or apply from a local dir. */
    public sealed interface Target {
        String skillName();
        record Git(String skillName) implements Target {}
        record FromDir(String skillName, Path dir) implements Target {}
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
                                                     List<Target> targets) throws IOException {
        Program<?> stage1 = buildStage1(store, gw, options, targets);
        java.util.function.Function<EffectContext, Program<?>> stage2 =
                ctx -> buildStage2(ctx, gw, options);
        return new StagedProgram<>("sync-" + UUID.randomUUID(), stage1, stage2, SyncUseCase::decode);
    }

    private static Program<?> buildStage1(SkillStore store,
                                          GatewayConfig gw,
                                          Options options,
                                          List<Target> targets) throws IOException {
        UnitStore sources = new UnitStore(store);
        List<SkillEffect> effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, options.registryOverride(), options.withMcp()));
        if (options.withMcp()) effects.add(new SkillEffect.SnapshotMcpDeps());
        for (Target t : targets) {
            switch (t) {
                case Target.Git g -> {
                    InstalledUnit src = sources.read(g.skillName()).orElse(null);
                    InstalledUnit.InstallSource is = src != null && src.installSource() != null
                            ? src.installSource()
                            : InstalledUnit.InstallSource.UNKNOWN;
                    effects.add(new SkillEffect.SyncGit(
                            g.skillName(), is, options.gitLatest(), options.merge()));
                }
                case Target.FromDir f -> effects.add(new SkillEffect.SyncFromLocalDir(
                        f.skillName(), f.dir(), options.merge(), options.yesForFromDir()));
            }
        }
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
        List<Skill> live;
        try {
            live = store.listInstalled();
        } catch (IOException io) {
            // Halt-via-empty-program: the surrounding command will see no
            // tail effects and the sync still reports through stage 1's
            // receipts. Realistically this only fails if the store dir is
            // mid-rename, which is rare enough to warrant a no-op tail.
            return new Program<>("sync-stage2-" + UUID.randomUUID(), List.of(), receipts -> null);
        }
        List<dev.skillmanager.model.AgentUnit> liveUnits = new ArrayList<>(live.size());
        for (Skill s : live) liveUnits.add(s.asUnit());

        List<SkillEffect> effects = new ArrayList<>();
        List<SkillEffect> alwaysAfter = new ArrayList<>();

        ResolvedGraph extras = discoverNewlySurfacedRefs(store, live);
        if (!extras.resolved().isEmpty()) {
            // Commit the newly-resolved units (their FetchUnit is implicit
            // in the resolver — extras.resolved() carries staged source
            // dirs ready to copy into the store).
            effects.add(new SkillEffect.CommitSkillsToStore(extras));
            // Build the plan over the extras + run it (tools/CLI/MCP).
            effects.add(new SkillEffect.BuildInstallPlan(extras));
            effects.add(new SkillEffect.RecordSourceProvenance(extras));
            effects.add(new SkillEffect.RunInstallPlan(gw));
            // Cleanup the resolver's staged temp dirs no matter how the
            // tail goes.
            alwaysAfter.add(new SkillEffect.CleanupResolvedGraph(extras));
            // Re-list so the post-update tail sees the newly-committed units
            // alongside the pre-existing ones.
            try {
                live = store.listInstalled();
                liveUnits = new ArrayList<>(live.size());
                for (Skill s : live) liveUnits.add(s.asUnit());
            } catch (IOException ignored) { /* fall through with stale liveUnits */ }
        }

        effects.add(new SkillEffect.InstallTools(liveUnits));
        effects.add(new SkillEffect.InstallCli(liveUnits));
        if (options.withMcp()) effects.add(new SkillEffect.RegisterMcp(liveUnits, gw));
        if (options.withAgents()) effects.add(new SkillEffect.SyncAgents(liveUnits, gw));
        if (options.withMcp()) effects.add(new SkillEffect.UnregisterMcpOrphans(gw));

        Program<?> p = new Program<>("sync-stage2-" + UUID.randomUUID(), effects, receipts -> null);
        for (SkillEffect cleanup : alwaysAfter) p = p.withFinally(cleanup);
        return p;
    }

    /**
     * Walk every live skill's references; any that don't resolve to a
     * unit in the store gets resolved here. Returns an empty graph when
     * everything is in order — the common no-op case.
     */
    private static ResolvedGraph discoverNewlySurfacedRefs(SkillStore store, List<Skill> live) {
        List<Resolver.Coord> unmet = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Skill s : live) {
            for (UnitReference ref : s.skillReferences()) {
                String coord = referenceToCoord(ref, store, s.name());
                String name = ref.name() != null ? ref.name() : guessName(coord);
                if (name == null || name.isBlank() || store.contains(name)) continue;
                if (!seen.add(name)) continue;
                unmet.add(new Resolver.Coord(coord, ref.version()));
            }
        }
        if (unmet.isEmpty()) return new ResolvedGraph();
        try {
            return new Resolver(store).resolveAll(unmet);
        } catch (IOException io) {
            // Resolver failure means we can't fan-out the new refs; fall
            // through with no extras — the next sync run picks them up.
            return new ResolvedGraph();
        }
    }

    private static String referenceToCoord(UnitReference ref, SkillStore store, String parentSkillName) {
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
