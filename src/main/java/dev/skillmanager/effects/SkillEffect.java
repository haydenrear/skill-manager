package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
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
        SkillEffect.CommitSkillsToStore,
        SkillEffect.RecordAuditPlan,
        SkillEffect.RecordSourceProvenance,
        SkillEffect.OnboardSource,
        SkillEffect.ResolveTransitives,
        SkillEffect.EnsureTool,
        SkillEffect.RunCliInstall,
        SkillEffect.RegisterMcpServer,
        SkillEffect.UnregisterMcpOrphan,
        SkillEffect.UnregisterMcpOrphans,
        SkillEffect.SyncAgents,
        SkillEffect.SyncGit,
        SkillEffect.RemoveSkillFromStore,
        SkillEffect.UnlinkAgentSkill,
        SkillEffect.UnlinkAgentMcpEntry,
        SkillEffect.ScaffoldSkill,
        SkillEffect.InitializePolicy,
        SkillEffect.LoadOutstandingErrors,
        SkillEffect.AddSkillError,
        SkillEffect.ClearSkillError,
        SkillEffect.ValidateAndClearError,
        SkillEffect.InstallTools,
        SkillEffect.InstallCli,
        SkillEffect.RegisterMcp {

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
     * Move every staged skill in the {@link ResolvedGraph} into the store.
     * Failure mode: copy throws midway → handler best-effort removes any
     * skill dirs it just created so a rerun starts clean. The receipt
     * lists which names committed for downstream effects (provenance,
     * post-update) to reference.
     */
    record CommitSkillsToStore(ResolvedGraph graph) implements SkillEffect {}

    /**
     * Append the install plan to the audit log under {@code verb}
     * ({@code "install"} / {@code "sync"} / etc.). Reads the plan from
     * {@link EffectContext#plan()} — must run after {@link BuildInstallPlan}.
     */
    record RecordAuditPlan(String verb) implements SkillEffect {}

    /** Walk the committed graph and write {@code sources/<name>.json} for each skill. */
    record RecordSourceProvenance(ResolvedGraph graph) implements SkillEffect {}

    /** Write a {@code sources/<name>.json} record for an installed skill that lacks one. */
    record OnboardSource(Skill skill) implements SkillEffect {}

    /** Recursively install missing transitive {@code skill_references}. */
    record ResolveTransitives(List<Skill> skills) implements SkillEffect {}

    /** Build the install plan from {@code skills} and run runtime-tool installers (uv / npm / docker / brew). */
    record InstallTools(List<Skill> skills) implements SkillEffect {}

    /** Build the install plan from {@code skills} and run CLI dep installers. */
    record InstallCli(List<Skill> skills) implements SkillEffect {}

    /** Register every skill's MCP deps with the gateway, capturing per-server outcomes. */
    record RegisterMcp(List<Skill> skills, GatewayConfig gateway) implements SkillEffect {}

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

    /** Refresh agent symlinks + MCP-config entries for every known agent. */
    record SyncAgents(List<Skill> skills, GatewayConfig gateway) implements SkillEffect {}

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
            String skillName,
            InstalledUnit.InstallSource installSource,
            boolean gitLatest,
            boolean merge
    ) implements SkillEffect {}

    /** Set an error on a skill's source record. */
    record AddSkillError(String skillName, InstalledUnit.ErrorKind kind, String message) implements SkillEffect {}

    /** Drop an error from a skill's source record. */
    record ClearSkillError(String skillName, InstalledUnit.ErrorKind kind) implements SkillEffect {}

    /**
     * Reconciler effect: probe for the resolution condition for {@code kind}
     * (e.g. working tree clean for {@link InstalledUnit.ErrorKind#MERGE_CONFLICT},
     * gateway reachable for {@link InstalledUnit.ErrorKind#GATEWAY_UNAVAILABLE}).
     * Clears the error if validation passes; leaves it for the next command otherwise.
     */
    record ValidateAndClearError(String skillName, InstalledUnit.ErrorKind kind) implements SkillEffect {}

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
     * Halt the program if {@code skillName} is already in the store —
     * lets {@code install}'s "remove first" guard live in the program
     * instead of as inline command code.
     */
    record RejectIfAlreadyInstalled(String skillName) implements SkillEffect {}

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
    record RunCliInstall(String skillName, CliDependency dep) implements SkillEffect {}

    /** One per MCP dep: register the server with the gateway. */
    record RegisterMcpServer(String skillName, McpDependency dep, GatewayConfig gateway) implements SkillEffect {}

    // ----------------------------------------------------- store / agent removal

    /** Delete a skill's directory from the store (and best-effort delete its source record). */
    record RemoveSkillFromStore(String skillName) implements SkillEffect {}

    /** Remove an agent's symlink (or copied dir) of {@code skillName}. */
    record UnlinkAgentSkill(String agentId, String skillName) implements SkillEffect {}

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
}
