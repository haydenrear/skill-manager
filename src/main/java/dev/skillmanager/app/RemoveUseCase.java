package dev.skillmanager.app;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionLedger;
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

        // Walk the projection ledger — tear down every binding the unit
        // owns (DEFAULT_AGENT projections AND any user-created EXPLICIT
        // bindings to custom roots). When the ledger is empty (legacy
        // installs that predate ticket 49, or pre-migration state), fall
        // back to the per-agent UnlinkAgentUnit sweep so the existing
        // contract still holds.
        ProjectionLedger ledger = new BindingStore(store).read(skillName);
        if (!ledger.bindings().isEmpty()) {
            for (Binding b : ledger.bindings()) {
                // Reverse projections in LIFO order — SYMLINK first,
                // RENAMED_ORIGINAL_BACKUP last so the original
                // destination contents land back where they started.
                List<Projection> reversed = new ArrayList<>(b.projections());
                java.util.Collections.reverse(reversed);
                for (Projection p : reversed) {
                    effects.add(new SkillEffect.UnmaterializeProjection(p));
                }
                effects.add(new SkillEffect.RemoveBinding(skillName, b.bindingId()));
            }
        } else {
            List<String> agents = agentsToUnlink == null
                    ? Agent.all().stream().map(Agent::id).toList()
                    : agentsToUnlink;
            for (String agentId : agents) {
                effects.add(new SkillEffect.UnlinkAgentUnit(agentId, skillName, kind));
            }
        }

        effects.add(new SkillEffect.RemoveUnitFromStore(skillName, kind));

        // Plugin marketplace + harness CLI cleanup. Skip for skills —
        // the marketplace only catalogs plugins. For plugins, regenerate
        // the marketplace.json (skill is already gone from the store
        // listing by this effect's execution time) and tell each
        // available harness CLI to drop its record.
        if (kind == UnitKind.PLUGIN) {
            effects.add(SkillEffect.RefreshHarnessPlugins.removing(skillName));
        }

        // Lock flip — drop the row for this unit. Last main effect so
        // any earlier failure leaves the lock untouched.
        java.nio.file.Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
        dev.skillmanager.lock.UnitsLock current = dev.skillmanager.lock.UnitsLockReader.read(lockPath);
        dev.skillmanager.lock.UnitsLock target = current.withoutUnit(skillName);
        if (unregisterMcp && !removedDeps.isEmpty()) {
            // Compute orphans against the projected post-remove state by
            // pretending {@code skillName} is gone. Walk every installed
            // unit (skills + plugins) so a surviving plugin claiming the
            // same MCP server keeps it registered — matches the
            // skill-claim path. PluginUnit.mcpDependencies() is already
            // unioned across the plugin-level toml + every contained
            // skill at parse time, so a single read covers all claims.
            Set<String> stillReferenced = new HashSet<>();
            try {
                for (var u : store.listInstalledUnits()) {
                    if (u.name().equals(skillName)) continue;
                    for (McpDependency d : u.mcpDependencies()) stillReferenced.add(d.name());
                }
            } catch (IOException io) {
                // Fall back to skills-only on listing failure — better to
                // miss a plugin claim and emit a wrongly-orphan unregister
                // (which the gateway will reject if still in use) than to
                // skip the orphan sweep entirely and leak a dead server.
                for (Skill s : store.listInstalled()) {
                    if (s.name().equals(skillName)) continue;
                    for (McpDependency d : s.mcpDependencies()) stillReferenced.add(d.name());
                }
            }
            for (McpDependency d : removedDeps) {
                if (!stillReferenced.contains(d.name())) {
                    effects.add(new SkillEffect.UnregisterMcpOrphan(d.name(), gw));
                }
            }
        }

        // Lock flip lands last — orphan-unregister is the only thing
        // post-commit that could fail (gateway unreachable), and a failed
        // orphan-unregister shouldn't leave the lock claiming the unit
        // is still installed. Executor's RestoreUnitsLock walks back the
        // disk write if anything trailing fails; for now nothing does.
        effects.add(new SkillEffect.UpdateUnitsLock(target, lockPath));

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
