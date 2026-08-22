package dev.skillmanager.effects;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.lock.CliDependencyCleaner;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.HomeLock;
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
    private final boolean json;
    private java.util.function.IntPredicate faultInjector;
    private java.time.Duration homeLockTimeout = HomeLock.DEFAULT_TIMEOUT;

    public Executor(SkillStore store, GatewayConfig gateway) {
        this(store, gateway, false);
    }

    public Executor(SkillStore store, GatewayConfig gateway, boolean json) {
        this.store = store;
        this.gateway = gateway;
        this.json = json;
        this.interpreter = new LiveInterpreter(store, gateway, json);
    }

    /**
     * Test-only seam for {@code FailureInjectionSweepTest}. Forces a FAILED
     * receipt at any main-effect step whose 0-based index satisfies the
     * predicate, before the underlying handler runs. Returning the same
     * Executor allows fluent chaining at test setup. {@code null} disables
     * injection (the production default).
     *
     * <p>Production code should never call this — it bypasses the
     * interpreter dispatch entirely, so the receipt's failure message is
     * synthetic.
     */
    public Executor withFaultInjection(java.util.function.IntPredicate failAtStep) {
        this.faultInjector = failAtStep;
        return this;
    }

    public record Outcome<R>(R result, boolean rolledBack, List<Compensation> applied) {}

    public <R> Outcome<R> run(Program<R> program) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway, json);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        try {
            // The home lock is taken by runWithContext, which every caller of
            // this method reaches and which is public in its own right.
            return runWithContext(program, ctx);
        } finally {
            renderer.onComplete();
        }
    }

    /**
     * Exclude every other writer of this home for the length of one program.
     *
     * <h3>Why the lock is here and not in five commands</h3>
     *
     * <p>Nothing on the resolve/install path held one. Two resolves into the
     * same home interleaved staging and commit freely: each deletes the
     * destination unit directory and copies its own tree in, so the loser's
     * half-written unit and the winner's are the same bytes on disk, and each
     * one's rollback journal describes a home the other has already moved
     * past. That is the second half of #186, and it is per-home isolation —
     * the same unit of exclusion {@link HomeLock} already serves for
     * {@code home sync}.
     *
     * <p>Install, upgrade, sync, onboard, bind, uninstall and project-resolve
     * all reach the store through this class, so taking it here is one
     * spelling instead of six that can drift apart. {@link HomeLock} is
     * re-entrant per thread, so a program that nests another (a child-home
     * harness install runs an inner {@code Executor} against the parent store)
     * does not deadlock on itself.
     *
     * <p>Read-only paths do not come through here — a dry run is a
     * {@link DryRunInterpreter}, never an {@code Executor} — so this never
     * turns a report into a write.
     *
     * @throws java.io.UncheckedIOException when the home stays contended past
     *         the timeout. A refusal naming the home, in preference to waiting
     *         forever behind a peer that may have crashed;
     *         {@code SkillManagerCli.printFailure} renders it as the message
     *         it is rather than as a stack trace.
     */
    private HomeLock homeLock(String operationId) {
        try {
            return HomeLock.acquire(store.root(),
                    operationId == null ? "program" : operationId, homeLockTimeout);
        } catch (IOException io) {
            throw new java.io.UncheckedIOException(io.getMessage(), io);
        }
    }

    /**
     * How long a program waits for a peer holding this home before refusing.
     *
     * <p>Package-private seam for {@code CommitPreImageRestoreTest}, which has
     * to observe the refusal edge and cannot spend {@link
     * HomeLock#DEFAULT_TIMEOUT} doing it. Deliberately not public: no command
     * has any business shortening the wait, and one that did would turn an
     * ordinary queue behind a peer into a spurious failure.
     */
    Executor withHomeLockTimeout(java.time.Duration timeout) {
        this.homeLockTimeout = timeout;
        return this;
    }

    /**
     * Run a {@link StagedProgram}: stage 1 first, then stage 2 built from
     * the post-stage-1 ctx. Both stages contribute to a single journal so
     * a stage-2 failure walks back stage-1 mutations too.
     *
     * <p>Stage 2 is skipped when stage 1 ends in either {@link
     * EffectStatus#FAILED} (rollback fires) OR {@link EffectStatus#HALTED}
     * (cooperative stop — e.g. a policy gate rejection, a typed-exit-code
     * resolve halt, or a "remove first" precondition). Halt is meant to
     * apply across the whole multi-stage program, not just the stage that
     * raised it — running stage 2 after a halt would let the post-update
     * tail fire on top of a half-built state (e.g. resolve halted before
     * commit, so there's nothing for SyncAgents to project).
     */
    public <R> Outcome<R> runStaged(StagedProgram<R> staged) {
        ConsoleProgramRenderer renderer = new ConsoleProgramRenderer(store, gateway, json);
        EffectContext ctx = new EffectContext(store, gateway, renderer);
        try (HomeLock ignored = homeLock(staged.operationId())) {
            RollbackJournal journal = new RollbackJournal();
            List<EffectReceipt> all = new ArrayList<>();

            StageOutcome s1 = runStage(staged.stage1(), ctx, journal);
            all.addAll(s1.receipts);

            boolean failed = s1.failed;
            boolean halted = s1.halted;
            if (!failed && !halted) {
                Program<?> stage2 = staged.stage2().apply(ctx);
                StageOutcome s2 = runStage(stage2, ctx, journal);
                all.addAll(s2.receipts);
                failed = s2.failed;
            }

            List<Compensation> applied = List.of();
            if (failed) {
                applied = walkBack(journal, ctx);
            } else {
                commit(journal);
            }
            R result = staged.decoder().decode(all);
            return new Outcome<>(result, failed, applied);
        } finally {
            renderer.onComplete();
        }
    }

    public <R> Outcome<R> runWithContext(Program<R> program, EffectContext ctx) {
        try (HomeLock ignored = homeLock(program.operationId())) {
            RollbackJournal journal = new RollbackJournal();
            StageOutcome s = runStage(program, ctx, journal);
            List<Compensation> applied = List.of();
            if (s.failed) {
                applied = walkBack(journal, ctx);
            } else {
                commit(journal);
            }
            R result = program.decoder().decode(s.receipts);
            return new Outcome<>(result, s.failed, applied);
        }
    }

    /**
     * Drop the journal after a program that is not going to be walked back.
     *
     * <h3>Why this is not just {@code journal.clear()}</h3>
     *
     * <p>Most compensations are values — a name, a record, a path — and
     * forgetting one costs nothing. {@link Compensation.RestoreUnitDir} is not:
     * it holds a live {@link dev.skillmanager.source.MaterializationEscrow}
     * over real bytes parked under {@code <home>/cache/}. Dropping the
     * reference without telling the escrow would leave one held tree per
     * resolve in the home, growing without bound, invisible to {@code list} and
     * to {@code home verify} — the exact residue the review of #231 caught the
     * first version of that class leaving behind, one directory further down.
     *
     * <p>Called on the success edge AND the HALTED edge: a cooperative stop is
     * not a rollback, so bytes a commit superseded before the halt are equally
     * not coming back.
     */
    private void commit(RollbackJournal journal) {
        for (Compensation c : journal.pendingLifo()) {
            if (c instanceof Compensation.RestoreUnitDir r) r.escrow().discard();
        }
        journal.clear();
    }

    /**
     * Drive one program's main effects + alwaysAfter cleanup, recording
     * compensations as effects succeed. Stops the main loop on the first
     * FAILED receipt. Does not walk the journal — the caller decides
     * whether a stage 2 should still run, and only walks back at the end.
     */
    private record StageOutcome(List<EffectReceipt> receipts, boolean failed, boolean halted) {}

    /**
     * Drive one program's effects, recording compensations as effects
     * complete (regardless of status). Status FAILED no longer halts —
     * each effect's {@link Continuation} drives the halt decision. The
     * compensation journal still walks back any FAILED-status receipts
     * at the end of the stage (rollback is decoupled from halt).
     *
     * <p>Per-effect failures that should stop downstream effects opt-in
     * via {@code continuationOnFail() = HALT} (or by emitting an
     * explicit {@code failedAndHalt} receipt). Independent fan-out
     * effects (per-target sync, per-server MCP register) leave the
     * default {@code CONTINUE} so a single per-item failure doesn't
     * sink the rest of the batch.
     */
    private StageOutcome runStage(Program<?> program, EffectContext ctx, RollbackJournal journal) {
        List<EffectReceipt> receipts = new ArrayList<>();
        boolean halted = false;
        boolean failed = false;
        int idx = -1;
        for (SkillEffect effect : program.effects()) {
            idx++;
            if (halted) {
                EffectReceipt skip = EffectReceipt.skipped(effect, "halted");
                receipts.add(skip);
                ctx.renderer().onReceipt(skip);
                continue;
            }
            // Snapshot prior installed-record state before mutation effects so
            // RestoreInstalledUnit has the pre-image to roll back to. Done
            // pre-execution; no-op for effects that don't touch records.
            List<Compensation> preState = preStateCompensations(effect, ctx);

            EffectReceipt r;
            if (faultInjector != null && faultInjector.test(idx)) {
                r = EffectReceipt.failedAndHalt(effect, "fault-injected at step " + idx);
                ctx.renderer().onReceipt(r);
            } else {
                r = interpreter.runOne(effect, ctx);
            }
            receipts.add(r);

            // Track rollback intent. Any FAILED receipt triggers the
            // walk-back at end of stage — independent of whether the
            // program halts here.
            if (r.status() == EffectStatus.FAILED) failed = true;

            // Record compensations for whatever the effect did before
            // failing — handlers that emit per-item facts (e.g.
            // CommitUnitsToStore's mid-copy failure) still surface what
            // they touched, so rollback walks every successful item back.
            journal.recordAll(preState);
            journal.recordAll(compensationsFor(effect, r, ctx));

            // Halt decision is now exclusively the receipt's continuation.
            if (r.continuation() == Continuation.HALT) halted = true;
        }
        // alwaysAfter always runs (cleanup); never compensated.
        for (SkillEffect effect : program.alwaysAfter()) {
            EffectReceipt r = interpreter.runOne(effect, ctx);
            receipts.add(r);
        }
        return new StageOutcome(receipts, failed, halted);
    }

    // ============================================================ derivation

    /**
     * Compensations that capture pre-execution state — needed so a successful
     * mutation can be reversed to its prior value (vs. just deleted).
     *
     * <p><b>Exhaustive on purpose.</b> Every {@link SkillEffect} permit
     * is enumerated explicitly — most yield {@code List.of()} (no
     * pre-state needed). The compiler is the contract: when a new
     * effect lands in the sealed permits clause, this switch fails to
     * compile until someone consciously decides whether the effect
     * needs a pre-state snapshot. A {@code default ->} arm would
     * silently absorb new effects as "no compensation" and turn
     * rollback gaps into latent bugs that only surface when an effect
     * fails downstream.
     */
    static List<Compensation> preStateCompensations(SkillEffect effect, EffectContext ctx) {
        return switch (effect) {
            // ---- effects that need pre-state snapshotting ----
            // The commit is delete-destination-then-copy. For a destination
            // that already holds a unit, that delete is the only record of
            // what was there, so the pre-image has to be taken here or it does
            // not exist at all. See escrowOverwrittenUnits.
            case SkillEffect.CommitUnitsToStore e -> escrowOverwrittenUnits(e, ctx);
            case SkillEffect.AddUnitError e -> snapshotInstalled(e.unitName(), ctx);
            case SkillEffect.ClearUnitError e -> snapshotInstalled(e.unitName(), ctx);
            case SkillEffect.OnboardUnit e -> {
                // OnboardUnit only writes if absent — if there's an existing
                // record, no rollback shape (RestoreInstalledUnit) needed.
                yield ctx.source(e.unit().name()).isPresent()
                        ? List.of()
                        : List.of(new Compensation.DeleteInstalledUnit(e.unit().name()));
            }
            case SkillEffect.UpdateUnitsLock e -> {
                // Capture the on-disk lock pre-image so the compensation can
                // restore byte-for-byte. Reading at preState (not at apply
                // time) is what makes the rollback atomic — by the time the
                // walk runs, the file has been overwritten.
                try {
                    dev.skillmanager.lock.UnitsLock prev =
                            dev.skillmanager.lock.UnitsLockReader.read(e.path());
                    yield List.of(new Compensation.RestoreUnitsLock(prev, e.path()));
                } catch (java.io.IOException io) {
                    Log.warn("preState UpdateUnitsLock: could not snapshot %s — %s",
                            e.path(), io.getMessage());
                    yield List.of();
                }
            }
            // ---- effects that don't need pre-state snapshotting ----
            // Either pure (no mutation), idempotent over the existing
            // state (next run produces the same result), or the
            // post-state compensation in {@link #compensationsFor}
            // already captures enough to walk back without a snapshot.
            case SkillEffect.ConfigureRegistry e -> List.of();
            case SkillEffect.EnsureGateway e -> List.of();
            case SkillEffect.StopGateway e -> List.of();
            case SkillEffect.ConfigureGateway e -> List.of();
            case SkillEffect.SetupPackageManagerRuntime e -> List.of();
            case SkillEffect.InstallPackageManager e -> List.of();
            case SkillEffect.SnapshotMcpDeps e -> List.of();
            case SkillEffect.RejectIfAlreadyInstalled e -> List.of();
            case SkillEffect.RejectIfTopLevelInstalled e -> List.of();
            case SkillEffect.CheckInstallPolicyGate e -> List.of();
            // The BuildResolveGraphFrom* family stages temp dirs (cleaned
            // up by CleanupResolvedGraph alwaysAfter) and writes to
            // ctx.resolvedGraph() (per-execution, no on-disk rollback).
            case SkillEffect.BuildResolveGraphFromSource e -> List.of();
            case SkillEffect.BuildResolveGraphFromBundledSkills e -> List.of();
            case SkillEffect.BuildResolveGraphFromUnmetReferences e -> List.of();
            case SkillEffect.ReportUnitReadProblems e -> List.of();
            case SkillEffect.ValidateMarkdownImports e -> List.of();
            case SkillEffect.BuildInstallPlan e -> List.of();
            case SkillEffect.RunInstallPlan e -> List.of();
            case SkillEffect.CleanupResolvedGraph e -> List.of();
            case SkillEffect.PrintInstalledSummary e -> List.of();
            case SkillEffect.SyncFromLocalDir e -> List.of();
            case SkillEffect.RecordAuditPlan e -> List.of();
            case SkillEffect.RecordSourceProvenance e -> List.of();
            case SkillEffect.EnsureTool e -> List.of();
            case SkillEffect.RunCliInstall e -> List.of();
            case SkillEffect.RegisterMcpServer e -> List.of();
            case SkillEffect.UnregisterMcpOrphan e -> List.of();
            case SkillEffect.UnregisterMcpOrphans e -> List.of();
            case SkillEffect.SyncAgents e -> List.of();
            case SkillEffect.RefreshHarnessPlugins e -> List.of();
            case SkillEffect.SyncGit e -> List.of();
            case SkillEffect.RemoveUnitFromStore e -> List.of();
            case SkillEffect.PruneCliIfOrphan e -> List.of();
            case SkillEffect.RecordArtifactLedger e -> List.of();
            case SkillEffect.PruneOrphanArtifacts e -> List.of();
            case SkillEffect.UnlinkAgentUnit e -> List.of();
            case SkillEffect.UnlinkAgentMcpEntry e -> List.of();
            case SkillEffect.ScaffoldSkill e -> List.of();
            case SkillEffect.ScaffoldPlugin e -> List.of();
            case SkillEffect.InitializePolicy e -> List.of();
            case SkillEffect.LoadOutstandingErrors e -> List.of();
            case SkillEffect.ValidateAndClearError e -> List.of();
            case SkillEffect.InstallTools e -> List.of();
            case SkillEffect.InstallCli e -> List.of();
            case SkillEffect.RegisterMcp e -> List.of();
            case SkillEffect.SyncClaimingProjects e -> List.of();
            // ---- bindings (ticket 49) ----
            case SkillEffect.CreateBinding e -> snapshotLedger(e.binding().unitName(), ctx);
            case SkillEffect.RemoveBinding e -> snapshotLedger(e.unitName(), ctx);
            // MaterializeProjection's compensation is captured post-state
            // (ReverseProjection in compensationsFor). UnmaterializeProjection
            // is a teardown — no compensation.
            case SkillEffect.MaterializeProjection e -> List.of();
            case SkillEffect.UnmaterializeProjection e -> List.of();
            // SyncDocRepo is forward-only: it rewrites tracked-copy
            // bytes based on the four-state matrix. No pre-state
            // snapshot — the dest bytes themselves are the only
            // rollback target and we don't capture them.
            case SkillEffect.SyncDocRepo e -> List.of();
            // SyncHarness is similarly forward-only — it reconciles
            // ledger rows against a fresh plan. The internal apply path
            // is idempotent; capturing pre-state ledger snapshots for
            // every unit it might touch is not worth the cost.
            case SkillEffect.SyncHarness e -> List.of();
            // ---- ARTI-06 build ----
            // A gate reads and prompts. A rebuild re-derives an artifact this
            // home already declares, so there is no prior state to restore —
            // and the compensation RunCliInstall gets would mean UNINSTALLING
            // what this run just repaired. See RebuildCliArtifact's javadoc.
            case SkillEffect.CheckBuildPolicyGate e -> List.of();
            case SkillEffect.RebuildCliArtifact e -> List.of();
        };
    }

    /**
     * Move aside the bytes {@link SkillEffect.CommitUnitsToStore} is about to
     * overwrite, and hand back the compensation that puts them back.
     *
     * <h3>The defect (#186)</h3>
     *
     * <p>This arm read {@code List.of()} — an EMPTY pre-state compensation, in
     * an exhaustive switch, for the one effect that overwrites bytes already in
     * the home. The commit deletes the destination and copies the new tree in;
     * on a downstream failure the walk-back deleted what it found and restored
     * nothing, because nothing had been captured. A publish or resolve that
     * failed partway into a NON-EMPTY home left that unit absent rather than as
     * it was. Severity scaled with the tier: the root home has the most
     * installed units and the least coverage.
     *
     * <h3>Why a move and not a copy</h3>
     *
     * <p>{@code <home>/cache/} and {@code <home>/skills/} are the same
     * filesystem, so {@link java.nio.file.Files#move} is a rename: constant
     * time, and no second copy of a unit that can be hundreds of megabytes on a
     * disk that is routinely near full. It also makes the commit's own
     * {@code deleteRecursive(dst)} a no-op, so the destructive step stops being
     * destructive rather than being raced.
     *
     * <p>This method has a SIDE EFFECT, which the rest of
     * {@link #preStateCompensations} does not: it moves bytes. That is
     * inherent. A pre-image of a directory cannot be a value read out of the
     * store the way an installed-record or a lock file can, and taking it after
     * the commit is taking it after the delete. The call site already runs
     * immediately before the effect, which is exactly when the move must
     * happen.
     *
     * <h3>What is deliberately not covered</h3>
     *
     * <p>Only the destination of a commit. Every other effect's compensation is
     * out of scope for #186 and stays as it was.
     */
    private static List<Compensation> escrowOverwrittenUnits(
            SkillEffect.CommitUnitsToStore e, EffectContext ctx) {
        dev.skillmanager.resolve.ResolvedGraph graph = e.graph() != null
                ? e.graph()
                : (ctx == null ? null : ctx.resolvedGraph().orElse(null));
        if (graph == null || ctx == null) return List.of();

        List<Compensation> out = new ArrayList<>();
        for (var resolved : graph.resolved()) {
            UnitKind kind = resolved.unit().kind();
            Path dst = ctx.store().unitDir(resolved.name(), kind);
            if (!Files.exists(dst)) continue;              // nothing there to lose
            Path parent = dst.getParent();
            if (parent == null) continue;
            dev.skillmanager.source.MaterializationEscrow escrow =
                    dev.skillmanager.source.MaterializationEscrow.liftPaths(
                            parent, ctx.store().root(),
                            List.of(dst.getFileName().toString()), false);
            if (escrow.isEmpty()) {
                // The lift warned already. Say which unit is now unprotected:
                // "could not set aside <some directory>" does not tell an
                // operator which of their installed units a rollback would now
                // delete outright.
                Log.warn("no pre-image taken for %s — if this run rolls back, the version "
                        + "already installed at %s cannot be restored", resolved.name(), dst);
                continue;
            }
            out.add(new Compensation.RestoreUnitDir(resolved.name(), kind, escrow));
        }
        return out;
    }

    private static List<Compensation> snapshotLedger(String unitName, EffectContext ctx) {
        dev.skillmanager.bindings.ProjectionLedger prev = ctx.bindingStore().read(unitName);
        return List.of(new Compensation.RestoreProjectionLedger(unitName, prev));
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
     *
     * <p><b>Exhaustive on purpose</b> — same rationale as
     * {@link #preStateCompensations}: every permit listed explicitly
     * so a new effect lights up a compile error here until someone
     * decides whether it needs a rollback shape. The {@code default}
     * shortcut would silently treat new effects as "no rollback,"
     * which is the kind of bug that only manifests when something
     * downstream of the new effect fails and the install ends up
     * half-applied.
     */
    static List<Compensation> compensationsFor(SkillEffect effect, EffectReceipt receipt) {
        return compensationsFor(effect, receipt, null);
    }

    /**
     * Same as {@link #compensationsFor(SkillEffect, EffectReceipt)} but with
     * an {@link EffectContext} for the graph fallback. When the effect's
     * own graph field is null (the new ctx-based plumbing path), the
     * compensation derivation reads from {@link EffectContext#resolvedGraph()}
     * instead. Tests still use the 2-arg overload — they pass the graph
     * via the effect record, so the ctx fallback never fires for them.
     */
    static List<Compensation> compensationsFor(SkillEffect effect, EffectReceipt receipt, EffectContext ctx) {
        return switch (effect) {
            // ---- effects that produce rollback shapes ----
            case SkillEffect.CommitUnitsToStore c -> {
                // One DeleteUnitDir per resolved unit that emitted a
                // SkillCommitted fact. Rollback is asymmetric to the
                // forward path's "touched" tracking: the handler already
                // self-rolls-back on mid-copy failure, so by the time we
                // see SkillCommitted facts the dir is fully copied.
                dev.skillmanager.resolve.ResolvedGraph graph = c.graph() != null
                        ? c.graph()
                        : (ctx == null ? null : ctx.resolvedGraph().orElse(null));
                if (graph == null) yield List.of();
                List<Compensation> out = new ArrayList<>();
                for (var resolved : graph.resolved()) {
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
                dev.skillmanager.resolve.ResolvedGraph graph = p.graph() != null
                        ? p.graph()
                        : (ctx == null ? null : ctx.resolvedGraph().orElse(null));
                if (graph == null) yield List.of();
                List<Compensation> out = new ArrayList<>();
                for (var resolved : graph.resolved()) {
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
            // ---- effects with no post-state rollback shape ----
            // Each enumerated explicitly so adding a new effect that
            // mutates state lights up a compile error here. Reasons
            // for "no rollback" vary:
            //   - pure read / probe (BuildInstallPlan, EnsureGateway)
            //   - idempotent / re-runnable (SyncAgents writes are safe
            //     to repeat — but SyncAgents IS handled above for
            //     UnprojectIfOrphan; this comment covers the others)
            //   - mutation already gated on idempotence elsewhere
            //     (RefreshHarnessPlugins regenerates from store state
            //     so the next run lands the same result)
            //   - rollback handled by sub-effects (RunInstallPlan
            //     expands into RunCliInstall / RegisterMcpServer, each
            //     of which has its own rollback above)
            //   - mutation is the rollback (RemoveUnitFromStore can't
            //     un-remove without the bytes; relies on
            //     CommitUnitsToStore's prior compensation chain)
            //   - error-state mutation (AddUnitError / ClearUnitError)
            //     gets its rollback from preStateCompensations'
            //     RestoreInstalledUnit
            //   - lock mutation gets rollback from preStateCompensations'
            //     RestoreUnitsLock
            case SkillEffect.ConfigureRegistry e -> List.of();
            case SkillEffect.EnsureGateway e -> List.of();
            case SkillEffect.StopGateway e -> List.of();
            case SkillEffect.ConfigureGateway e -> List.of();
            case SkillEffect.SetupPackageManagerRuntime e -> List.of();
            case SkillEffect.InstallPackageManager e -> List.of();
            case SkillEffect.SnapshotMcpDeps e -> List.of();
            case SkillEffect.RejectIfAlreadyInstalled e -> List.of();
            case SkillEffect.RejectIfTopLevelInstalled e -> List.of();
            case SkillEffect.CheckInstallPolicyGate e -> List.of();
            // The BuildResolveGraphFrom* family — no post-state
            // rollback needed (temp dirs cleaned by CleanupResolvedGraph
            // alwaysAfter; ctx slot is per-execution).
            case SkillEffect.BuildResolveGraphFromSource e -> List.of();
            case SkillEffect.BuildResolveGraphFromBundledSkills e -> List.of();
            case SkillEffect.BuildResolveGraphFromUnmetReferences e -> List.of();
            case SkillEffect.ReportUnitReadProblems e -> List.of();
            case SkillEffect.ValidateMarkdownImports e -> List.of();
            case SkillEffect.BuildInstallPlan e -> List.of();
            case SkillEffect.RunInstallPlan e -> List.of();
            case SkillEffect.CleanupResolvedGraph e -> List.of();
            case SkillEffect.PrintInstalledSummary e -> List.of();
            case SkillEffect.SyncFromLocalDir e -> List.of();
            case SkillEffect.RecordAuditPlan e -> List.of();
            case SkillEffect.OnboardUnit e -> List.of();
            case SkillEffect.EnsureTool e -> List.of();
            case SkillEffect.UnregisterMcpOrphan e -> List.of();
            case SkillEffect.UnregisterMcpOrphans e -> List.of();
            case SkillEffect.RefreshHarnessPlugins e -> List.of();
            case SkillEffect.SyncGit e -> List.of();
            case SkillEffect.RemoveUnitFromStore e -> List.of();
            case SkillEffect.PruneCliIfOrphan e -> List.of();
            case SkillEffect.RecordArtifactLedger e -> List.of();
            case SkillEffect.PruneOrphanArtifacts e -> List.of();
            case SkillEffect.UnlinkAgentUnit e -> List.of();
            case SkillEffect.UnlinkAgentMcpEntry e -> List.of();
            case SkillEffect.ScaffoldSkill e -> List.of();
            case SkillEffect.ScaffoldPlugin e -> List.of();
            case SkillEffect.InitializePolicy e -> List.of();
            case SkillEffect.LoadOutstandingErrors e -> List.of();
            case SkillEffect.AddUnitError e -> List.of();
            case SkillEffect.ClearUnitError e -> List.of();
            case SkillEffect.ValidateAndClearError e -> List.of();
            case SkillEffect.InstallTools e -> List.of();
            case SkillEffect.InstallCli e -> List.of();
            case SkillEffect.UpdateUnitsLock e -> List.of();
            case SkillEffect.SyncClaimingProjects e -> List.of();
            // ---- bindings (ticket 49) ----
            // Ledger writes are reversed by RestoreProjectionLedger captured
            // pre-state. Materialize captures its post-state compensation
            // (ReverseProjection); Unmaterialize is teardown.
            case SkillEffect.CreateBinding e -> List.of();
            case SkillEffect.RemoveBinding e -> List.of();
            case SkillEffect.MaterializeProjection e -> {
                // Only record a compensation if the materialization actually
                // succeeded (skipped-on-conflict emits no rollback need).
                boolean materialized = receipt.facts().stream().anyMatch(
                        f -> f instanceof ContextFact.ProjectionMaterialized);
                yield materialized
                        ? List.of(new Compensation.ReverseProjection(e.projection()))
                        : List.of();
            }
            case SkillEffect.UnmaterializeProjection e -> List.of();
            case SkillEffect.SyncDocRepo e -> List.of();
            case SkillEffect.SyncHarness e -> List.of();
            // ARTI-06: deliberately uncompensated. A rebuild has no prior state
            // to restore, and the UninstallCliIfOrphan that RunCliInstall pairs
            // with would delete the shim and tree this run just repaired —
            // leaving the home worse than before the remedy was run.
            case SkillEffect.CheckBuildPolicyGate e -> List.of();
            case SkillEffect.RebuildCliArtifact e -> List.of();
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

    /** Package-private for {@code CompensationOrphanTest}. */
    void applyCompensation(Compensation c, EffectContext ctx) throws IOException {
        switch (c) {
            case Compensation.DeleteUnitDir d -> {
                Path dir = ctx.store().unitDir(d.unitName(), d.kind());
                if (Files.exists(dir)) {
                    dev.skillmanager.shared.util.Fs.deleteRecursive(dir);
                }
            }
            case Compensation.RestoreUnitDir r -> {
                // Runs AFTER the DeleteUnitDir for the same unit — the journal
                // records pre-state before post-state and is walked LIFO — so
                // the destination is already clear of what the commit wrote.
                // restore() removes whatever is left there anyway, because a
                // commit that failed mid-copy leaves a partial tree that no
                // SkillCommitted fact accounts for.
                r.escrow().restore();
                Log.info("rollback: restored the previously installed %s at %s",
                        r.unitName(), ctx.store().unitDir(r.unitName(), r.kind()));
            }
            case Compensation.RestoreInstalledUnit r -> {
                ctx.writeSource(r.previous());
            }
            case Compensation.DeleteInstalledUnit d -> {
                try { ctx.sourceStore().delete(d.unitName()); } catch (Exception ignored) {}
                ctx.invalidate();
            }
            case Compensation.UninstallCliIfOrphan u -> {
                try {
                    CliDependencyCleaner.Result result =
                            CliDependencyCleaner.pruneIfOrphan(ctx.store(), u.unitName(), u.dep());
                    if (!result.claimedByOtherUnit()) {
                        Log.info("rollback: uninstall CLI dep %s (claimed only by rolled-back %s)",
                                u.dep().name(), u.unitName());
                    }
                } catch (Exception ignored) {}
            }
            case Compensation.UnregisterMcpIfOrphan u -> {
                if (!isMcpClaimedByOtherUnit(u.unitName(), u.dep().name())) {
                    GatewayClient client = new GatewayClient(u.gateway());
                    if (client.ping()) {
                        try {
                            if (client.unregister(u.dep().name())) {
                                // The row goes with the registration. McpWriter
                                // writes it the moment `register` succeeds, so
                                // this is the rollback path where a row can
                                // outlive its artifact — the same "second copy
                                // that can disagree with the disk" the forward
                                // unregister paths in LiveInterpreter close.
                                // Only after the gateway confirms it let go.
                                dev.skillmanager.mcp.McpRegistrationLock lock =
                                        dev.skillmanager.mcp.McpRegistrationLock.read(
                                                ctx.store().root());
                                if (lock.server(u.dep().name()).isPresent()) {
                                    lock.without(u.dep().name()).write(ctx.store().root());
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            case Compensation.RestoreUnitsLock r -> {
                dev.skillmanager.lock.UnitsLockWriter.atomicWrite(r.previous(), r.path());
            }
            case Compensation.UnprojectIfOrphan u -> {
                // Route through ProjectorRegistry — same strategy that
                // SyncAgents.apply / UnlinkAgentUnit use. Each projector
                // decides whether the unit had a projection at all
                // (CodexProjector returns empty for plugins, etc.).
                AgentUnit transient_ = unitForUnproject(u.unitName(), u.kind());
                dev.skillmanager.project.ProjectorRegistry.defaultRegistry().removeAll(transient_, store);
            }
            case Compensation.RestoreProjectionLedger r -> {
                ctx.bindingStore().write(r.previous());
            }
            case Compensation.ReverseProjection r -> {
                LiveInterpreter.reverseProjection(r.projection());
            }
        }
    }

    /**
     * Synthesize a transient {@link AgentUnit} carrying just the name +
     * kind needed for {@link dev.skillmanager.project.Projector#planProjection}.
     * Mirrors the same trick {@code unlinkAgentUnit} uses — projector
     * planning only reads the unit's interface, no manifest re-parse
     * needed for a unit we're walking back.
     */
    private static AgentUnit unitForUnproject(String name, UnitKind kind) {
        return switch (kind) {
            case SKILL -> new Skill(name, name, null,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.Map.of(), "", null).asUnit();
            case PLUGIN -> new dev.skillmanager.model.PluginUnit(
                    name, null, name,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.Map.of(), java.util.List.of(), null);
            case DOC, HARNESS -> throw new IllegalStateException(
                    "doc-repos and harness templates do not project into agent dirs — "
                            + "no UnprojectIfOrphan path");
        };
    }

    /** Package-private for {@code CompensationOrphanTest}. */
    boolean isClaimedByOtherUnit(String rolledBackUnit, String depName) {
        try {
            var listed = store.listInstalledUnits();
            for (var u : listed.units()) {
                if (u.name().equals(rolledBackUnit)) continue;
                for (var d : u.cliDependencies()) {
                    if (d.name().equals(depName)) return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    /** Package-private for {@code CompensationOrphanTest}. */
    boolean isMcpClaimedByOtherUnit(String rolledBackUnit, String serverName) {
        try {
            var listed = store.listInstalledUnits();
            for (var u : listed.units()) {
                if (u.name().equals(rolledBackUnit)) continue;
                for (var d : u.mcpDependencies()) {
                    if (d.name().equals(serverName)) return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }
}
