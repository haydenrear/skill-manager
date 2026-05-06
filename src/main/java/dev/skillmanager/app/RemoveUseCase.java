package dev.skillmanager.app;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

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

        // Recover the unit's kind from its installed-record. Defaults to
        // SKILL when unknown — for legacy records that predate ticket 03's
        // unitKind field, that's what they were anyway.
        UnitKind kind = new UnitStore(store)
                .read(skillName)
                .map(InstalledUnit::unitKind)
                .orElse(UnitKind.SKILL);

        // Snapshot MCP deps BEFORE the remove so we can compute orphans
        // against the post-remove store list. Plugin uninstall has to
        // re-walk the on-disk plugin (plugin.json + skill-manager-plugin.toml +
        // every contained skill's skill-manager.toml) to recover the
        // effective dep set — without this, MCP servers declared by a
        // contained skill leak on uninstall (the surface store.load(name)
        // exposes for plugins is just the plugin manifest, not the unioned
        // contents). PluginUnit#mcpDependencies is already unioned at parse
        // time, so once we reload via PluginParser the dep set is complete.
        List<McpDependency> removedDeps = recoverEffectiveMcpDeps(store, skillName, kind);

        if (unregisterMcp) effects.addAll(ResolveContextUseCase.preflight(gw, null, true));

        List<String> agents = agentsToUnlink == null
                ? Agent.all().stream().map(Agent::id).toList()
                : agentsToUnlink;
        for (String agentId : agents) {
            effects.add(new SkillEffect.UnlinkAgentUnit(agentId, skillName, kind));
        }

        effects.add(new SkillEffect.RemoveUnitFromStore(skillName, kind));

        if (unregisterMcp && !removedDeps.isEmpty()) {
            // Compute orphans against the projected post-remove state by
            // pretending skillName is gone. Iterates skill-only
            // listInstalled — pre-ticket-11, plugin-contained-skill claims
            // aren't surfaced here. That means: if a plugin we're NOT
            // uninstalling declares the same MCP server we're tearing down,
            // the orphan check won't see its claim and we'd unregister.
            // Ticket 11's listInstalledUnits closes that gap. The risk is
            // low in practice (most installs are single skills) and the
            // executor's UnregisterMcpIfOrphan compensation re-checks at
            // walk-back time anyway, so a wrongly-emitted unregister gets
            // surfaced as a separate failure rather than corrupting state.
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

    /**
     * Re-walk the unit on disk to recover its effective MCP dep set. For
     * SKILL units this is just {@code Skill#mcpDependencies}. For PLUGIN
     * units it's {@link PluginUnit#mcpDependencies} — pre-unioned at parse
     * time across the plugin-level toml + every contained skill's toml.
     *
     * <p>Re-parsing on disk (vs. consulting the installed-record) matters
     * because the record only carries identity + provenance, not the
     * declared deps. The contract for uninstall is "unregister whatever
     * the unit claimed at the moment of uninstall" — that's whatever its
     * on-disk manifests say, after any sync that may have changed them.
     */
    static List<McpDependency> recoverEffectiveMcpDeps(SkillStore store, String unitName, UnitKind kind) {
        return switch (kind) {
            case SKILL -> {
                try {
                    yield store.load(unitName).map(Skill::mcpDependencies).orElse(List.of());
                } catch (IOException io) {
                    Log.warn("uninstall: could not parse skill %s — %s", unitName, io.getMessage());
                    yield List.of();
                }
            }
            case PLUGIN -> {
                try {
                    PluginUnit p = PluginParser.load(store.unitDir(unitName, UnitKind.PLUGIN));
                    yield p.mcpDependencies();
                } catch (IOException io) {
                    Log.warn("uninstall: could not parse plugin %s — %s", unitName, io.getMessage());
                    yield List.of();
                }
            }
        };
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
