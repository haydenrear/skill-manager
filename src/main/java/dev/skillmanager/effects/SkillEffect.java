package dev.skillmanager.effects;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.store.UnitReadProblem;
import dev.skillmanager.tools.ToolDependency;

import java.nio.file.Path;
import java.util.List;

/**
 * Effects are pure data — they describe what should happen, not how.
 * Every interpreter handles every variant by exhaustive switch.
 *
 * <p>Each effect either touches an external system that can fail
 * (filesystem, gateway, registry, audit log) or modifies one of the
 * source-of-truth records the rest of the system reads (skill store,
 * {@code sources/<name>.json}, {@code registry.properties}). Receipts
 * carry the per-effect outcome so decoders can build reports and so a
 * future compensation pass can roll back partial state.
 */
public sealed interface SkillEffect permits
        SkillEffect.ConfigureRegistry,
        SkillEffect.EnsureGateway,
        SkillEffect.StopGateway,
        SkillEffect.ConfigureGateway,
        SkillEffect.SetupPackageManagerRuntime,
        SkillEffect.InstallPackageManager,
        SkillEffect.SnapshotMcpDeps,
        SkillEffect.RejectIfAlreadyInstalled,
        SkillEffect.BuildResolveGraphFromSource,
        SkillEffect.BuildResolveGraphFromBundledSkills,
        SkillEffect.BuildResolveGraphFromUnmetReferences,
        SkillEffect.ReportUnitReadProblems,
        SkillEffect.ValidateMarkdownImports,
        SkillEffect.BuildInstallPlan,
        SkillEffect.RunInstallPlan,
        SkillEffect.CleanupResolvedGraph,
        SkillEffect.PrintInstalledSummary,
        SkillEffect.SyncFromLocalDir,
        SkillEffect.CommitUnitsToStore,
        SkillEffect.RecordAuditPlan,
        SkillEffect.RecordSourceProvenance,
        SkillEffect.OnboardUnit,
        SkillEffect.EnsureTool,
        SkillEffect.RunCliInstall,
        SkillEffect.RegisterMcpServer,
        SkillEffect.UnregisterMcpOrphan,
        SkillEffect.UnregisterMcpOrphans,
        SkillEffect.SyncAgents,
        SkillEffect.RefreshHarnessPlugins,
        SkillEffect.SyncGit,
        SkillEffect.RemoveUnitFromStore,
        SkillEffect.PruneCliIfOrphan,
        SkillEffect.RecordArtifactLedger,
        SkillEffect.PruneOrphanArtifacts,
        SkillEffect.UnlinkAgentUnit,
        SkillEffect.UnlinkAgentMcpEntry,
        SkillEffect.ScaffoldSkill,
        SkillEffect.ScaffoldPlugin,
        SkillEffect.InitializePolicy,
        SkillEffect.LoadOutstandingErrors,
        SkillEffect.AddUnitError,
        SkillEffect.ClearUnitError,
        SkillEffect.ValidateAndClearError,
        SkillEffect.InstallTools,
        SkillEffect.InstallCli,
        SkillEffect.RegisterMcp,
        SkillEffect.UpdateUnitsLock,
        SkillEffect.SyncClaimingProjects,
        SkillEffect.RejectIfTopLevelInstalled,
        SkillEffect.CheckInstallPolicyGate,
        SkillEffect.CreateBinding,
        SkillEffect.RemoveBinding,
        SkillEffect.MaterializeProjection,
        SkillEffect.UnmaterializeProjection,
        SkillEffect.SyncDocRepo,
        SkillEffect.SyncHarness,
        SkillEffect.CheckBuildPolicyGate,
        SkillEffect.RebuildCliArtifact {

    // ------------------------------------------------------------------
    // Per-outcome continuations. Effect declares what the program should
    // do *after this effect ran*, separately for each terminal status:
    //
    //   - {@link #continuationOnOk()}      — runs cleanly
    //   - {@link #continuationOnPartial()} — succeeded for some items, failed for others
    //   - {@link #continuationOnFail()}    — failed entirely
    //
    // Interpreters drive off {@link EffectReceipt#continuation()} (which
    // is populated by the receipt factory from the matching method here)
    // when deciding whether to skip the rest of the program. Handlers
    // CAN still override per-receipt via {@code EffectReceipt.okAndHalt}
    // / {@code failedAndHalt} etc. — useful for precondition checks
    // where the halt decision is runtime-conditional (e.g.
    // {@link RejectIfAlreadyInstalled} only halts when the unit is
    // actually present).
    //
    // Defaults: everything continues. Effects that genuinely break
    // downstream effects when they fail (commit, plan-build,
    // resolve-on-failure, etc.) override {@link #continuationOnFail()}
    // to {@link Continuation#HALT}. Cooperative-stop effects (policy
    // gate, "remove first" precondition) override
    // {@link #continuationOnOk()} or signal HALT per-receipt.
    // ------------------------------------------------------------------

    default Continuation continuationOnOk() { return Continuation.CONTINUE; }
    default Continuation continuationOnPartial() { return Continuation.CONTINUE; }
    default Continuation continuationOnFail() { return Continuation.CONTINUE; }

    // ------------------------------------------------------------------
    // Write confinement. An effect that claims to be writing INTO THIS HOME
    // says so, and everything it reaches that consults
    // dev.skillmanager.store.WriteConfinement then refuses a path resolving
    // outside the roots named here.
    //
    // THE DEFAULT IS UNCONFINED, AND IT HAS TO BE. Around a dozen of the
    // effects below write outside the home as their ENTIRE PURPOSE:
    // MaterializeProjection and CreateBinding write ~/.claude, SyncAgents and
    // RefreshHarnessPlugins write the three agent homes, SyncClaimingProjects
    // writes a project checkout, ScaffoldSkill and ScaffoldPlugin write an
    // operator-named directory, and every package backend writes a shared
    // content-addressed cache whose own javadoc says confinement is not this
    // codebase's mechanism. A default of "inside the home or refuse" would not
    // be a guard, it would be an outage.
    //
    // So this method is the ENUMERATION of which effects claim confinement,
    // and it is deliberately short. Adding an override is a reviewable act;
    // the bug is the exception nobody wrote down.
    // ------------------------------------------------------------------

    /**
     * The roots this effect may write under, or
     * {@link dev.skillmanager.store.WriteConfinement#unconfined()} when it
     * makes no such claim.
     *
     * <p><b>Declaring is not the same as enforcing</b>, and this method should
     * not be read as though it were. Enforcement lives at the sites that
     * actually mutate — {@code CliShimPruner.prune},
     * {@code InstallerRegistry}'s producer boundary and
     * {@code InstallerRegistry.takeOwnershipOfShim} — and each of those carries
     * its own <em>unconditional</em> home check, so closing the measured
     * defects does not depend on this declaration being present. What a
     * declaration here does is let an effect WIDEN the roots (a sanctioned
     * parent store, a shared cache) in one reviewable place, instead of each
     * writer inventing its own exception where nobody reads it.
     */
    default dev.skillmanager.store.WriteConfinement.Scope writeConfinement(
            dev.skillmanager.store.SkillStore store) {
        return dev.skillmanager.store.WriteConfinement.unconfined();
    }

    /** Shorthand for the effects below that claim this home and nothing else. */
    private static dev.skillmanager.store.WriteConfinement.Scope thisHome(
            dev.skillmanager.store.SkillStore store, String what) {
        return store == null
                ? dev.skillmanager.store.WriteConfinement.unconfined()
                : dev.skillmanager.store.WriteConfinement.forHome(store.root(), what);
    }

    /**
     * Persist a registry URL override and reload {@link
     * dev.skillmanager.registry.RegistryConfig}. Failure modes: malformed
     * URI, unwritable store root.
     *
     * <p>Halts on fail: a wrong registry URL would route the resolve
     * downstream at the wrong server, so the program should stop.
     */
    record ConfigureRegistry(String url) implements SkillEffect {
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Probe the gateway; if local and not running, start it and wait for
     * {@code /health}. Failure modes: gateway not local + unreachable, or
     * local-start exceeded the health-check timeout.
     */
    record EnsureGateway(GatewayConfig gateway, java.time.Duration timeout) implements SkillEffect {
        public EnsureGateway(GatewayConfig gateway) {
            this(gateway, java.time.Duration.ofSeconds(20));
        }
        // No gateway means MCP register + agent-config write downstream
        // can't function; halt rather than fan-out errors.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Move every staged unit in the {@link ResolvedGraph} into the store,
     * routing each to {@code skills/<name>} or {@code plugins/<name>} per
     * {@link AgentUnit#kind()}. Failure mode: copy throws midway → handler
     * best-effort removes any unit dirs it just created so a rerun starts
     * clean. The receipt lists which names committed for downstream
     * effects (provenance, post-update) to reference.
     *
     * <p>The {@code graph} field is optional — when {@code null}, the
     * handler reads {@link EffectContext#resolvedGraph()} set by a prior
     * {@code BuildResolveGraphFrom*} effect. Tests and legacy callers
     * still pass an explicit graph; new use cases plug into the ctx
     * pattern by passing {@code null}.
     */
    record CommitUnitsToStore(ResolvedGraph graph) implements SkillEffect {
        public CommitUnitsToStore() { this(null); }
        // No bytes on disk means provenance / run-plan / agent sync /
        // lock flip all run against an empty store. Halt so the program
        // doesn't trash post-update state.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    // ------------------------------------------------------------------
    // Resolve-graph effects (Stage A — input-discovery + Resolver call
    // unified into one scenario-specific effect per command, so the
    // discovery IO surfaces through ConsoleProgramRenderer too).
    // ------------------------------------------------------------------

    /**
     * Install's scenario: a single CLI-supplied source string (and
     * optional version) gets shaped into one {@link
     * dev.skillmanager.resolve.Resolver.Coord} and passed through
     * the resolver. The "build" half is trivial (no IO) but stays an
     * effect for shape consistency with the other two scenarios and
     * to keep the rendering pipeline uniform — every "produce
     * graph" step emits its facts the same way.
     */
    record BuildResolveGraphFromSource(String source, String version) implements SkillEffect {
        // Install path: any resolve failure (top-level or transitive)
        // means the program can't proceed. Handler emits FAILED status
        // when failures exist, and this declaration translates that
        // into the program-halting receipt continuation.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
        // Even a "partial" graph (some coords resolved, some failed)
        // halts install — the user explicitly asked to install one
        // thing, so a failing transitive can't be silently dropped.
        @Override public Continuation continuationOnPartial() { return Continuation.HALT; }
    }

    /**
     * Onboard's scenario: walk a candidate install root (or fall back
     * to github coords) and resolve every bundled skill that isn't
     * already installed. The discovery half does real IO — probe each
     * candidate dir for {@code SKILL.md}, read its
     * {@code skill-manager.toml} for the published name, check
     * {@link dev.skillmanager.store.SkillStore#contains} for skip — and
     * each decision emits a renderer-visible fact
     * ({@link ContextFact.BundledSkillFound},
     * {@link ContextFact.BundledSkillAlreadyInstalled},
     * {@link ContextFact.BundledSkillMissing}).
     */
    record BuildResolveGraphFromBundledSkills(
            java.nio.file.Path installRoot,
            List<BundledSkillSpec> bundledSkills) implements SkillEffect {
        /**
         * One bundled-skill entry from {@code OnboardCommand}: its
         * source-tree directory name (used to find it under
         * {@code installRoot}), its published name (for the
         * already-installed skip), and its github fallback coord
         * (used when {@code installRoot} is null).
         */
        public record BundledSkillSpec(String dirName, String publishedName, String githubCoord) {}

        // Onboard's pre-Program path halted on any failure; preserve.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
        @Override public Continuation continuationOnPartial() { return Continuation.HALT; }
    }

    /**
     * Sync's scenario (stage 2): walk every live skill's
     * {@code skill_references}, decide which ones aren't yet in the
     * store, and resolve the unmet set. Failure attribution is to the
     * parent unit that declared each failing ref —
     * {@link EffectContext#addError(String, dev.skillmanager.source.InstalledUnit.ErrorKind, String)}
     * records a {@code TRANSITIVE_RESOLVE_FAILED} on each parent in
     * the store; self-clears next pass when refs resolve again.
     */
    record BuildResolveGraphFromUnmetReferences(
            List<dev.skillmanager.model.Skill> liveSkills) implements SkillEffect {
        // A total resolve failure leaves no new graph work to apply. Halt
        // before the global/project reconciliation tail rewrites existing
        // projections and metadata only to have the transaction roll back.
        // PARTIAL remains CONTINUE so successfully resolved refs still land.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Report installed-unit directories that existed but could not be
     * parsed/read while building a live store view. Advisory; downstream
     * effects run over the units that were readable.
     */
    record ReportUnitReadProblems(List<UnitReadProblem> problems) implements SkillEffect {
        public ReportUnitReadProblems {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /**
     * Walk markdown files in installed units and report any frontmatter
     * {@code skill-imports} entries that do not resolve to installed
     * unit files. Violations are advisory facts, not hard install
     * blockers. When {@code unitNames} is {@code null}, the handler
     * reads the current resolved graph from context and validates just
     * those committed units. Sync validates after newly surfaced
     * transitive refs have been committed, so imports can point at a unit
     * that was synced in the same staged run.
     */
    record ValidateMarkdownImports(List<String> unitNames) implements SkillEffect {
        public ValidateMarkdownImports {
            unitNames = unitNames == null ? null : List.copyOf(unitNames);
        }

        public static ValidateMarkdownImports resolvedGraph() {
            return new ValidateMarkdownImports(null);
        }

    }

    /**
     * Append the install plan to the audit log under {@code verb}
     * ({@code "install"} / {@code "sync"} / etc.). Reads the plan from
     * {@link EffectContext#plan()} — must run after {@link BuildInstallPlan}.
     *
     * <p>{@code targets} names work this command did that the plan cannot
     * describe, one audit line each. It exists because {@code sync} had NO
     * audit trail at all (issue #45): its last entry was 8 days old while a
     * {@code sync} rewrote 101 files in the same home, which was discovered
     * precisely when a trail was needed. Giving sync "the same treatment" as
     * install — this effect, with the plan — is necessary and not sufficient:
     * sync's plan is built from
     * {@link BuildResolveGraphFromUnmetReferences}, so a steady-state sync
     * (nothing unmet, which is the ordinary case) plans zero actions and would
     * have logged zero lines while still re-merging every unit. The per-target
     * work lives in {@link SyncGit} / {@link SyncFromLocalDir} /
     * {@link SyncDocRepo} / {@link SyncHarness}, and those are what touched the
     * bytes.
     *
     * <p>A component on this effect rather than a second audit-writing effect
     * or an {@code AuditLog} call inside each of those four handlers: one
     * definition ({@code AuditLog.record}), one handler, one emission site (the
     * loop in {@code SyncUseCase} that already builds the targets). Four
     * handlers writing their own line is four chances to miss one, which is how
     * this was missing in the first place. Empty for {@code install}, whose
     * behaviour is byte-identical to before.
     */
    record RecordAuditPlan(String verb, List<String> targets) implements SkillEffect {
        public RecordAuditPlan {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }

        public RecordAuditPlan(String verb) { this(verb, List.of()); }
    }

    /**
     * Walk the committed graph and write {@code sources/<name>.json} for each skill.
     *
     * <p>{@code graph} is optional — when {@code null}, the handler reads
     * the graph from {@link EffectContext#resolvedGraph()}.
     */
    record RecordSourceProvenance(ResolvedGraph graph) implements SkillEffect {
        public RecordSourceProvenance() { this(null); }
        // Lock flip reads installed-records; a failed provenance write
        // means the lock would point at non-existent records. Halt.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Write an {@code installed/<name>.json} record for a unit that lacks
     * one. Routes through {@link dev.skillmanager.store.SkillStore#unitDir}
     * for the source-dir probe so plugins under {@code plugins/<name>}
     * onboard with the right git/transport detection.
     */
    record OnboardUnit(AgentUnit unit) implements SkillEffect {}

    /** Build the install plan from {@code units} and run runtime-tool installers (uv / npm / docker / brew). */
    record InstallTools(List<AgentUnit> units) implements SkillEffect {}

    /** Build the install plan from {@code units} and run CLI dep installers. */
    record InstallCli(
            List<AgentUnit> units,
            boolean forceScripts,
            List<String> forceScriptUnitNames) implements SkillEffect {
        public InstallCli(List<AgentUnit> units) { this(units, false, List.of()); }
        public InstallCli(List<AgentUnit> units, boolean forceScripts) {
            this(units, forceScripts, List.of());
        }
        public InstallCli {
            forceScriptUnitNames = forceScriptUnitNames == null
                    ? List.of()
                    : List.copyOf(forceScriptUnitNames);
        }
        // This effect prunes bin/cli and then runs every declared producer.
        // Both halves are the measured damage: DEF-007 (the prune, deleting
        // through a bin/cli that is itself a link at another home) and HIS-7
        // (the producer, writing through an inherited shim into the parent).
        @Override public dev.skillmanager.store.WriteConfinement.Scope writeConfinement(
                dev.skillmanager.store.SkillStore store) {
            return thisHome(store, "sync's CLI install pass");
        }
    }

    /** Register every unit's MCP deps with the gateway, capturing per-server outcomes. */
    record RegisterMcp(List<AgentUnit> units, GatewayConfig gateway) implements SkillEffect {}

    /** Unregister an MCP server no surviving skill still declares. */
    record UnregisterMcpOrphan(String serverId, GatewayConfig gateway) implements SkillEffect {}

    /**
     * Diff {@link EffectContext#preMcpDeps()} against the live store and
     * unregister every MCP server no surviving skill still declares.
     * Reads the pre-snapshot at exec time so orphan-detection sees the
     * actual post-mutation state — the snapshot must have been captured
     * by an earlier {@link SnapshotMcpDeps} effect.
     */
    record UnregisterMcpOrphans(GatewayConfig gateway) implements SkillEffect {}

    /**
     * Refresh agent symlinks + MCP-config entries for every known agent.
     *
     * <p>Typed against {@link AgentUnit} as of ticket 07. Kind-aware
     * dispatch (skill → symlink, plugin → projector entry) lands in
     * ticket 11; until then the handler treats every unit as the
     * pre-existing skill carrier.
     */
    record SyncAgents(List<AgentUnit> units, GatewayConfig gateway) implements SkillEffect {}

    /**
     * Reconcile the skill-manager-owned plugin marketplace
     * ({@code <store>/plugin-marketplace/}) and any harness CLIs
     * ({@code claude}, {@code codex}) with the current installed-plugin
     * set:
     *
     * <ol>
     *   <li>Regenerate {@code marketplace.json} + the symlink tree from
     *       {@link dev.skillmanager.store.SkillStore#listInstalledUnits()}.</li>
     *   <li>For every harness driver whose CLI is on PATH:
     *       {@code marketplace add} (idempotent), {@code marketplace
     *       update}, then for each name in {@link #reinstall} run
     *       uninstall+reinstall (so newly-bundled hooks load), and for
     *       each name in {@link #uninstall} run uninstall.</li>
     *   <li>For every harness driver whose CLI is missing on PATH,
     *       record {@link
     *       dev.skillmanager.source.InstalledUnit.ErrorKind#HARNESS_CLI_UNAVAILABLE}
     *       on each plugin so the report surface tells the user how to
     *       install the missing CLI.</li>
     *   <li>One-time cleanup of the pre-marketplace
     *       {@code <agentPluginsDir>/<name>} layout — delete any leftover
     *       symlink/dir under the harness's old per-plugin namespace.</li>
     * </ol>
     *
     * <p>Callers fill {@link #reinstall} for install/sync/upgrade flows
     * and {@link #uninstall} for remove flows. The marketplace
     * regeneration happens regardless — it's a function of current
     * store state.
     */
    record RefreshHarnessPlugins(List<String> reinstall, List<String> uninstall)
            implements SkillEffect {
        public RefreshHarnessPlugins {
            reinstall = reinstall == null ? List.of() : List.copyOf(reinstall);
            uninstall = uninstall == null ? List.of() : List.copyOf(uninstall);
        }
        public static RefreshHarnessPlugins reinstallAll(List<String> names) {
            return new RefreshHarnessPlugins(names, List.of());
        }
        public static RefreshHarnessPlugins removing(String name) {
            return new RefreshHarnessPlugins(List.of(), List.of(name));
        }
    }

    /**
     * Pull upstream into a single git-tracked skill: stash → fetch → merge → pop.
     * The {@code installSource} is materialized at plan time so dry-run output
     * shows which routing arm each skill takes:
     *
     * <ul>
     *   <li>{@link InstalledUnit.InstallSource#REGISTRY} — ask the registry for
     *       the latest version's git_sha; refuse to downgrade.</li>
     *   <li>{@link InstalledUnit.InstallSource#GIT} / {@link InstalledUnit.InstallSource#LOCAL_FILE}
     *       / {@link InstalledUnit.InstallSource#UNKNOWN} — pull the recorded
     *       branch/tag from origin (no registry contact).</li>
     * </ul>
     *
     * <p>{@code gitLatest} bypasses the routing entirely — always uses the
     * recorded {@code gitRef}.
     */
    record SyncGit(
            String unitName,
            UnitKind kind,
            InstalledUnit.InstallSource installSource,
            boolean gitLatest,
            boolean merge
    ) implements SkillEffect {}

    /** Set an error on a unit's source record. */
    record AddUnitError(String unitName, InstalledUnit.ErrorKind kind, String message) implements SkillEffect {}

    /** Drop an error from a unit's source record. */
    record ClearUnitError(String unitName, InstalledUnit.ErrorKind kind) implements SkillEffect {}

    /**
     * Reconciler effect: probe for the resolution condition for {@code kind}
     * (e.g. working tree clean for {@link InstalledUnit.ErrorKind#MERGE_CONFLICT},
     * gateway reachable for {@link InstalledUnit.ErrorKind#GATEWAY_UNAVAILABLE}).
     * Clears the error if validation passes; leaves it for the next command otherwise.
     */
    record ValidateAndClearError(String unitName, InstalledUnit.ErrorKind kind) implements SkillEffect {}

    // -------------------------------------------------------- gateway lifecycle

    /** Stop the local gateway process if running, optionally clearing agent MCP entries. */
    record StopGateway(GatewayConfig gateway) implements SkillEffect {}

    /** Persist a new gateway URL (and reload {@link GatewayConfig}). */
    record ConfigureGateway(String url) implements SkillEffect {}

    // ------------------------------------------------------ package-manager bootstrap

    /**
     * Validate that every required {@link ToolDependency} is reachable —
     * for {@link ToolDependency.Bundled} tools, install the pinned version
     * if missing; for {@link ToolDependency.External} tools, check PATH.
     * Replaces inline {@code PackageManagerRuntime} probing in {@code PlanBuilder}.
     */
    record SetupPackageManagerRuntime(List<ToolDependency> tools) implements SkillEffect {}

    /**
     * Install a specific {@link PackageManager} at {@code version} (or its
     * default if {@code version} is null). Drives {@code pm install <tool>}
     * through the effect program.
     */
    record InstallPackageManager(PackageManager pm, String version) implements SkillEffect {}

    // -------------------------------------------------- pre-flight precondition checks

    /**
     * Halt the program if {@code unitName} is already in the store —
     * lets {@code install}'s "remove first" guard live in the program
     * instead of as inline command code.
     *
     * <p>Halt is runtime-conditional (only when the unit IS present),
     * so the handler emits {@code okAndHalt} explicitly on the "already
     * installed" branch and a plain {@code ok} on the absent branch.
     * The per-status defaults here cover the rest.
     */
    record RejectIfAlreadyInstalled(String unitName) implements SkillEffect {}

    /**
     * Like {@link RejectIfAlreadyInstalled} but the unit name is the
     * top-level resolved coord — read at exec time from
     * {@link EffectContext#resolvedGraph()}. Used by the install
     * program after {@link BuildResolveGraphFromSource} runs: the
     * top-level name isn't known until the resolver matches the
     * user-supplied source to a unit name, so the use-case-build path
     * can't construct {@link RejectIfAlreadyInstalled} with a
     * concrete name.
     */
    record RejectIfTopLevelInstalled() implements SkillEffect {}

    /**
     * Categorize the install plan and enforce the
     * {@code policy.install.*} confirmation gates from inside the
     * program. Must run AFTER {@link BuildInstallPlan} so it sees the
     * same plan the rest of the program will execute.
     *
     * <p>When the plan triggers a category the policy still requires
     * confirmation for:
     * <ul>
     *   <li>{@code yes=true} (--yes flag) → halt with exit code 5
     *       ({@link ContextFact.HaltWithExitCode}) naming the
     *       {@code policy.install.*} flags to flip.</li>
     *   <li>No TTY (CI / pipe / test harness) → halt with exit code 5
     *       — interactive prompt would block.</li>
     *   <li>TTY + {@code yes=false} → prompt y/N; halt with exit
     *       code 6 if the user rejects.</li>
     * </ul>
     *
     * <p>Replaces {@code InstallCommand.checkPolicyGate}: the up-front
     * pre-resolve was only needed because the plan wasn't yet visible
     * to the command. With the resolve + plan-build inside the
     * program, the gate is just another effect.
     */
    record CheckInstallPolicyGate(boolean yes) implements SkillEffect {}

    /**
     * Capture every installed skill's MCP-dep names BEFORE any mutating
     * effect runs. The orphan-detection effect later compares this
     * snapshot against the post-mutation store to figure out which
     * MCP servers no surviving skill still declares.
     */
    record SnapshotMcpDeps() implements SkillEffect {
        // No snapshot = orphan detection runs on the post-mutation
        // store with no baseline, mis-classifying live MCP servers as
        // orphans. Halt.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Build the {@link InstallPlan} for {@code graph}, print it, store it
     * in {@link EffectContext#plan()}, and HALT if the plan has blocked
     * items. Replaces inline {@code PlanBuilder} construction in commands.
     *
     * <p>{@code graph} is optional — when {@code null}, the handler reads
     * the graph from {@link EffectContext#resolvedGraph()} set by a prior
     * {@code BuildResolveGraphFrom*} effect.
     */
    record BuildInstallPlan(
            ResolvedGraph graph,
            boolean forceScripts,
            List<String> forceScriptUnitNames,
            boolean withMcp) implements SkillEffect {
        public BuildInstallPlan() { this(null, false, List.of(), true); }
        public BuildInstallPlan(boolean forceScripts) { this(null, forceScripts, List.of(), true); }
        public BuildInstallPlan(boolean forceScripts, boolean withMcp) {
            this(null, forceScripts, List.of(), withMcp);
        }
        public BuildInstallPlan(ResolvedGraph graph) { this(graph, false, List.of(), true); }
        public BuildInstallPlan(ResolvedGraph graph, boolean forceScripts) {
            this(graph, forceScripts, List.of(), true);
        }
        public BuildInstallPlan(
                ResolvedGraph graph,
                boolean forceScripts,
                List<String> forceScriptUnitNames) {
            this(graph, forceScripts, forceScriptUnitNames, true);
        }
        public BuildInstallPlan {
            forceScriptUnitNames = forceScriptUnitNames == null
                    ? List.of()
                    : List.copyOf(forceScriptUnitNames);
        }
        // No plan = nothing to expand. Halt rather than silently skip.
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Read the plan from {@link EffectContext#plan()}, expand it via
     * {@code PlanExpander}, and run the per-action sub-program through
     * {@link dev.skillmanager.effects.LiveInterpreter#runWithContext}.
     * Plan-build at exec time means {@code sync}'s post-merge state is
     * what's expanded — finally fixing the pre-merge plan staleness.
     */
    record RunInstallPlan(GatewayConfig gateway) implements SkillEffect {}

    /**
     * Always-after cleanup: drops the resolver's staged temp dirs.
     * Belongs in {@link Program#alwaysAfter()} so it runs even after a
     * halt or failure mid-program.
     *
     * <p>{@code graph} is optional — when {@code null}, the handler reads
     * from {@link EffectContext#resolvedGraph()} (or no-ops if the
     * resolve effect never ran).
     */
    record CleanupResolvedGraph(ResolvedGraph graph) implements SkillEffect {
        public CleanupResolvedGraph() { this(null); }
    }

    /**
     * Print the {@code INSTALLED:} lines for each committed skill in the graph.
     *
     * <p>{@code graph} is optional — when {@code null}, the handler reads
     * from {@link EffectContext#resolvedGraph()}.
     */
    record PrintInstalledSummary(ResolvedGraph graph) implements SkillEffect {
        public PrintInstalledSummary() { this(null); }
    }

    /**
     * {@code sync --from <dir>} apply: copy / 3-way-merge content from a
     * local directory. Drives the diff display, the (optional) stdin
     * confirm, and either {@code Fs.copyRecursive} or
     * {@link SyncGitHandler#runMerge}. The interactive prompt is part of
     * the effect — dry-run prints a description instead.
     */
    record SyncFromLocalDir(String skillName, java.nio.file.Path fromDir,
                            boolean merge, boolean yes) implements SkillEffect {}

    // ---------------------------------------------- decomposed plan-action effects

    /** One per unique tool — the executable presence-check / bundle-install for it. */
    record EnsureTool(ToolDependency tool, boolean missingOnPath) implements SkillEffect {}

    /** One per CLI dep: run the backend installer and append the lock entry. */
    record RunCliInstall(String unitName, CliDependency dep, boolean forceScripts) implements SkillEffect {
        public RunCliInstall(String unitName, CliDependency dep) {
            this(unitName, dep, false);
        }
        // Runs a producer this codebase did not write, with
        // $SKILL_MANAGER_BIN_DIR in its environment.
        @Override public dev.skillmanager.store.WriteConfinement.Scope writeConfinement(
                dev.skillmanager.store.SkillStore store) {
            return thisHome(store, "the CLI install of " + dep.name());
        }
    }

    /** One per MCP dep: register the server with the gateway. */
    record RegisterMcpServer(String unitName, McpDependency dep, GatewayConfig gateway) implements SkillEffect {}

    // ------------------------------------------------------------ ARTI-06 build

    /**
     * {@code build}'s policy gate: the same {@code policy.install} decision
     * {@link CheckInstallPolicyGate} makes, taken from the selected dependencies
     * instead of from a resolved graph and an install plan.
     *
     * <h2>Why this is not {@link CheckInstallPolicyGate}</h2>
     *
     * <p>That gate reads {@code ctx.resolvedGraph()} and {@code ctx.plan()} and
     * returns {@code skipped} when either is absent — which is every {@code
     * build} run, because {@code build} resolves no sources and plans no
     * installs. Reusing it would have produced a gate that silently passed:
     * exactly the "probe that did not finish, recorded as a clean verdict"
     * failure this epic keeps finding. The DECISION is not duplicated — both
     * handlers call {@link dev.skillmanager.policy.PolicyGate#violations} with
     * the same {@code ! CLI} categorization line and the same
     * {@code policy.install} flags, so there is one policy and two ways of
     * arriving at its input.
     *
     * <p>A {@code skill-script} rebuild runs unbounded shell shipped by a unit
     * ({@code PlanAction.RunCliInstall.severity()} is {@code DANGER} for that
     * backend, always). {@code build} is a narrower verb than {@code sync}; it
     * is not a quieter one.
     *
     * @param deps the dependencies the run would install, already narrowed to
     *        the artifacts being rebuilt — so the gate describes THIS run and
     *        not every dep the units happen to declare
     * @param yes  as on {@code install}: not a bypass. It turns the prompt into
     *        a refusal with exit 5 naming the {@code policy.install} flag.
     */
    record CheckBuildPolicyGate(List<CliDependency> deps, boolean yes) implements SkillEffect {
        public CheckBuildPolicyGate {
            deps = deps == null ? List.of() : List.copyOf(deps);
        }
    }

    /**
     * Rebuild ONE derived CLI artifact in place — {@code build}'s only writer.
     *
     * <h2>Why this is not {@link RunCliInstall}, which does the same work</h2>
     *
     * <p>Because of what happens when a LATER effect fails.
     * {@code RunCliInstall} pairs with {@link
     * dev.skillmanager.effects.Compensation.UninstallCliIfOrphan}, and
     * {@code CliDependencyCleaner.pruneIfOrphan} excludes the rolled-back unit
     * from the claimant set — so for a dep that only its own unit declares
     * (every {@code skill-script} in a real home), rolling back removes the lock
     * row AND deletes the shim and its tree. That is right for {@code install}:
     * the unit should not be left half-installed. It is wrong for a repair: a
     * {@code build} that repaired four shims and failed on the fifth would
     * UNINSTALL the four it had just fixed, leaving the home strictly worse than
     * before the remedy was run.
     *
     * <p>So this effect deliberately yields NO compensation, and the reason is
     * not "rollback was inconvenient": there is no prior state to restore. The
     * artifact is derived, the declaration that it should exist is untouched by
     * this effect, and re-deriving it again is the only repair. The exhaustive
     * switches in {@code Executor} are what forced this to be decided rather
     * than inherited.
     *
     * @param artifactId the id this rebuild is FOR, carried so the receipt can
     *        be joined back to the artifact without re-deriving the lock key
     * @param force      the artifact-granularity {@code --force-scripts}
     */
    record RebuildCliArtifact(String artifactId, String unitName, CliDependency dep, boolean force)
            implements SkillEffect {
        // One failed rebuild must not stop the others: the whole point of the
        // verb is repairing what it can and naming what it could not.
        @Override public Continuation continuationOnFail() { return Continuation.CONTINUE; }
        // The HIS-7 path: this is the one moment a home takes ownership of an
        // inherited shim and hands the slot to a producer.
        @Override public dev.skillmanager.store.WriteConfinement.Scope writeConfinement(
                dev.skillmanager.store.SkillStore store) {
            return thisHome(store, "the rebuild of " + artifactId);
        }
    }

    // ----------------------------------------------------- store / agent removal

    /**
     * Delete a unit's directory from the store (skills/ for SKILL,
     * plugins/ for PLUGIN) and best-effort delete its installed-source
     * record.
     */
    record RemoveUnitFromStore(String unitName, UnitKind kind) implements SkillEffect {}

    /**
     * Remove {@code unitName}'s CLI lock claim for {@code dep}. If no
     * surviving installed unit still declares the same backend/requested-tool
     * identity, prune skill-manager-owned CLI artifacts too.
     */
    record PruneCliIfOrphan(String unitName, CliDependency dep) implements SkillEffect {
        // A delete path, which is why it is here: the confinement covers
        // deletes, not only writes.
        @Override public dev.skillmanager.store.WriteConfinement.Scope writeConfinement(
                dev.skillmanager.store.SkillStore store) {
            return thisHome(store, "the CLI prune for " + unitName);
        }
    }

    /**
     * Write {@code artifacts.lock.toml} from what the home holds RIGHT NOW.
     *
     * <p>Emitted before a removal, and the ordering is the whole point: a
     * prune deletes only what the ledger recorded, and after the unit is gone
     * there is nothing left to derive a record from. The tree
     * {@code cache/skill-script-<unit>-<tool>/} is credited to its unit by
     * containment from the shim that runs out of it, and the shim is one of
     * the things the removal deletes — so the moment to write it down is
     * while it is still knowable, which is here.
     */
    record RecordArtifactLedger() implements SkillEffect {}

    /**
     * Reverse-walk the artifact edges for {@code unitName} and remove what it
     * alone owned.
     *
     * <p>The half {@link PruneCliIfOrphan} does not reach: it removes a lock
     * claim and the DECLARED BINARY, and the tree the install actually wrote
     * outlives it — there is no {@code skill-script} branch in
     * {@code CliDependencyCleaner.removeArtifacts} at all. Every rule about
     * what may be deleted, and the one about {@code .git} that must not bend,
     * lives in {@link dev.skillmanager.artifacts.ArtifactPrune}.
     */
    record PruneOrphanArtifacts(String unitName) implements SkillEffect {}

    /**
     * Remove an agent's symlink (or copied dir) of {@code unitName}.
     * SKILL units are unlinked from {@link Agent#skillsDir()}; PLUGIN
     * units from {@link Agent#pluginsDir()}.
     */
    record UnlinkAgentUnit(String agentId, String unitName, UnitKind kind) implements SkillEffect {}

    /** Remove the {@code virtual-mcp-gateway} entry from a single agent's MCP config. */
    record UnlinkAgentMcpEntry(String agentId, GatewayConfig gateway) implements SkillEffect {}

    // ---------------------------------------------------------- scaffolding

    /**
     * Write the supplied file map into {@code dir}. Keys are relative file
     * names ({@code "SKILL.md"} / {@code "skill-manager.toml"}), values are
     * the rendered contents. The handler creates {@code dir} if missing
     * and writes each file (overwriting unconditionally — the command is
     * expected to gate on {@code --force}).
     */
    record ScaffoldSkill(Path dir, String skillName, java.util.Map<String, String> files)
            implements SkillEffect {}

    /**
     * Parallel to {@link ScaffoldSkill} but for plugin layouts. The file
     * map carries plugin-specific paths ({@code .claude-plugin/plugin.json},
     * {@code skill-manager-plugin.toml}, {@code skills/<name>/SKILL.md}, ...) so
     * the handler doesn't have to know plugin structure — it just writes
     * what it's given. Empty subdirs may still be represented by a
     * {@code .gitkeep} placeholder entry when callers need one.
     */
    record ScaffoldPlugin(Path dir, String pluginName, java.util.Map<String, String> files)
            implements SkillEffect {}

    /** Write {@code policy.toml} with the default policy if not present. */
    record InitializePolicy() implements SkillEffect {}

    // ----------------------------------------------------------- error report

    /**
     * Walk every {@code sources/<name>.json}, emit one
     * {@link dev.skillmanager.effects.ContextFact.OutstandingError} per
     * {@link InstalledUnit.UnitError}. Centralizes the closing report's IO
     * so an unreadable source file becomes a receipt instead of a silent
     * skip.
     */
    record LoadOutstandingErrors() implements SkillEffect {}

    /**
     * Atomically flip {@code units.lock.toml} to {@code target} at
     * {@code path}. The handler captures the prior lock as a
     * {@link Compensation.RestoreUnitsLock} pre-state shape so a
     * downstream failure walks the file back to its byte-identical
     * pre-program state.
     *
     * <p>Programs append this effect once, just before commit-finalising
     * effects. If an earlier effect fails the lock is never written.
     * If a later effect fails the compensation re-writes the prior
     * content. Either way the lock is the source-of-truth for the
     * install set and never lands in a half-applied state.
     */
    record UpdateUnitsLock(dev.skillmanager.lock.UnitsLock target, java.nio.file.Path path)
            implements SkillEffect {}

    /**
     * Best-effort project child-home refresh after a parent-home sync. The
     * effect finds registered project locks that claim any synced unit and
     * re-runs project sync for each unique project so child `.skill-manager`
     * homes mirror newly installed CLI/MCP shims and updated projected units.
     *
     * <p>Failures are advisory: the handler records
     * {@link dev.skillmanager.source.InstalledUnit.ErrorKind#PROJECT_SYNC_FAILED}
     * on the synced parent unit(s), emits one {@link ContextFact.ProjectSyncFailed}
     * per failed project, and returns PARTIAL without halting the parent sync.
     */
    record SyncClaimingProjects(
            List<String> unitNames,
            GatewayConfig gateway,
            boolean withGateway
    ) implements SkillEffect {
        public SyncClaimingProjects {
            unitNames = unitNames == null ? List.of() : List.copyOf(unitNames);
        }
    }

    // ============================================================ bindings (ticket 49)

    /**
     * Persist a {@link Binding} into the per-unit projection ledger at
     * {@code installed/<unitName>.projections.json}. The binding's
     * {@code projections} list is expected to be complete by the time
     * this effect runs — typically every {@link MaterializeProjection}
     * for the binding has already succeeded and the planner has
     * collected the resulting Projection rows into the Binding record.
     *
     * <p>Compensation is captured pre-state: the entire prior ledger
     * is snapshotted so rollback restores byte-for-byte (including
     * any other bindings for the same unit that were already present).
     */
    record CreateBinding(Binding binding) implements SkillEffect {
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Drop a {@link Binding} from the per-unit projection ledger.
     * Pre-state snapshot captures the prior ledger so rollback can
     * restore the binding row if a later teardown effect fails.
     */
    record RemoveBinding(String unitName, String bindingId) implements SkillEffect {}

    /**
     * Apply one filesystem action a {@link Binding} produced. The
     * handler dispatches on {@link Projection#kind}:
     *
     * <ul>
     *   <li>{@link dev.skillmanager.bindings.ProjectionKind#SYMLINK} —
     *       {@code Files.createSymbolicLink(destPath, sourcePath)},
     *       falling back to a recursive copy when the filesystem
     *       refuses symlinks. {@code conflictPolicy} decides what to
     *       do if {@code destPath} already exists; {@link ConflictPolicy#RENAME_EXISTING}
     *       expects a sibling {@link dev.skillmanager.bindings.ProjectionKind#RENAMED_ORIGINAL_BACKUP}
     *       projection to have been planned alongside this one.</li>
     *   <li>{@link dev.skillmanager.bindings.ProjectionKind#COPY} —
     *       recursive copy from {@code sourcePath} to {@code destPath}.</li>
     *   <li>{@link dev.skillmanager.bindings.ProjectionKind#RENAMED_ORIGINAL_BACKUP} —
     *       move the existing file/dir at {@code projection.backupOf()}
     *       to {@code projection.destPath()}. Lets the executor walk
     *       backups back independently of the SYMLINK projection.</li>
     * </ul>
     *
     * <p>Compensation: {@link dev.skillmanager.effects.Compensation.ReverseProjection}
     * reverses by {@code kind}.
     */
    record MaterializeProjection(Projection projection, ConflictPolicy conflictPolicy) implements SkillEffect {
        @Override public Continuation continuationOnFail() { return Continuation.HALT; }
    }

    /**
     * Reverse one previously materialized {@link Projection}. Used by
     * {@code unbind} and {@code uninstall} (which walks the ledger).
     *
     * <p>{@link dev.skillmanager.bindings.ProjectionKind#SYMLINK} →
     * unlink {@code destPath} if it points at {@code sourcePath} (or
     * delete the directory in the copy-fallback case).
     * {@link dev.skillmanager.bindings.ProjectionKind#COPY} → delete
     * {@code destPath}.
     * {@link dev.skillmanager.bindings.ProjectionKind#RENAMED_ORIGINAL_BACKUP}
     * → move {@code projection.destPath} (the backup) back to
     * {@code projection.backupOf} (the original path).
     *
     * <p>No post-state compensation: unmaterialize is a teardown,
     * its own failures fail forward.
     */
    record UnmaterializeProjection(Projection projection) implements SkillEffect {}

    /**
     * Reconcile every binding for a doc-repo (#48) against the upstream
     * source bytes in the store. Handler walks the projection ledger
     * for {@code unitName}, routes each {@code MANAGED_COPY} projection
     * through the four-state drift matrix
     * ({@link dev.skillmanager.bindings.SyncDecision}), and reapplies
     * {@code IMPORT_DIRECTIVE} rows idempotently. Emits one
     * {@link ContextFact.DocBindingSynced} per visited binding so the
     * renderer can surface upgrade / locally-edited / conflict /
     * orphan-* states uniformly.
     *
     * <p>{@code force} clobbers locally-edited and conflict destinations
     * (lost edits are reported via a {@code Severity.WARN} fact); the
     * default is preserve-with-warning.
     *
     * <p>No compensation: sync is forward-only. A partway failure on
     * one binding stops nothing — subsequent bindings still get a
     * chance — and the receipt's {@link EffectStatus#PARTIAL} surfaces
     * the per-binding errors without blocking the rest of the program.
     */
    record SyncDocRepo(String unitName, boolean force) implements SkillEffect {}

    /**
     * Reconcile a harness instance against its (potentially updated)
     * template. Re-runs {@link dev.skillmanager.bindings.HarnessInstantiator}
     * with the same {@code instanceId} so stable harness binding ids
     * (e.g. {@code harness:<instanceId>:repo-intel}) get replaced in
     * place; projections rewrite via {@link
     * dev.skillmanager.bindings.ConflictPolicy#OVERWRITE}. Orphan
     * harness bindings (units removed from the template since the
     * last instantiate) are torn down by walking the existing
     * {@code harness:<instanceId>:*} ledger entries and emitting
     * {@link UnmaterializeProjection} / {@link RemoveBinding} for
     * the ones not present in the new plan.
     *
     * <p>Idempotent: re-running against an up-to-date instance is a
     * no-op modulo overwriting same-bytes files.
     */
    record SyncHarness(String harnessName, String instanceId) implements SkillEffect {}
}
