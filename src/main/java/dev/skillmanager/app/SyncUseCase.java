package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One program for both {@code sync} and {@code upgrade}: a {@link
 * SkillEffect.SyncGit} per target, then the same post-update tail as
 * {@link PostUpdateUseCase} (resolve transitives, install tools/CLI, register
 * MCP, sync agents, unregister orphans).
 *
 * <p>The use case is install-source-aware at plan time — for each target we
 * read the {@link SkillSource} once and bake the {@link
 * SkillSource.InstallSource} into the {@link SkillEffect.SyncGit} record so
 * dry-run output shows which routing arm runs for each skill.
 */
public final class SyncUseCase {

    private SyncUseCase() {}

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
                                               String registryOverride,
                                               List<String> targetNames,
                                               boolean gitLatest,
                                               boolean merge,
                                               boolean withMcp,
                                               boolean withAgents,
                                               Map<String, Set<String>> preMcpDeps) throws IOException {
        SkillSourceStore sources = new SkillSourceStore(store);
        List<SkillEffect> effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, registryOverride, withMcp));

        for (String name : targetNames) {
            SkillSource src = sources.read(name).orElse(null);
            SkillSource.InstallSource is = src != null && src.installSource() != null
                    ? src.installSource()
                    : SkillSource.InstallSource.UNKNOWN;
            effects.add(new SkillEffect.SyncGit(name, is, gitLatest, merge));
        }

        // Post-update tail. Read live skills here — handlers will see the
        // post-merge content because each SyncGit handler invalidates the
        // source cache, but the live skill list is read at plan time.
        // Handlers that take List<Skill> walk the in-memory list — install/CLI
        // installers re-read manifests from disk inside their plan builder.
        List<Skill> live = store.listInstalled();
        effects.add(new SkillEffect.ResolveTransitives(live));
        effects.add(new SkillEffect.InstallTools(live));
        effects.add(new SkillEffect.InstallCli(live));
        if (withMcp) effects.add(new SkillEffect.RegisterMcp(live, gw));
        if (withAgents) effects.add(new SkillEffect.SyncAgents(live, gw));
        if (withMcp) {
            for (String orphan : PostUpdateUseCase.computeOrphans(preMcpDeps, live)) {
                effects.add(new SkillEffect.UnregisterMcpOrphan(orphan, gw));
            }
        }

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
            if (r.status() == EffectStatus.FAILED) errorCount++;
            if (r.effect() instanceof SkillEffect.SyncGit
                    && (r.status() == EffectStatus.PARTIAL || r.status() == EffectStatus.FAILED)) {
                errorCount++;
            }
            if (r.effect() instanceof SkillEffect.RegisterMcp
                    && (r.status() == EffectStatus.PARTIAL || r.status() == EffectStatus.FAILED)) {
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
