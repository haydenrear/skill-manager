package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.pm.PackageManager;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.InstalledUnit;
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
        SkillEffect.RejectIfTopLevelInstalled,
        SkillEffect.CheckInstallPolicyGate {

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
            List<dev.skillmanager.model.Skill> liveSkills) implements SkillEffect {}

    /**
     * Append the install plan to the audit log under {@code verb}
     * ({@code "install"} / {@code "sync"} / etc.). Reads the plan from
     * {@link EffectContext#plan()} — must run after {@link BuildInstallPlan}.
     */
    record RecordAuditPlan(String verb) implements SkillEffect {}

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
    record InstallCli(List<AgentUnit> units) implements SkillEffect {}

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
    record BuildInstallPlan(ResolvedGraph graph) implements SkillEffect {
        public BuildInstallPlan() { this(null); }
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
    record RunCliInstall(String unitName, CliDependency dep) implements SkillEffect {}

    /** One per MCP dep: register the server with the gateway. */
    record RegisterMcpServer(String unitName, McpDependency dep, GatewayConfig gateway) implements SkillEffect {}

    // ----------------------------------------------------- store / agent removal

    /**
     * Delete a unit's directory from the store (skills/ for SKILL,
     * plugins/ for PLUGIN) and best-effort delete its installed-source
     * record.
     */
    record RemoveUnitFromStore(String unitName, UnitKind kind) implements SkillEffect {}

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
     * {@code skill-manager-plugin.toml}, {@code skills/.gitkeep}, ...) so
     * the handler doesn't have to know plugin structure — it just writes
     * what it's given. Empty subdirs are represented by a {@code .gitkeep}
     * placeholder entry in the map (e.g. {@code "skills/.gitkeep" → ""}).
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
}
