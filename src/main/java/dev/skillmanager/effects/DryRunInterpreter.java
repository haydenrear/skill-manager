package dev.skillmanager.effects;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Prints what would happen without touching the filesystem, gateway, or
 * registry. Returns OK receipts so the decoder produces a normal report
 * but no facts (the decoder can detect dry-run by checking
 * {@code receipt.facts().get("dryRun")}).
 *
 * <p>For {@link #runStaged}, callers supply a {@link SkillStore} so the
 * stage-2 builder can read pre-merge live state. The dry-run still does
 * not mutate anything; reads through the store are read-only.
 */
public final class DryRunInterpreter implements ProgramInterpreter {

    private final dev.skillmanager.store.SkillStore store;

    public DryRunInterpreter() { this(null); }

    public DryRunInterpreter(dev.skillmanager.store.SkillStore store) { this.store = store; }

    @Override
    public <R> R run(Program<R> program) {
        System.out.println();
        System.out.println("DRY RUN — no changes will be made");
        System.out.println("operation: " + program.operationId());
        List<EffectReceipt> receipts = describeProgram(program, 0);
        System.out.println();
        return program.decoder().decode(receipts);
    }

    @Override
    public <R> R runStaged(StagedProgram<R> staged) {
        System.out.println();
        System.out.println("DRY RUN — no changes will be made");
        System.out.println("operation: " + staged.operationId());
        // Stage 2 is built from a live EffectContext; in dry-run we still
        // build it so the user sees the shape, but it sees the *pre*-stage-1
        // store (stage 1's effects haven't actually run). For sync this
        // means stage 2 enumerates references known before the merge
        // would have landed — same blind spot dry-run already had.
        List<EffectReceipt> all = new ArrayList<>();
        all.addAll(describeProgram(staged.stage1(), 0));
        Program<?> stage2;
        if (store == null) {
            Log.warn("dry-run: no store supplied — stage 2 hidden in --dry-run; pass a store to DryRunInterpreter to enable");
            stage2 = null;
        } else {
            EffectContext bridgeCtx = new EffectContext(store, null);
            try {
                stage2 = staged.stage2().apply(bridgeCtx);
            } catch (Exception ex) {
                Log.warn("dry-run: stage 2 builder failed (%s) — printing stage 1 only", ex.getMessage());
                stage2 = null;
            }
        }
        if (stage2 != null) {
            all.addAll(describeProgram(stage2, all.size()));
        }
        System.out.println();
        return staged.decoder().decode(all);
    }

    private List<EffectReceipt> describeProgram(Program<?> program, int startIdx) {
        System.out.println("effects (" + program.effects().size() + "):");
        List<EffectReceipt> receipts = new ArrayList<>();
        int i = startIdx;
        for (SkillEffect effect : program.effects()) {
            describe(++i, effect);
            receipts.add(EffectReceipt.ok(effect, new ContextFact.DryRun()));
        }
        if (!program.alwaysAfter().isEmpty()) {
            System.out.println("alwaysAfter (" + program.alwaysAfter().size() + "):");
            for (SkillEffect effect : program.alwaysAfter()) {
                describe(++i, effect);
                // CleanupResolvedGraph removes the resolver's staged temp
                // dirs. The resolver ran before the dry-run gate (it has
                // to — the program is built from the graph), so without
                // actually executing the cleanup we'd leak temp dirs on
                // every dry-run.
                if (effect instanceof SkillEffect.CleanupResolvedGraph c) {
                    try { c.graph().cleanup(); } catch (Exception ignored) {}
                }
                receipts.add(EffectReceipt.ok(effect, new ContextFact.DryRun()));
            }
        }
        return receipts;
    }

    private static void describe(int n, SkillEffect effect) {
        switch (effect) {
            case SkillEffect.ConfigureRegistry e ->
                    Log.step("[%d] persist registry URL = %s", n,
                            e.url() == null || e.url().isBlank() ? "<no-op>" : e.url());
            case SkillEffect.EnsureGateway e ->
                    Log.step("[%d] ensure gateway running at %s", n,
                            e.gateway() == null ? "<none>" : e.gateway().baseUrl());
            case SkillEffect.CommitUnitsToStore e ->
                    Log.step("[%d] commit %d unit(s) to store", n, e.graph().resolved().size());
            case SkillEffect.RecordAuditPlan e ->
                    Log.step("[%d] append audit entry (verb=%s)", n, e.verb());
            case SkillEffect.RecordSourceProvenance e ->
                    Log.step("[%d] write installed/<name>.json for %d unit(s)", n,
                            e.graph().resolved().size());
            case SkillEffect.OnboardUnit e ->
                    Log.step("[%d] onboard missing installed-record for %s (%s)",
                            n, e.unit().name(), e.unit().kind());
            case SkillEffect.InstallTools e ->
                    Log.step("[%d] install runtime tools (uv/npm/docker/brew) for %d unit(s)",
                            n, e.units().size());
            case SkillEffect.InstallCli e ->
                    Log.step("[%d] install CLI deps for %d unit(s)", n, e.units().size());
            case SkillEffect.RegisterMcp e -> {
                Log.step("[%d] register MCP deps with %s", n,
                        e.gateway() == null ? "<none>" : e.gateway().baseUrl());
                for (AgentUnit u : e.units()) {
                    for (McpDependency d : u.mcpDependencies()) {
                        System.out.println("       - " + u.name() + " → " + d.name() + " (" + d.defaultScope() + ")");
                    }
                }
            }
            case SkillEffect.UnregisterMcpOrphan e ->
                    Log.step("[%d] unregister orphan MCP server %s", n, e.serverId());
            case SkillEffect.UnregisterMcpOrphans e ->
                    Log.step("[%d] diff snapshot vs live and unregister orphans", n);
            case SkillEffect.SyncAgents e ->
                    Log.step("[%d] sync agents over %d unit(s)", n, e.units().size());
            case SkillEffect.SyncGit e ->
                    Log.step("[%d] git-sync %s (kind=%s, installSource=%s, gitLatest=%s, merge=%s)",
                            n, e.unitName(), e.kind(), e.installSource(), e.gitLatest(), e.merge());
            case SkillEffect.AddUnitError e ->
                    Log.step("[%d] add error %s on %s: %s", n, e.kind(), e.unitName(), e.message());
            case SkillEffect.ClearUnitError e ->
                    Log.step("[%d] clear error %s on %s", n, e.kind(), e.unitName());
            case SkillEffect.ValidateAndClearError e ->
                    Log.step("[%d] validate-and-clear error %s on %s", n, e.kind(), e.unitName());
            case SkillEffect.StopGateway e ->
                    Log.step("[%d] stop gateway at %s", n,
                            e.gateway() == null ? "<none>" : e.gateway().baseUrl());
            case SkillEffect.ConfigureGateway e ->
                    Log.step("[%d] persist gateway URL = %s", n, e.url());
            case SkillEffect.SetupPackageManagerRuntime e ->
                    Log.step("[%d] setup PM runtime: %d tool(s)", n, e.tools().size());
            case SkillEffect.InstallPackageManager e ->
                    Log.step("[%d] install package manager %s@%s", n, e.pm().id,
                            e.version() == null ? e.pm().defaultVersion : e.version());
            case SkillEffect.EnsureTool e ->
                    Log.step("[%d] ensure tool %s (missingOnPath=%s)", n,
                            e.tool().id(), e.missingOnPath());
            case SkillEffect.RunCliInstall e ->
                    Log.step("[%d] cli-install %s [%s] %s", n,
                            e.unitName(), e.dep().backend(), e.dep().name());
            case SkillEffect.RegisterMcpServer e ->
                    Log.step("[%d] register mcp server %s for %s",
                            n, e.dep().name(), e.unitName());
            case SkillEffect.RemoveUnitFromStore e ->
                    Log.step("[%d] remove %s (%s) from store", n, e.unitName(), e.kind());
            case SkillEffect.UnlinkAgentUnit e ->
                    Log.step("[%d] unlink %s (%s) from agent %s", n, e.unitName(), e.kind(), e.agentId());
            case SkillEffect.UnlinkAgentMcpEntry e ->
                    Log.step("[%d] remove virtual-mcp-gateway entry from agent %s", n, e.agentId());
            case SkillEffect.ScaffoldSkill e ->
                    Log.step("[%d] scaffold skill %s into %s", n, e.skillName(), e.dir());
            case SkillEffect.InitializePolicy e ->
                    Log.step("[%d] initialize policy.toml if missing", n);
            case SkillEffect.LoadOutstandingErrors e ->
                    Log.step("[%d] load outstanding errors", n);
            case SkillEffect.SnapshotMcpDeps e ->
                    Log.step("[%d] snapshot pre-mutation MCP deps", n);
            case SkillEffect.RejectIfAlreadyInstalled e ->
                    Log.step("[%d] reject if %s already installed", n, e.unitName());
            case SkillEffect.BuildInstallPlan e ->
                    Log.step("[%d] build install plan over %d skill(s)",
                            n, e.graph().resolved().size());
            case SkillEffect.RunInstallPlan e ->
                    Log.step("[%d] expand + run install plan (gateway=%s)",
                            n, e.gateway() == null ? "<none>" : e.gateway().baseUrl());
            case SkillEffect.CleanupResolvedGraph e ->
                    Log.step("[%d] cleanup staged graph (%d skill(s))",
                            n, e.graph().resolved().size());
            case SkillEffect.PrintInstalledSummary e ->
                    Log.step("[%d] print INSTALLED summary for %d skill(s)",
                            n, e.graph().resolved().size());
            case SkillEffect.SyncFromLocalDir e ->
                    Log.step("[%d] sync %s from %s (merge=%s, yes=%s)",
                            n, e.skillName(), e.fromDir(), e.merge(), e.yes());
        }
    }
}
