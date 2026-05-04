package dev.skillmanager.effects;

import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillReference;
import dev.skillmanager.util.Log;

import java.util.ArrayList;
import java.util.List;

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
            receipts.add(EffectReceipt.ok(effect, new ContextFact.DryRun()));
        }
        System.out.println();
        return program.decoder().decode(receipts);
    }

    private static void describe(int n, SkillEffect effect) {
        switch (effect) {
            case SkillEffect.ConfigureRegistry e ->
                    Log.step("[%d] persist registry URL = %s", n,
                            e.url() == null || e.url().isBlank() ? "<no-op>" : e.url());
            case SkillEffect.EnsureGateway e ->
                    Log.step("[%d] ensure gateway running at %s", n,
                            e.gateway() == null ? "<none>" : e.gateway().baseUrl());
            case SkillEffect.CommitSkillsToStore e ->
                    Log.step("[%d] commit %d skill(s) to store", n, e.graph().resolved().size());
            case SkillEffect.RecordAuditPlan e ->
                    Log.step("[%d] append audit entry (verb=%s, %d action(s))", n,
                            e.verb(), e.plan().actions().size());
            case SkillEffect.RecordSourceProvenance e ->
                    Log.step("[%d] write source/<name>.json for %d skill(s)", n,
                            e.graph().resolved().size());
            case SkillEffect.OnboardSource e ->
                    Log.step("[%d] onboard missing source record for %s", n, e.skill().name());
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
            case SkillEffect.InstallTools e ->
                    Log.step("[%d] install runtime tools (uv/npm/docker/brew) for %d skill(s)",
                            n, e.skills().size());
            case SkillEffect.InstallCli e ->
                    Log.step("[%d] install CLI deps for %d skill(s)", n, e.skills().size());
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
            case SkillEffect.SyncGit e ->
                    Log.step("[%d] git-sync %s (installSource=%s, gitLatest=%s, merge=%s)",
                            n, e.skillName(), e.installSource(), e.gitLatest(), e.merge());
            case SkillEffect.AddSkillError e ->
                    Log.step("[%d] add error %s on %s: %s", n, e.kind(), e.skillName(), e.message());
            case SkillEffect.ClearSkillError e ->
                    Log.step("[%d] clear error %s on %s", n, e.kind(), e.skillName());
            case SkillEffect.ValidateAndClearError e ->
                    Log.step("[%d] validate-and-clear error %s on %s", n, e.kind(), e.skillName());
            case SkillEffect.StopGateway e ->
                    Log.step("[%d] stop gateway at %s", n,
                            e.gateway() == null ? "<none>" : e.gateway().baseUrl());
            case SkillEffect.ConfigureGateway e ->
                    Log.step("[%d] persist gateway URL = %s", n, e.url());
            case SkillEffect.SetupPackageManagerRuntime e ->
                    Log.step("[%d] setup PM runtime: %d tool(s)", n, e.tools().size());
            case SkillEffect.EnsureTool e ->
                    Log.step("[%d] ensure tool %s (missingOnPath=%s)", n,
                            e.tool().id(), e.missingOnPath());
            case SkillEffect.RunCliInstall e ->
                    Log.step("[%d] cli-install %s [%s] %s", n,
                            e.skillName(), e.dep().backend(), e.dep().name());
            case SkillEffect.RegisterMcpServer e ->
                    Log.step("[%d] register mcp server %s for %s",
                            n, e.dep().name(), e.skillName());
            case SkillEffect.RemoveSkillFromStore e ->
                    Log.step("[%d] remove %s from store", n, e.skillName());
            case SkillEffect.UnlinkAgentSkill e ->
                    Log.step("[%d] unlink %s from agent %s", n, e.skillName(), e.agentId());
            case SkillEffect.UnlinkAgentMcpEntry e ->
                    Log.step("[%d] remove virtual-mcp-gateway entry from agent %s", n, e.agentId());
            case SkillEffect.ScaffoldSkill e ->
                    Log.step("[%d] scaffold skill %s into %s", n, e.skillName(), e.dir());
            case SkillEffect.InitializePolicy e ->
                    Log.step("[%d] initialize policy.toml if missing", n);
            case SkillEffect.LoadOutstandingErrors e ->
                    Log.step("[%d] load outstanding errors", n);
        }
    }
}
