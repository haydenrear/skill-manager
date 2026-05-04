package dev.skillmanager.effects;

import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillReference;
import dev.skillmanager.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prints what would happen without touching the filesystem, gateway, or
 * registry. Returns OK receipts so the decoder produces a normal report
 * but no facts (the decoder can detect dry-run by checking
 * {@code receipt.facts().get("dryRun")}).
 */
public final class DryRunInterpreter implements ProgramInterpreter {

    @Override
    public <R> R run(Program<R> program) {
        System.out.println();
        System.out.println("DRY RUN — no changes will be made");
        System.out.println("operation: " + program.operationId());
        System.out.println("effects (" + program.effects().size() + "):");

        List<EffectReceipt> receipts = new ArrayList<>();
        int i = 0;
        for (SkillEffect effect : program.effects()) {
            describe(++i, effect);
            receipts.add(EffectReceipt.ok(effect, Map.of("dryRun", true)));
        }
        System.out.println();
        return program.decoder().decode(receipts);
    }

    private static void describe(int n, SkillEffect effect) {
        switch (effect) {
            case SkillEffect.ResolveTransitives e -> {
                Log.step("[%d] resolve transitives over %d skill(s)", n, e.skills().size());
                for (Skill s : e.skills()) {
                    for (SkillReference ref : s.skillReferences()) {
                        String coord = ref.path() != null ? "file:" + ref.path()
                                : ref.version() != null ? ref.name() + "@" + ref.version()
                                : ref.name();
                        System.out.println("       - " + s.name() + " → " + coord);
                    }
                }
            }
            case SkillEffect.InstallToolsAndCli e ->
                    Log.step("[%d] install tools + CLI deps for %d skill(s)", n, e.skills().size());
            case SkillEffect.RegisterMcp e -> {
                Log.step("[%d] register MCP deps with %s", n, e.gateway().baseUrl());
                for (Skill s : e.skills()) {
                    for (McpDependency d : s.mcpDependencies()) {
                        System.out.println("       - " + s.name() + " → " + d.name() + " (" + d.defaultScope() + ")");
                    }
                }
            }
            case SkillEffect.UnregisterMcpOrphan e ->
                    Log.step("[%d] unregister orphan MCP server %s", n, e.serverId());
            case SkillEffect.SyncAgents e ->
                    Log.step("[%d] sync agents over %d skill(s)", n, e.skills().size());
        }
    }
}
