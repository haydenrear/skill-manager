package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
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

    public static Program<Report> buildProgram(SkillStore store,
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

        // Post-update tail. The skill list captured here is the *names*
        // of installed skills at use-case-build time (pre-merge). The
        // ResolveTransitives handler re-reads ctx.store().listInstalled()
        // at exec time, so any newly-pulled-in skill refs go through a
        // full sub-install (which covers their tools/cli/mcp/agents).
        // The bulk InstallTools/InstallCli/RegisterMcp/SyncAgents handlers
        // re-load each named skill's manifest from disk, so updated deps
        // on existing skills are picked up post-merge.
        List<Skill> live = store.listInstalled();
        effects.add(new SkillEffect.ResolveTransitives(live));
        effects.add(new SkillEffect.InstallTools(live));
        effects.add(new SkillEffect.InstallCli(live));
        if (options.withMcp()) effects.add(new SkillEffect.RegisterMcp(live, gw));
        if (options.withAgents()) effects.add(new SkillEffect.SyncAgents(live, gw));
        if (options.withMcp()) effects.add(new SkillEffect.UnregisterMcpOrphans(gw));

        return new Program<>("sync-" + UUID.randomUUID(), effects, SyncUseCase::decode);
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
