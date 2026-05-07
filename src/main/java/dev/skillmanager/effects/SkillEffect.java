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
        SkillEffect.UpdateUnitsLock {

    /**
     * Persist a registry URL override and reload {@link
     * dev.skillmanager.registry.RegistryConfig}. Failure modes: malformed
     * URI, unwritable store root.
     */
    record ConfigureRegistry(String url) implements SkillEffect {}

    /**
     * Probe the gateway; if local and not running, start it and wait for
     * {@code /health}. Failure modes: gateway not local + unreachable, or
     * local-start exceeded the health-check timeout.
     */
    record EnsureGateway(GatewayConfig gateway, java.time.Duration timeout) implements SkillEffect {
        public EnsureGateway(GatewayConfig gateway) {
            this(gateway, java.time.Duration.ofSeconds(20));
        }
    }

    /**
     * Move every staged unit in the {@link ResolvedGraph} into the store,
     * routing each to {@code skills/<name>} or {@code plugins/<name>} per
     * {@link AgentUnit#kind()}. Failure mode: copy throws midway → handler
     * best-effort removes any unit dirs it just created so a rerun starts
     * clean. The receipt lists which names committed for downstream
     * effects (provenance, post-update) to reference.
     */
    record CommitUnitsToStore(ResolvedGraph graph) implements SkillEffect {}

    /**
     * Append the install plan to the audit log under {@code verb}
     * ({@code "install"} / {@code "sync"} / etc.). Reads the plan from
     * {@link EffectContext#plan()} — must run after {@link BuildInstallPlan}.
     */
    record RecordAuditPlan(String verb) implements SkillEffect {}

    /** Walk the committed graph and write {@code sources/<name>.json} for each skill. */
    record RecordSourceProvenance(ResolvedGraph graph) implements SkillEffect {}

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
     */
    record RejectIfAlreadyInstalled(String unitName) implements SkillEffect {}

    /**
     * Capture every installed skill's MCP-dep names BEFORE any mutating
     * effect runs. The orphan-detection effect later compares this
     * snapshot against the post-mutation store to figure out which
     * MCP servers no surviving skill still declares.
     */
    record SnapshotMcpDeps() implements SkillEffect {}

    /**
     * Build the {@link InstallPlan} for {@code graph}, print it, store it
     * in {@link EffectContext#plan()}, and HALT if the plan has blocked
     * items. Replaces inline {@code PlanBuilder} construction in commands.
     */
    record BuildInstallPlan(ResolvedGraph graph) implements SkillEffect {}

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
     */
    record CleanupResolvedGraph(ResolvedGraph graph) implements SkillEffect {}

    /** Print the {@code INSTALLED:} lines for each committed skill in the graph. */
    record PrintInstalledSummary(ResolvedGraph graph) implements SkillEffect {}

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
