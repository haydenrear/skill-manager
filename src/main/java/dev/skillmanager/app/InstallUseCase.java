package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.resolve.ResolvedGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link Program} {@code install} runs after the resolver has
 * staged the dep graph. Every step is an effect with its own receipt:
 *
 * <ol>
 *   <li>{@link SkillEffect.ConfigureRegistry} — persist a {@code --registry} override (no-op if blank).</li>
 *   <li>{@link SkillEffect.EnsureGateway} — start the local gateway if not already running.</li>
 *   <li>{@link SkillEffect.CommitSkillsToStore} — move staged skill dirs into the store; rolls back partials on failure.</li>
 *   <li>{@link SkillEffect.RecordAuditPlan} — append the {@code "install"} audit entry.</li>
 *   <li>{@link SkillEffect.RecordSourceProvenance} — write {@code sources/<name>.json} for the committed graph.</li>
 *   <li>{@link SkillEffect.ResolveTransitives} — install any unmet {@code skill_references}.</li>
 *   <li>{@link SkillEffect.InstallTools} → {@link SkillEffect.InstallCli}.</li>
 *   <li>{@link SkillEffect.RegisterMcp} → {@link SkillEffect.SyncAgents}.</li>
 * </ol>
 *
 * <p>Dry-run drops the commit / audit / provenance effects so nothing
 * touches the store, but keeps the post-update tail so the user sees the
 * full effect chain.
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

    public static Program<Report> buildProgram(GatewayConfig gw, String registryOverride,
                                               ResolvedGraph graph, InstallPlan plan,
                                               boolean dryRun) {
        List<SkillEffect> effects = new ArrayList<>(
                ResolveContextUseCase.preflight(gw, registryOverride, !dryRun));
        if (!dryRun) {
            effects.add(new SkillEffect.CommitSkillsToStore(graph));
            effects.add(new SkillEffect.RecordAuditPlan(plan, "install"));
            effects.add(new SkillEffect.RecordSourceProvenance(graph));
        }

        List<Skill> skills = graph.skills();
        effects.add(new SkillEffect.ResolveTransitives(skills));
        // Expand the plan into per-action effects (one EnsureTool per unique
        // tool, one RunCliInstall per CLI dep, one RegisterMcpServer per MCP
        // dep) — finer-grained receipts than the bulk InstallTools / InstallCli
        // / RegisterMcp effects, plus a SetupPackageManagerRuntime up front.
        effects.addAll(PlanExpander.expand(plan, gw));
        effects.add(new SkillEffect.SyncAgents(skills, gw));

        return new Program<>("install-" + UUID.randomUUID(), effects, InstallUseCase::decode);
    }

    private static Report decode(List<EffectReceipt> receipts) {
        int errorCount = 0;
        List<String> committed = new ArrayList<>();
        Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        for (EffectReceipt r : receipts) {
            if (r.status() == EffectStatus.FAILED || r.status() == EffectStatus.PARTIAL) errorCount++;
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.SkillCommitted c -> committed.add(c.name());
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
