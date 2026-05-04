package dev.skillmanager.app;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Effect program for {@code remove} and {@code uninstall}: optional
 * gateway preflight, the per-(agent, skill) unlinks, the store delete,
 * and orphan MCP unregisters. The two commands differ only in which
 * agents to unlink and whether to skip the gateway entirely.
 */
public final class RemoveUseCase {

    private RemoveUseCase() {}

    public record Report(
            boolean removed,
            List<String> unlinkedAgents,
            List<String> unregisteredOrphans,
            int errorCount) {

        public static Report empty() { return new Report(false, List.of(), List.of(), 0); }
    }

    /**
     * @param agentsToUnlink  agent ids to unlink from. Empty = none. {@code null} = all known agents.
     * @param unregisterMcp   {@code true} to compute orphans and emit unregister effects.
     */
    public static Program<Report> buildProgram(SkillStore store, GatewayConfig gw,
                                               String skillName,
                                               List<String> agentsToUnlink,
                                               boolean unregisterMcp) throws IOException {
        List<SkillEffect> effects = new ArrayList<>();

        // Snapshot MCP deps BEFORE the remove so we can compute orphans
        // against the post-remove store list.
        List<McpDependency> removedDeps = store.load(skillName)
                .map(Skill::mcpDependencies)
                .orElse(List.of());

        if (unregisterMcp) effects.addAll(ResolveContextUseCase.preflight(gw, null, true));

        List<String> agents = agentsToUnlink == null
                ? Agent.all().stream().map(Agent::id).toList()
                : agentsToUnlink;
        for (String agentId : agents) {
            effects.add(new SkillEffect.UnlinkAgentSkill(agentId, skillName));
        }

        effects.add(new SkillEffect.RemoveSkillFromStore(skillName));

        if (unregisterMcp && !removedDeps.isEmpty()) {
            // Compute orphans against the projected post-remove state by
            // pretending skillName is gone.
            Set<String> stillReferenced = new HashSet<>();
            for (Skill s : store.listInstalled()) {
                if (s.name().equals(skillName)) continue;
                for (McpDependency d : s.mcpDependencies()) stillReferenced.add(d.name());
            }
            for (McpDependency d : removedDeps) {
                if (!stillReferenced.contains(d.name())) {
                    effects.add(new SkillEffect.UnregisterMcpOrphan(d.name(), gw));
                }
            }
        }

        return new Program<>("remove-" + UUID.randomUUID(), effects, RemoveUseCase::decode);
    }

    private static Report decode(List<EffectReceipt> receipts) {
        boolean removed = false;
        List<String> unlinked = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        int errorCount = 0;
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.SkillRemovedFromStore ignored -> removed = true;
                    case ContextFact.AgentSkillUnlinked u -> unlinked.add(u.agentId());
                    case ContextFact.OrphanUnregistered o -> orphans.add(o.serverId());
                    default -> {}
                }
            }
        }
        return new Report(removed, unlinked, orphans, errorCount);
    }
}
