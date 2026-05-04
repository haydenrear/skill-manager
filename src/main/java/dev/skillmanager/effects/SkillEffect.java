package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.SkillSource;
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
        SkillEffect.CommitSkillsToStore,
        SkillEffect.RecordAuditPlan,
        SkillEffect.RecordSourceProvenance,
        SkillEffect.OnboardSource,
        SkillEffect.ResolveTransitives,
        SkillEffect.EnsureTool,
        SkillEffect.RunCliInstall,
        SkillEffect.RegisterMcpServer,
        SkillEffect.UnregisterMcpOrphan,
        SkillEffect.SyncAgents,
        SkillEffect.SyncGit,
        SkillEffect.RemoveSkillFromStore,
        SkillEffect.UnlinkAgentSkill,
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
    record EnsureGateway(GatewayConfig gateway) implements SkillEffect {}

    /**
     * Move every staged skill in the {@link ResolvedGraph} into the store.
     * Failure mode: copy throws midway → handler best-effort removes any
     * skill dirs it just created so a rerun starts clean. The receipt
     * lists which names committed for downstream effects (provenance,
     * post-update) to reference.
     */
    record CommitSkillsToStore(ResolvedGraph graph) implements SkillEffect {}

    /** Append the install plan to the audit log under {@code verb} ({@code "install"} / {@code "sync"} / etc.). */
    record RecordAuditPlan(InstallPlan plan, String verb) implements SkillEffect {}

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

    /** Refresh agent symlinks + MCP-config entries for every known agent. */
    record SyncAgents(List<Skill> skills, GatewayConfig gateway) implements SkillEffect {}

    /**
     * Pull upstream into a single git-tracked skill: stash → fetch → merge → pop.
     * The {@code installSource} is materialized at plan time so dry-run output
     * shows which routing arm each skill takes:
     *
     * <ul>
     *   <li>{@link SkillSource.InstallSource#REGISTRY} — ask the registry for
     *       the latest version's git_sha; refuse to downgrade.</li>
     *   <li>{@link SkillSource.InstallSource#GIT} / {@link SkillSource.InstallSource#LOCAL_FILE}
     *       / {@link SkillSource.InstallSource#UNKNOWN} — pull the recorded
     *       branch/tag from origin (no registry contact).</li>
     * </ul>
     *
     * <p>{@code gitLatest} bypasses the routing entirely — always uses the
     * recorded {@code gitRef}.
     */
    record SyncGit(
            String skillName,
            SkillSource.InstallSource installSource,
            boolean gitLatest,
            boolean merge
    ) implements SkillEffect {}

    /** Set an error on a skill's source record. */
    record AddSkillError(String skillName, SkillSource.ErrorKind kind, String message) implements SkillEffect {}

    /** Drop an error from a skill's source record. */
    record ClearSkillError(String skillName, SkillSource.ErrorKind kind) implements SkillEffect {}

    /**
     * Reconciler effect: probe for the resolution condition for {@code kind}
     * (e.g. working tree clean for {@link SkillSource.ErrorKind#MERGE_CONFLICT},
     * gateway reachable for {@link SkillSource.ErrorKind#GATEWAY_UNAVAILABLE}).
     * Clears the error if validation passes; leaves it for the next command otherwise.
     */
    record ValidateAndClearError(String skillName, SkillSource.ErrorKind kind) implements SkillEffect {}

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
     * {@link SkillSource.SkillError}. Centralizes the closing report's IO
     * so an unreadable source file becomes a receipt instead of a silent
     * skip.
     */
    record LoadOutstandingErrors() implements SkillEffect {}
}
