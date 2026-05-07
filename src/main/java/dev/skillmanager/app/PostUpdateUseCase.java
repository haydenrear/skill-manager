package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the {@link Program} that runs after install / sync / upgrade
 * have mutated the store. Pure: no side effects, just produces effect
 * data the caller hands to an interpreter.
 *
 * <p>The pre-mutation MCP-dep snapshot is captured by a
 * {@link SkillEffect.SnapshotMcpDeps} effect at the start of the program
 * (writes into {@link dev.skillmanager.effects.EffectContext#preMcpDeps()})
 * and consumed by a single {@link SkillEffect.UnregisterMcpOrphans} effect
 * at the end — no preMcpDeps argument plumbed through the use case.
 */
public final class PostUpdateUseCase {

    private PostUpdateUseCase() {}

    public record Report(
            int errorCount,
            Map<McpWriter.ConfigChange, List<String>> agentConfigChanges,
            List<String> orphansUnregistered) {
        public static Report empty() {
            return new Report(0, Map.of(), List.of());
        }
    }

    public static Program<Report> buildProgram(SkillStore store,
                                               GatewayConfig gw,
                                               boolean withMcp,
                                               boolean withAgents) throws IOException {
        List<Skill> live = store.listInstalled();
        List<dev.skillmanager.model.AgentUnit> liveUnits = new ArrayList<>(live.size());
        for (Skill s : live) liveUnits.add(s.asUnit());
        List<SkillEffect> effects = new ArrayList<>();

        // ResolveTransitives is sync-only — it catches new references that
        // post-merge content surfaces. Post-update runs over the existing
        // installed set; everything is already in the store.
        if (withMcp) effects.add(new SkillEffect.SnapshotMcpDeps());
        effects.add(new SkillEffect.InstallTools(liveUnits));
        effects.add(new SkillEffect.InstallCli(liveUnits));
        if (withMcp) effects.add(new SkillEffect.RegisterMcp(liveUnits, gw));
        if (withAgents) effects.add(new SkillEffect.SyncAgents(liveUnits, gw));
        // Reconcile harness plugin marketplace alongside the symlink
        // path. RegisterMcp covers MCP-side surfaces; this covers the
        // plugin-marketplace side. Skill-only stores end up with an
        // empty plugin list — the effect is still cheap (writes a
        // marketplace.json with an empty plugins array).
        if (withAgents) {
            java.util.List<String> pluginNames = new java.util.ArrayList<>();
            for (var u : liveUnits) {
                if (u.kind() == dev.skillmanager.model.UnitKind.PLUGIN) pluginNames.add(u.name());
            }
            effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames));
        }
        if (withMcp) effects.add(new SkillEffect.UnregisterMcpOrphans(gw));

        return new Program<>("post-update-" + UUID.randomUUID(), effects, PostUpdateUseCase::decode);
    }

    private static Report decode(List<EffectReceipt> receipts) {
        int errorCount = 0;
        Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.AgentMcpConfigChanged c -> agentChanges
                            .computeIfAbsent(c.change(), k -> new ArrayList<>())
                            .add(c.agentId() + " (" + c.configPath() + ")");
                    case ContextFact.OrphanUnregistered o -> orphans.add(o.serverId());
                    default -> {}
                }
            }
        }
        return new Report(errorCount, agentChanges, orphans);
    }

    /** Helper used by the {@link SkillEffect.UnregisterMcpOrphans} handler. */
    public static List<String> computeOrphans(Map<String, Set<String>> preMcpDeps, List<Skill> postSkills) {
        Set<String> stillReferenced = new HashSet<>();
        for (Skill s : postSkills) {
            for (McpDependency d : s.mcpDependencies()) stillReferenced.add(d.name());
        }
        Set<String> previouslyReferenced = new HashSet<>();
        for (Set<String> deps : preMcpDeps.values()) previouslyReferenced.addAll(deps);
        List<String> orphans = new ArrayList<>();
        for (String name : previouslyReferenced) {
            if (!stillReferenced.contains(name)) orphans.add(name);
        }
        return orphans;
    }

    /** Helper used by the {@link SkillEffect.SnapshotMcpDeps} handler. */
    public static Map<String, Set<String>> snapshotMcpDeps(SkillStore store) throws IOException {
        Map<String, Set<String>> snapshot = new LinkedHashMap<>();
        for (Skill s : store.listInstalled()) {
            Set<String> names = new HashSet<>();
            for (McpDependency d : s.mcpDependencies()) names.add(d.name());
            snapshot.put(s.name(), names);
        }
        return snapshot;
    }

    public static void printAgentConfigSummary(Report report, String mcpUrl) {
        var added = report.agentConfigChanges.getOrDefault(McpWriter.ConfigChange.ADDED, List.of());
        var updated = report.agentConfigChanges.getOrDefault(McpWriter.ConfigChange.UPDATED, List.of());
        if (added.isEmpty() && updated.isEmpty()) return;
        System.out.println();
        System.out.println("agent MCP configs:");
        for (String a : added) System.out.println("  ADDED    " + a + "  → " + mcpUrl);
        for (String a : updated) System.out.println("  UPDATED  " + a + "  → " + mcpUrl);
        System.out.println();
        System.out.println("ACTION_REQUIRED: Restart Claude / Codex for the virtual-mcp-gateway entry");
        System.out.println("to take effect — without a restart the agent will not see any MCP tools.");
        System.out.println();
    }
}
