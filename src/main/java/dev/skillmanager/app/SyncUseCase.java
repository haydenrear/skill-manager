package dev.skillmanager.app;

import dev.skillmanager.effects.ContextFact;
import dev.skillmanager.effects.EffectContext;
import dev.skillmanager.effects.EffectReceipt;
import dev.skillmanager.effects.EffectStatus;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.effects.StagedProgram;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.store.UnitReadProblem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One program for both {@code sync} and {@code upgrade}: a per-target
 * effect (either {@link SkillEffect.SyncGit} or {@link
 * SkillEffect.SyncFromLocalDir}), then the post-update tail (transitives,
 * tools/CLI/MCP, agents) plus orphan-detection.
 *
 * <p>{@code preMcpDeps} is captured by an in-program {@link
 * SkillEffect.SnapshotMcpDeps} effect — no snapshot argument plumbed
 * through. Per-skill {@link InstalledUnit.InstallSource} is read at
 * use-case-build time and baked into each {@link SkillEffect.SyncGit}
 * record so dry-run output shows the routing arm.
 */
public final class SyncUseCase {

    private SyncUseCase() {}

    /**
     * All non-target sync flags as one record so the buildProgram signature
     * stays small.
     *
     * <h2>{@code withMcp} and {@code startGateway} are two fields because they
     * are two concerns (ARTI-21, #123)</h2>
     *
     * <p>They were one, and that one flag turned off four effects to fix a
     * defect caused by exactly one of them.
     *
     * <p>{@code 7fce8ed} made MCP work opt-in because a content sync at a
     * project or worktree home was being <b>rolled back</b> by the gateway
     * preflight. That was a real defect and its reasoning still holds — but the
     * preflight is {@code SkillEffect.EnsureGateway}, which builds a Python
     * venv and STARTS a gateway process, and returns {@code failed} when it
     * does not come up healthy. A {@code failed} receipt rolls the whole staged
     * program back. <b>That is gateway provisioning, and it is the only MCP
     * effect on the sync path that can do it.</b>
     *
     * <p>Every other MCP effect tolerates an absent gateway. <b>Corrected after
     * review — the first version of this list was wrong twice and rested on the
     * wrong question.</b>
     *
     * <p>The wrong question was "does this effect's continuation halt the
     * program". <b>Rollback is decoupled from halt:</b> {@code Executor} sets
     * {@code failed = true} on FAILED status <em>alone</em> and walks the
     * compensation journal whenever {@code failed}, halting or not. So the
     * property that matters is "can this effect return FAILED", and it must be
     * asked of all five effects {@code withMcp} drives, not four:
     *
     * <ul>
     *   <li>{@code RegisterMcp} pings first and returns
     *       {@code skipped("gateway unreachable")} — but it was declared
     *       {@code throws IOException} with its {@code ctx.addError} and
     *       {@code registerAll} calls unguarded, so it COULD return FAILED,
     *       in stage 2, after {@code CommitUnitsToStore}. That is fixed at
     *       the handler; see its javadoc.</li>
     *   <li>{@code UnregisterMcpOrphans} pings and skips the same way, and
     *       catches its own exceptions.</li>
     *   <li>{@code SnapshotMcpDeps} never touches a gateway — but it is
     *       <b>not</b> harmless on failure, as this list first claimed: it
     *       carries {@code continuationOnFail() == HALT} deliberately, because
     *       orphan detection with no baseline mis-classifies live servers as
     *       orphans. It is effect #1 of stage 1, so the journal is empty and a
     *       halt there stops the sync before anything has been done — loud and
     *       harmless to the store, which is the right behaviour and not the
     *       "cannot fail" this list originally implied.</li>
     *   <li>the {@code withMcp} arm of {@code PlanBuilder.addToolEnsures} emits
     *       {@code EnsureTool}, which installs only BUNDLED tools and reports
     *       an external one (docker) as {@code missingOnPath} rather than
     *       failing.</li>
     *   <li><b>the fifth, omitted from the first version:</b> {@code withMcp}
     *       also drives {@code PlanBuilder.addMcp}, which adds a
     *       {@code PlanAction.RegisterMcpServer} per dep. It is contained —
     *       {@code runInstallPlan} runs the expanded plan as a SUB-program and
     *       rolls its failures up into {@code ok}/{@code partial} on the parent
     *       receipt, so a plan action cannot produce a parent FAILED — but that
     *       containment is a property of {@code runInstallPlan}, not a
     *       consequence of anything argued here. Stated so nobody inherits it
     *       as a conclusion.</li>
     * </ul>
     *
     * <p>So the opt-in was on the wrong half. It suppressed a registration path
     * that was already safe, and the cost was not theoretical: {@code sync}
     * stopped re-registering MCP servers for real users — a moved
     * {@code mcp_dependencies} declaration stayed unapplied until an explicit
     * command — and two test-graph nodes went red for three days with nobody
     * seeing it, because the graphs were not running.
     *
     * <p>Split: {@code startGateway} carries {@code 7fce8ed}'s opt-in and gates
     * the preflight ONLY; {@code withMcp} goes back to gating registration,
     * which is what it always described. A home with a gateway already up
     * registers on every sync, as it did before; a home without one skips
     * cleanly and says so, which is what the rollback was really asking for.
     */
    public record Options(
            String registryOverride,
            boolean gitLatest,
            boolean merge,
            boolean withMcp,
            boolean withAgents,
            boolean yesForFromDir,
            boolean forceScripts,
            /**
             * Ensure a gateway is RUNNING before the sync, starting one if it
             * is not — the half {@code 7fce8ed} was right to make opt-in.
             */
            boolean startGateway) {
        public Options(String registryOverride,
                       boolean gitLatest,
                       boolean merge,
                       boolean withMcp,
                       boolean withAgents,
                       boolean yesForFromDir,
                       boolean forceScripts) {
            this(registryOverride, gitLatest, merge, withMcp, withAgents, yesForFromDir,
                    forceScripts, false);
        }

        public Options(String registryOverride,
                       boolean gitLatest,
                       boolean merge,
                       boolean withMcp,
                       boolean withAgents,
                       boolean yesForFromDir) {
            this(registryOverride, gitLatest, merge, withMcp, withAgents, yesForFromDir,
                    false, false);
        }
    }

    /**
     * A single sync target — what {@code skill-manager sync <name>}
     * should do for one unit. The shape depends on what kind of unit
     * we're syncing:
     * <ul>
     *   <li>{@link Git} — skill/plugin sync: fetch + merge against the
     *       unit's git origin (or registry-driven sha).</li>
     *   <li>{@link FromDir} — skill/plugin: apply contents from a
     *       local working directory ({@code --from <dir>}).</li>
     *   <li>{@link DocRepo} — doc-repo: walk the projection ledger
     *       for the unit, route each {@code MANAGED_COPY} through
     *       the four-state drift matrix (#48), reapply
     *       {@code IMPORT_DIRECTIVE} rows idempotently.</li>
     * </ul>
     *
     * <p>Future kinds (harness templates per #47) will add their own
     * Target variants so {@code SyncCommand} stays a single dispatch
     * point.
     */
    public sealed interface Target {
        String skillName();
        record Git(String skillName) implements Target {}
        record FromDir(String skillName, Path dir) implements Target {}
        /**
         * Doc-repo sync (#48). {@code force} clobbers locally-edited
         * and conflict destinations; default is preserve-with-warning.
         */
        record DocRepo(String skillName, boolean force) implements Target {}
        /**
         * Harness instance reconciliation (#47): re-runs the
         * instantiator against {@code instanceId} so changes to the
         * template (added / removed unit references, version bumps,
         * doc-repo source changes) propagate into the live sandbox.
         * Idempotent: stable harness binding ids mean re-applying the
         * same plan rewrites the existing bindings.
         *
         * <p>{@code skillName} carries the harness template name so the
         * existing {@link Target#skillName} surface keeps working for
         * legacy callers that don't know about the instance.
         */
        record Harness(String skillName, String instanceId) implements Target {}
    }

    public record Report(
            int worstRc,
            List<String> refused,
            List<String> conflicted,
            int errorCount,
            Map<McpWriter.ConfigChange, List<String>> agentConfigChanges,
            List<String> orphansUnregistered,
            /**
             * Markdown skill-import violations reported by this run. See
             * {@link dev.skillmanager.validation.MarkdownImportValidator#EXIT_CODE}
             * for why a printed violation has to reach an exit code.
             */
            int markdownImportViolations) {

        public static Report empty() {
            return new Report(0, List.of(), List.of(), 0, Map.of(), List.of(), 0);
        }
    }

    /**
     * Sync is a two-stage program. Stage 1 runs the per-target sync (git
     * fetch+merge or apply-from-dir). Stage 2 is built post-merge: it
     * scans the live store for {@code skill_references} that didn't
     * exist before the merge, resolves them, then runs the post-update
     * tail (InstallTools/Cli/RegisterMcp/SyncAgents/UnregisterOrphans)
     * over the union of pre-existing units and the newly-resolved ones.
     *
     * <p>Stage 2's effect list is data-dependent — its shape comes from
     * what stage 1 leaves in the store — so it can't be expressed as a
     * static {@link Program}. The {@link StagedProgram} wrapper carries
     * a stage-2 builder that the interpreter invokes after stage 1
     * completes, with the same {@link EffectContext} threaded through.
     */
    public static StagedProgram<Report> buildProgram(SkillStore store,
                                                     GatewayConfig gw,
                                                     Options options,
                                                     List<Target> targets,
                                                     List<UnitReadProblem> initialReadProblems) throws IOException {
        // The frozen-home gate sits here rather than in each command
        // because this one program *is* both `sync` and `upgrade` (and the
        // `sync --lock` reconcile). Guarding the program means a future
        // caller cannot reach the mutation by adding another entry point
        // and forgetting the check.
        dev.skillmanager.policy.HomePolicy.requireLive(store, "sync");
        Program<?> stage1 = buildStage1(store, gw, options, targets, initialReadProblems);
        java.util.function.Function<EffectContext, Program<?>> stage2 =
                ctx -> buildStage2(ctx, gw, options, targets);
        return new StagedProgram<>("sync-" + UUID.randomUUID(), stage1, stage2, SyncUseCase::decode);
    }

    private static Program<?> buildStage1(SkillStore store,
                                          GatewayConfig gw,
                                          Options options,
                                          List<Target> targets,
                                          List<UnitReadProblem> initialReadProblems) throws IOException {
        UnitStore sources = new UnitStore(store);
        List<SkillEffect> effects = new ArrayList<>(
                // startGateway, NOT withMcp: this preflight is the one effect
                // on the sync path that can start a process and fail the whole
                // program. Registration below is gateway-tolerant by its own
                // handler and does not belong behind the same switch (#123).
                ResolveContextUseCase.preflight(gw, options.registryOverride(),
                        options.startGateway()));
        if (initialReadProblems != null && !initialReadProblems.isEmpty()) {
            effects.add(new SkillEffect.ReportUnitReadProblems(initialReadProblems));
        }
        if (options.withMcp()) effects.add(new SkillEffect.SnapshotMcpDeps());
        for (Target t : targets) {
            switch (t) {
                case Target.Git g -> {
                    InstalledUnit src = sources.read(g.skillName()).orElse(null);
                    InstalledUnit.InstallSource is = src != null && src.installSource() != null
                            ? src.installSource()
                            : InstalledUnit.InstallSource.UNKNOWN;
                    dev.skillmanager.model.UnitKind kind = src != null && src.unitKind() != null
                            ? src.unitKind()
                            : dev.skillmanager.model.UnitKind.SKILL;
                    effects.add(new SkillEffect.SyncGit(
                            g.skillName(), kind, is, options.gitLatest(), options.merge()));
                }
                case Target.FromDir f -> effects.add(new SkillEffect.SyncFromLocalDir(
                        f.skillName(), f.dir(), options.merge(), options.yesForFromDir()));
                case Target.DocRepo d -> effects.add(new SkillEffect.SyncDocRepo(
                        d.skillName(), d.force()));
                case Target.Harness h -> effects.add(new SkillEffect.SyncHarness(
                        h.skillName(), h.instanceId()));
            }
        }
        return new Program<>("sync-stage1-" + UUID.randomUUID(), effects, receipts -> null);
    }

    /**
     * Stage 2 builder, invoked by the interpreter after stage 1 finishes.
     * Reads the post-merge live store, resolves any references that point
     * at units not in the store, commits them, then runs the post-update
     * tail over the union (existing + newly-resolved). The bulk handlers
     * reload manifests from disk per name so updated dep declarations on
     * existing skills are picked up.
     */
    private static Program<?> buildStage2(
            EffectContext ctx,
            GatewayConfig gw,
            Options options,
            List<Target> targets
    ) {
        SkillStore store = ctx.store();
        // Kind-aware listing — covers skills under skills/ and plugins
        // under plugins/. Skill-only listInstalled() (legacy) misses
        // plugin-kind units, which silently skipped the post-update
        // tail (RegisterMcp, SyncAgents, RefreshHarnessPlugins).
        List<dev.skillmanager.model.AgentUnit> liveUnits;
        List<SkillEffect> effects = new ArrayList<>();
        try {
            var listed = store.listInstalledUnits();
            liveUnits = new ArrayList<>(listed.units());
            if (!listed.problems().isEmpty()) {
                effects.add(new SkillEffect.ReportUnitReadProblems(listed.problems()));
            }
        } catch (IOException io) {
            // Halt-via-empty-program: the surrounding command will see no
            // tail effects and the sync still reports through stage 1's
            // receipts. Realistically this only fails if the store dir is
            // mid-rename, which is rare enough to warrant a no-op tail.
            return new Program<>("sync-stage2-" + UUID.randomUUID(), List.of(), receipts -> null);
        }

        List<SkillEffect> alwaysAfter = new ArrayList<>();

        // discoverNewlySurfacedRefs still consumes List<Skill> — pull the
        // skill subset out for it. Plugins don't surface new refs through
        // skill_references; their references live on the plugin / contained
        // skills, both of which are already part of liveUnits.
        List<Skill> liveSkills = new ArrayList<>();
        for (var u : liveUnits) {
            if (u instanceof dev.skillmanager.model.SkillUnit su) liveSkills.add(su.skill());
        }
        // Stage 2 owns the unmet-reference resolve via the new effect.
        // The handler walks each live skill's references, resolves the
        // unmet set, and writes the resulting graph into
        // ctx.resolvedGraph() — every downstream effect with a no-arg
        // constructor reads from there. When the resolve finds nothing
        // unmet (steady-state sync), the graph is empty and the commit /
        // plan / provenance / run effects are no-ops. Same shape as the
        // install path's BuildResolveGraphFromSource preamble.
        effects.add(new SkillEffect.BuildResolveGraphFromUnmetReferences(liveSkills));
        effects.add(new SkillEffect.CommitUnitsToStore());
        effects.add(new SkillEffect.ValidateMarkdownImports(
                markdownValidationScope(targets, liveUnits)));
        effects.add(SkillEffect.ValidateMarkdownImports.resolvedGraph());
        List<String> forceScriptUnits = forceScriptUnitNames(targets);
        effects.add(new SkillEffect.BuildInstallPlan(
                null, options.forceScripts(), forceScriptUnits, options.withMcp()));
        // Sync had no audit trail at all until issue #45 — measured, the log's
        // last entry was 8 days old while a sync rewrote 101 files in that home,
        // which is precisely the situation an audit log exists for. Install's
        // treatment is this same effect in this same position (after the plan is
        // built, before the plan runs), so it is reused rather than respelled.
        //
        // The plan alone would not have fixed it: sync's plan comes from
        // BuildResolveGraphFromUnmetReferences, so a steady-state sync plans
        // zero actions and would still have logged nothing while re-merging
        // every unit. auditTargets() names the per-target work — the SyncGit /
        // SyncFromLocalDir / SyncDocRepo / SyncHarness effects stage 1 ran —
        // from the one place that already knows the targets.
        effects.add(new SkillEffect.RecordAuditPlan("sync", auditTargets(targets)));
        effects.add(new SkillEffect.RecordSourceProvenance());
        effects.add(new SkillEffect.RunInstallPlan(gw));
        // Cleanup the resolver's staged temp dirs no matter how the
        // tail goes — the resolve effect populates ctx with a fresh
        // graph each pass.
        alwaysAfter.add(new SkillEffect.CleanupResolvedGraph());

        effects.add(new SkillEffect.InstallTools(liveUnits));
        effects.add(new SkillEffect.InstallCli(liveUnits, options.forceScripts(), forceScriptUnits));
        if (options.withMcp()) effects.add(new SkillEffect.RegisterMcp(liveUnits, gw));
        if (options.withAgents()) effects.add(new SkillEffect.SyncAgents(liveUnits, gw));
        // Plugin marketplace + harness CLI lifecycle. Sync is the
        // user-stated trigger for "uninstall+reinstall every plugin" so
        // hooks reload from the just-merged bytes — pass every installed
        // plugin name as the reinstall set.
        if (options.withAgents()) {
            List<String> pluginNames = new ArrayList<>();
            for (var u : liveUnits) {
                if (u.kind() == dev.skillmanager.model.UnitKind.PLUGIN) pluginNames.add(u.name());
            }
            effects.add(SkillEffect.RefreshHarnessPlugins.reinstallAll(pluginNames));
        }
        if (options.withMcp()) effects.add(new SkillEffect.UnregisterMcpOrphans(gw));

        // Lock flip — last main effect. Targets the post-merge live state.
        // Sync's "bumped sha" rows are the primary thing that changes
        // here: a unit's installed-record gitHash advances after the
        // merge, and the lock follows. Newly-resolved extras (if any)
        // are reflected on the NEXT sync — their installed-records will
        // have been written by RecordSourceProvenance by then.
        effects.add(buildLockUpdate(store, liveUnits));
        List<String> projectSyncUnits = projectSyncUnitNames(targets);
        if (!projectSyncUnits.isEmpty()) {
            // startGateway, NOT withMcp, and this one is load-bearing rather
            // than tidy. This argument reaches a CHILD project's own install
            // program through ProjectDependencyResolver ->
            // InstallUseCase.buildProgramForStagedGraph, where ONE boolean
            // still gates both the EnsureGateway preflight and the MCP work —
            // the same conflation this ticket is splitting, one level down and
            // NOT split here. Passing withMcp would put an EnsureGateway back
            // into a project resolve, which is precisely the rollback 7fce8ed
            // fixed and precisely the home tier it was reported at.
            //
            // The cost, stated rather than hidden: a claiming-project sync
            // does not register MCP servers, where the parent home now does.
            // That asymmetry is the SAME behaviour this path has had since
            // 7fce8ed, so nothing regresses — but it does not go away until
            // buildProgramForStagedGraph takes two booleans too. Deferred on
            // #100's backlog rather than widened into here, because that
            // signature is `install`'s and `project resolve`'s, not sync's.
            effects.add(new SkillEffect.SyncClaimingProjects(
                    projectSyncUnits,
                    options.startGateway() ? gw : null,
                    options.startGateway()));
        }

        Program<?> p = new Program<>("sync-stage2-" + UUID.randomUUID(), effects, receipts -> null);
        for (SkillEffect cleanup : alwaysAfter) p = p.withFinally(cleanup);
        return p;
    }

    /**
     * One audit line per sync target, naming the unit AND which arm touched it.
     *
     * <p>"Which arm" is the part that made the missing trail expensive: a unit
     * re-merged from its git trunk, one overwritten from a local directory, and
     * one whose doc-repo projections were reapplied are three different sets of
     * bytes moving, and an entry that named only the unit could not tell an
     * investigator which one happened.
     */
    private static List<String> auditTargets(List<Target> targets) {
        List<String> out = new ArrayList<>();
        for (Target t : targets == null ? List.<Target>of() : targets) {
            out.add(switch (t) {
                case Target.Git g -> "sync git " + g.skillName();
                case Target.FromDir f -> "sync from-dir " + f.skillName() + " <- " + f.dir();
                case Target.DocRepo d -> "sync doc-repo " + d.skillName()
                        + (d.force() ? " --force" : "");
                case Target.Harness h -> "sync harness " + h.skillName() + " #" + h.instanceId();
            });
        }
        return out;
    }

    private static List<String> projectSyncUnitNames(List<Target> targets) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Target t : targets == null ? List.<Target>of() : targets) {
            String name = t.skillName();
            if (name != null && !name.isBlank()) out.add(name);
        }
        return List.copyOf(out);
    }

    private static List<String> forceScriptUnitNames(List<Target> targets) {
        return projectSyncUnitNames(targets);
    }

    /**
     * Compute the post-sync lock target. Reads the current lock and
     * upserts one row per live unit (post-merge installed-record state).
     * Plugins flow through the same path — the only thing the lock
     * cares about is the installed-record per name.
     *
     * <p>Newly-resolved extras (from
     * {@link SkillEffect.BuildResolveGraphFromUnmetReferences}) aren't
     * reflected here: at builder time they don't exist yet, and by the
     * time the lock effect runs, the extras' installed-records have been
     * written by {@link SkillEffect.RecordSourceProvenance}, but {@code
     * live} was captured pre-resolve. They flow into the lock on the
     * next sync via the live-skill loop above — same convergence pattern
     * as drift detection.
     */
    private static SkillEffect.UpdateUnitsLock buildLockUpdate(
            SkillStore store, List<dev.skillmanager.model.AgentUnit> live) {
        try {
            java.nio.file.Path lockPath = dev.skillmanager.lock.UnitsLockReader.defaultPath(store);
            dev.skillmanager.lock.UnitsLock current = dev.skillmanager.lock.UnitsLockReader.read(lockPath);
            UnitStore sources = new UnitStore(store);
            dev.skillmanager.lock.UnitsLock target = current;
            for (var u : live) {
                InstalledUnit rec = sources.read(u.name()).orElse(null);
                if (rec == null) continue;
                target = target.withUnit(dev.skillmanager.lock.LockedUnit.fromInstalled(rec));
            }
            return new SkillEffect.UpdateUnitsLock(target, lockPath);
        } catch (IOException io) {
            return new SkillEffect.UpdateUnitsLock(
                    dev.skillmanager.lock.UnitsLock.empty(),
                    dev.skillmanager.lock.UnitsLockReader.defaultPath(store));
        }
    }

    private static List<String> unitNames(List<dev.skillmanager.model.AgentUnit> units) {
        List<String> out = new ArrayList<>();
        for (var u : units) out.add(u.name());
        return out;
    }

    /**
     * The units whose markdown imports THIS sync answers for.
     *
     * <h2>Why it is the targets and not the whole home</h2>
     *
     * <p>This used to be every live unit, which made
     * {@link dev.skillmanager.validation.MarkdownImportValidator#EXIT_CODE}
     * report the wrong thing on a named sync. Measured: {@code skill-dev sync
     * <unit>} — which delegates to {@code skill-manager sync <unit> --from
     * <worktree> --merge} — synced that unit successfully
     * ({@code ✓ synced 1 unit(s) — 1 merged}) and then exited 11 for an
     * unresolved import in {@code skill-dev-skill}, a DIFFERENT unit the
     * command was never asked about. {@code skill-dev}'s edit loop reads that
     * exit code, so the product's own documented developer cycle failed on
     * content it did not touch, and would keep failing until an unrelated
     * unit's reference was fixed.
     *
     * <p>An exit code is a verdict on the run. A run that synced one unit
     * cannot answer for every reference in the home, and 11 is only evidence
     * of anything if it is attributable — which is the same argument that
     * gave the violations their own code instead of folding them into 1.
     *
     * <p>A no-name {@code sync} still validates everything: its target list is
     * built by walking every installed unit, so the scope is unchanged there.
     * Newly-resolved transitives are validated by the separate
     * {@code ValidateMarkdownImports.resolvedGraph()} effect, which is why
     * scoping this one does not let a freshly-committed unit through.
     *
     * <p>Names are intersected with the live store so a target that is not
     * installed (a harness template with no instances, a unit removed
     * mid-run) does not reach the validator.
     */
    private static List<String> markdownValidationScope(
            List<Target> targets, List<dev.skillmanager.model.AgentUnit> liveUnits) {
        java.util.Set<String> live = new java.util.LinkedHashSet<>(unitNames(liveUnits));
        java.util.LinkedHashSet<String> scope = new java.util.LinkedHashSet<>();
        for (Target t : targets) {
            if (t.skillName() != null && live.contains(t.skillName())) scope.add(t.skillName());
        }
        return new ArrayList<>(scope);
    }

    private static Report decode(List<EffectReceipt> receipts) {
        int worstRc = 0;
        List<String> refused = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        int errorCount = 0;
        Map<McpWriter.ConfigChange, List<String>> agentChanges = new LinkedHashMap<>();
        List<String> orphans = new ArrayList<>();
        int importViolations = 0;

        for (EffectReceipt r : receipts) {
            // Each receipt counts at most once. PARTIAL counts on per-target
            // sync (SyncGit / SyncFromLocalDir) and SyncAgents
            // (per-(agent,skill) sync failures). Other PARTIAL paths are
            // informational.
            //
            // `RegisterMcp` USED TO COUNT HERE and no longer does (ARTI-21
            // review, measured A/B on one machine with one fixture and a LIVE
            // gateway holding one server that fails `tools/list`):
            //
            //     base epic/artifact-dag : exit 0
            //     with MCP on by default : exit 1     <-- regression
            //
            // The PR that turned registration back on claimed no exit-code
            // change because a SKIPPED receipt is neither FAILED nor PARTIAL.
            // That is true only for the gateway-ABSENT case. With a gateway
            // PRESENT and any server failing to deploy, the receipt is PARTIAL
            // — and PARTIAL was counted, so every sync at a home with one
            // flaky MCP server started exiting 1 where it had exited 0. It was
            // also the direct cause of `smoke`'s `sync_noop_exit_zero` and
            // `sync_force_exit_zero` going red.
            //
            // The rule, and it is the same one `7fce8ed` established one level
            // over: SYNC'S EXIT CODE DESCRIBES THE CONTENT SYNC. A gateway must
            // not roll a content refresh back, and by the same argument it must
            // not fail one either. Nothing is hidden by this — a failed
            // registration is still recorded per unit as
            // MCP_REGISTRATION_FAILED / GATEWAY_UNAVAILABLE, still printed by
            // the renderer, and still surfaced by `report` with the remedy that
            // fixes it. It moves from the exit code to the record, which is
            // where a fact about the gateway belongs.
            boolean isSyncTarget = r.effect() instanceof SkillEffect.SyncGit
                    || r.effect() instanceof SkillEffect.SyncFromLocalDir;
            boolean isAgentSync = r.effect() instanceof SkillEffect.SyncAgents;
            boolean isProjectSync = r.effect() instanceof SkillEffect.SyncClaimingProjects;
            // FAILED is deliberately NOT exempted for RegisterMcp. After the
            // guard added to its handler it cannot return FAILED at all — and
            // if some later change makes it able to again, that is a receipt
            // that rolls the whole sync back, so the exit code is the least of
            // it and it should count loudly rather than be quietly excused.
            if (r.status() == EffectStatus.FAILED) {
                errorCount++;
            } else if (r.status() == EffectStatus.PARTIAL
                    && (isSyncTarget || isAgentSync || isProjectSync)) {
                errorCount++;
            }
            for (ContextFact f : r.facts()) {
                switch (f) {
                    case ContextFact.SyncGitRefused g -> {
                        refused.add(g.skillName());
                        if (worstRc < 7) worstRc = 7;
                    }
                    case ContextFact.SyncGitConflicted g -> {
                        conflicted.add(g.skillName());
                        if (worstRc < 8) worstRc = 8;
                    }
                    case ContextFact.SyncGitFailed ignored -> {
                        if (worstRc < 1) worstRc = 1;
                    }
                    case ContextFact.AgentMcpConfigChanged c -> agentChanges
                            .computeIfAbsent(c.change(), k -> new ArrayList<>())
                            .add(c.agentId() + " (" + c.configPath() + ")");
                    case ContextFact.OrphanUnregistered o -> orphans.add(o.serverId());
                    case ContextFact.MarkdownImportViolation ignored -> importViolations++;
                    default -> {}
                }
            }
        }
        return new Report(worstRc, refused, conflicted, errorCount, agentChanges, orphans,
                importViolations);
    }

    public static void printSyncSummary(Report report) {
        if (report.refused().isEmpty() && report.conflicted().isEmpty()) return;
        System.err.println();
        System.err.println("sync summary: "
                + (report.refused().size() + report.conflicted().size()) + " skill(s) need attention");
        if (!report.refused().isEmpty()) {
            System.err.println();
            System.err.println("  Extra local changes — re-run with --merge to bring upstream in:");
            for (String n : report.refused()) {
                System.err.println("    skill-manager sync " + n + " --merge");
            }
        }
        if (!report.conflicted().isEmpty()) {
            System.err.println();
            System.err.println("  Conflicted — resolve in the store dir, then `git commit` or `git merge --abort`:");
            for (String n : report.conflicted()) System.err.println("    " + n);
        }
        System.err.println();
    }
}
