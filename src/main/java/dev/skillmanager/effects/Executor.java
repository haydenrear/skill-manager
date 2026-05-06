package dev.skillmanager.effects;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional wrapper around {@link LiveInterpreter}: runs a {@link Program}
 * effect-by-effect through the interpreter's per-effect dispatch, but records a
 * {@link Compensation} for each successful effect into a {@link RollbackJournal}.
 * If any effect comes back {@link EffectStatus#FAILED}, the executor walks
 * the journal LIFO, applying every compensation against the same
 * {@link EffectContext}, leaving the store / gateway / agent dirs in their
 * pre-program state.
 *
 * <p>Ticket-09a lands the type + the compensation-derivation logic + the
 * apply-walk. Wiring the executor into Install / Upgrade / Uninstall
 * commands (replacing their inline rollback code) happens in 09b. Until
 * then, this class is exercised only by {@code CompensationLogicTest}.
 *
 * <h3>Status semantics</h3>
 * <ul>
 *   <li>{@link EffectStatus#FAILED} on any main effect → halt main loop,
 *       walk journal LIFO, return outcome with {@code rolledBack=true}.</li>
 *   <li>{@link EffectStatus#HALTED} → cooperative stop (e.g. {@code RejectIfAlreadyInstalled}).
 *       No rollback. Subsequent main effects are skipped, alwaysAfter still runs.</li>
 *   <li>{@link EffectStatus#PARTIAL} → continue. Compensation is recorded for the
 *       parts that did succeed (best-effort — not every PARTIAL receipt
 *       reports per-item granularity).</li>
 * </ul>
 *
 * <p>{@link Program#alwaysAfter()} effects (typically {@code CleanupResolvedGraph})
 * run after the main loop regardless of outcome, but are NOT compensated —
 * they're cleanup, not state-mutating work.
 *
 * <h3>{@code *_IfOrphan} semantics</h3>
 * The CLI / MCP / projector compensations consult the live store at apply
 * time. If a surviving installed unit still claims the dep, the
 * compensation is a no-op. This is what stops a partial install of A from
 * tearing down B's CLI dep just because B happened to install the same
 * dep first.
 */
public final class Executor {

    private final SkillStore store;
    private final GatewayConfig gateway;
    private final LiveInterpreter interpreter;

    public Executor(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
        this.interpreter = new LiveInterpreter(store, gateway);
    }

    public record Outcome<R>(R result, boolean rolledBack, List<Compensation> applied) {}

    public <R> Outcome<R> run(Program<R> program) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        try {
            return runWithContext(program, ctx);
        } finally {
            renderer.onComplete();
        }
    }

    public <R> Outcome<R> runWithContext(Program<R> program, EffectContext ctx) {
        RollbackJournal journal = new RollbackJournal();
        List<EffectReceipt> receipts = new ArrayList<>();
        boolean halted = false;
        boolean failed = false;
        for (SkillEffect effect : program.effects()) {
            if (halted || failed) {
                EffectReceipt skip = EffectReceipt.skipped(effect, failed ? "rolled back" : "halted");
                receipts.add(skip);
                ctx.renderer().onReceipt(skip);
                continue;
            }
            // Snapshot prior installed-record state before mutation effects so
            // RestoreInstalledUnit has the pre-image to roll back to. Done
            // pre-execution; no-op for effects that don't touch records.
            List<Compensation> preState = preStateCompensations(effect, ctx);

            EffectReceipt r = interpreter.runOne(effect, ctx);
            receipts.add(r);

            if (r.status() == EffectStatus.HALTED) {
                halted = true;
                continue;
            }
            if (r.status() == EffectStatus.FAILED) {
                failed = true;
                continue;
            }
            // OK or PARTIAL — record what succeeded.
            journal.recordAll(preState);
            journal.recordAll(compensationsFor(effect, r));
        }

        // alwaysAfter always runs (cleanup); never compensated.
        for (SkillEffect effect : program.alwaysAfter()) {
            EffectReceipt r = interpreter.runOne(effect, ctx);
            receipts.add(r);
        }

        List<Compensation> applied = List.of();
        if (failed) {
            applied = walkBack(journal, ctx);
        } else {
            journal.clear();
        }

        R result = program.decoder().decode(receipts);
        return new Outcome<>(result, failed, applied);
    }

    // ============================================================ derivation

    /**
     * Compensations that capture pre-execution state — needed so a successful
     * mutation can be reversed to its prior value (vs. just deleted).
     */
    static List<Compensation> preStateCompensations(SkillEffect effect, EffectContext ctx) {
        return switch (effect) {
            case SkillEffect.AddUnitError e -> snapshotInstalled(e.unitName(), ctx);
            case SkillEffect.ClearUnitError e -> snapshotInstalled(e.unitName(), ctx);
            case SkillEffect.OnboardUnit e -> {
                // OnboardUnit only writes if absent — if there's an existing
                // record, no rollback shape (RestoreInstalledUnit) needed.
                yield ctx.source(e.unit().name()).isPresent()
                        ? List.of()
                        : List.of(new Compensation.DeleteInstalledUnit(e.unit().name()));
            }
            default -> List.of();
        };
    }

    private static List<Compensation> snapshotInstalled(String name, EffectContext ctx) {
        return ctx.source(name)
                .map(prev -> List.<Compensation>of(new Compensation.RestoreInstalledUnit(name, prev)))
                .orElseGet(() -> List.of(new Compensation.DeleteInstalledUnit(name)));
    }

    /**
     * Post-execution compensations — what we need to walk back to undo what
     * the effect just did. Pattern-matches on the effect record + the
     * receipt facts (which carry the per-item granularity for batched
     * effects like CommitUnitsToStore).
     */
    static List<Compensation> compensationsFor(SkillEffect effect, EffectReceipt receipt) {
        return switch (effect) {
            case SkillEffect.CommitUnitsToStore c -> {
                // One DeleteUnitDir per resolved unit that emitted a
                // SkillCommitted fact. Rollback is asymmetric to the
                // forward path's "touched" tracking: the handler already
                // self-rolls-back on mid-copy failure, so by the time we
                // see SkillCommitted facts the dir is fully copied.
                List<Compensation> out = new ArrayList<>();
                for (var resolved : c.graph().resolved()) {
                    boolean committed = receipt.facts().stream()
                            .anyMatch(f -> f instanceof ContextFact.SkillCommitted s
                                    && s.name().equals(resolved.name()));
                    if (committed) {
                        out.add(new Compensation.DeleteUnitDir(resolved.name(), resolved.unit().kind()));
                    }
                }
                yield out;
            }
            case SkillEffect.RecordSourceProvenance p -> {
                // Provenance writes one record per resolved unit. Rollback
                // is DeleteInstalledUnit per unit — provenance has no prior
                // record to restore (it runs after CommitUnitsToStore on a
                // fresh install).
                List<Compensation> out = new ArrayList<>();
                for (var resolved : p.graph().resolved()) {
                    out.add(new Compensation.DeleteInstalledUnit(resolved.name()));
                }
                yield out;
            }
            case SkillEffect.RunCliInstall r ->
                    List.of(new Compensation.UninstallCliIfOrphan(r.unitName(), r.dep()));
            case SkillEffect.RegisterMcpServer r ->
                    List.of(new Compensation.UnregisterMcpIfOrphan(r.unitName(), r.dep(), r.gateway()));
            case SkillEffect.RegisterMcp batch -> {
                // RegisterMcp registers every dep on every supplied unit.
                // Rollback fans out to one UnregisterMcpIfOrphan per
                // (unit, dep) the receipt confirms registered.
                List<Compensation> out = new ArrayList<>();
                for (AgentUnit u : batch.units()) {
                    for (McpDependency d : u.mcpDependencies()) {
                        boolean registered = receipt.facts().stream()
                                .anyMatch(f -> f instanceof ContextFact.McpServerRegistered m
                                        && u.name().equals(m.skillName())
                                        && m.result().serverId().equals(d.name()));
                        if (registered) out.add(new Compensation.UnregisterMcpIfOrphan(u.name(), d, batch.gateway()));
                    }
                }
                yield out;
            }
            case SkillEffect.SyncAgents sa -> {
                // One UnprojectIfOrphan per unit for which any AgentSkillSynced
                // fact landed (per-agent fan-out happens inside the applier).
                List<Compensation> out = new ArrayList<>();
                java.util.Set<String> projected = new java.util.LinkedHashSet<>();
                for (var f : receipt.facts()) {
                    if (f instanceof ContextFact.AgentSkillSynced ass) projected.add(ass.skillName());
                }
                for (AgentUnit u : sa.units()) {
                    if (projected.contains(u.name())) {
                        out.add(new Compensation.UnprojectIfOrphan(u.name(), u.kind()));
                    }
                }
                yield out;
            }
            default -> List.of();
        };
    }

    // ================================================================ apply

    private List<Compensation> walkBack(RollbackJournal journal, EffectContext ctx) {
        List<Compensation> applied = new ArrayList<>();
        for (Compensation c : journal.pendingLifo()) {
            try {
                applyCompensation(c, ctx);
                applied.add(c);
            } catch (Exception ex) {
                Log.warn("rollback: %s failed — %s", c.getClass().getSimpleName(), ex.getMessage());
            }
        }
        journal.clear();
        return applied;
    }

    private void applyCompensation(Compensation c, EffectContext ctx) throws IOException {
        switch (c) {
            case Compensation.DeleteUnitDir d -> {
                Path dir = ctx.store().unitDir(d.unitName(), d.kind());
                if (Files.exists(dir)) {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(dir);
                }
            }
            case Compensation.RestoreInstalledUnit r -> {
                ctx.writeSource(r.previous());
            }
            case Compensation.DeleteInstalledUnit d -> {
                try { ctx.sourceStore().delete(d.unitName()); } catch (Exception ignored) {}
                ctx.invalidate();
            }
            case Compensation.UninstallCliIfOrphan u -> {
                if (!isClaimedByOtherUnit(u.unitName(), u.dep().name())) {
                    Log.info("rollback: uninstall CLI dep %s (claimed only by rolled-back %s)",
                            u.dep().name(), u.unitName());
                    // CliInstallRecorder doesn't expose a single-dep uninstall yet;
                    // just drop the lock entry. Bytes on disk linger until the next
                    // install/sync pass — acceptable for 09a (compensation infra
                    // skeleton). 09b's command wiring + tests pin the contract.
                    try {
                        var lock = dev.skillmanager.lock.CliLock.load(ctx.store());
                        lock.remove(u.dep().backend(),
                                dev.skillmanager.lock.RequestedVersion.of(u.dep()).tool());
                        lock.save(ctx.store());
                    } catch (Exception ignored) {}
                }
            }
            case Compensation.UnregisterMcpIfOrphan u -> {
                if (!isMcpClaimedByOtherUnit(u.unitName(), u.dep().name())) {
                    GatewayClient client = new GatewayClient(u.gateway());
                    if (client.ping()) {
                        try { client.unregister(u.dep().name()); } catch (Exception ignored) {}
                    }
                }
            }
            case Compensation.UnprojectIfOrphan u -> {
                // Per-agent fan-out: remove this unit's projection from every
                // known agent. Mirrors what UnlinkAgentUnit does in the forward
                // direction; ticket 11 swaps both arms for Projector.remove.
                for (Agent agent : Agent.all()) {
                    Path base = u.kind() == UnitKind.PLUGIN ? agent.pluginsDir() : agent.skillsDir();
                    Path link = base.resolve(u.unitName());
                    if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(link)) {
                        try { dev.skillmanager.shared.util.Fs.deleteRecursive(link); }
                        catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    private boolean isClaimedByOtherUnit(String rolledBackUnit, String depName) {
        try {
            for (Skill s : store.listInstalled()) {
                if (s.name().equals(rolledBackUnit)) continue;
                for (var d : s.cliDependencies()) {
                    if (d.name().equals(depName)) return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    private boolean isMcpClaimedByOtherUnit(String rolledBackUnit, String serverName) {
        try {
            for (Skill s : store.listInstalled()) {
                if (s.name().equals(rolledBackUnit)) continue;
                for (var d : s.mcpDependencies()) {
                    if (d.name().equals(serverName)) return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }
}
