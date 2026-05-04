package dev.skillmanager.effects;

import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.source.SkillSource;

/**
 * Typed outcome each effect handler emits on its receipt — replaces the
 * stringly-typed {@code Map<String,Object>} that decoders used to grep.
 *
 * <p>Every fact is a sealed record so decoders, summary printers, and the
 * reconciler all match exhaustively. New outcomes get a new record (and a
 * compile error in every consumer), not a new magic key.
 *
 * <p>Facts are emitted at the granularity the user cares about — for
 * example a {@link AgentSkillSynced} per (agent, skill) pair, not one
 * blob per agent. That keeps the "which skill failed for which agent"
 * answer in the receipts themselves.
 */
public sealed interface ContextFact {

    /** DryRunInterpreter emits this on every effect so decoders can detect dry-run. */
    record DryRun() implements ContextFact {}

    // ---- Pre-flight ----
    record RegistryConfigured(String url) implements ContextFact {}
    record GatewayAlreadyRunning() implements ContextFact {}
    record GatewayStarted(String host, int port) implements ContextFact {}
    record GatewayUnreachable(String host) implements ContextFact {}

    // ---- Commit / audit / provenance ----
    record SkillCommitted(String name) implements ContextFact {}
    record CommitRolledBack(String name) implements ContextFact {}
    record AuditRecorded(String verb) implements ContextFact {}
    record ProvenanceRecorded(int count) implements ContextFact {}

    // ---- Onboard (reconciler) ----
    record SkillOnboarded(String skillName, SkillSource.Kind kind) implements ContextFact {}

    // ---- Transitives ----
    record TransitiveInstalled(String name) implements ContextFact {}
    record TransitiveFailed(String coord) implements ContextFact {}

    // ---- Tools / CLI deps ----
    record ToolsInstalledFor(int skillCount) implements ContextFact {}
    record CliInstalledFor(int skillCount) implements ContextFact {}

    // ---- MCP gateway ----
    record McpRegistered(String skillName, String serverId) implements ContextFact {}
    record McpRegistrationFailed(String skillName, String serverId, String message) implements ContextFact {}
    record OrphanUnregistered(String serverId) implements ContextFact {}

    // ---- Agents — per-(agent, skill) ----
    record AgentSkillSynced(String agentId, String skillName) implements ContextFact {}
    record AgentSkillSyncFailed(String agentId, String skillName, String message) implements ContextFact {}
    record AgentMcpConfigChanged(String agentId, McpWriter.ConfigChange change, String configPath)
            implements ContextFact {}
    record AgentMcpConfigFailed(String agentId, String message) implements ContextFact {}

    // ---- Sync git ----
    record SyncGitUpToDate(String skillName, String label) implements ContextFact {}
    record SyncGitMerged(String skillName, String fetchedHash) implements ContextFact {}
    record SyncGitRefused(String skillName) implements ContextFact {}
    record SyncGitConflicted(String skillName) implements ContextFact {}
    record SyncGitFailed(String skillName, String reason) implements ContextFact {}
    record SyncGitNotGitTracked(String skillName) implements ContextFact {}
    record SyncGitNoOrigin(String skillName) implements ContextFact {}
    record SyncGitRegistryUnavailable(String skillName) implements ContextFact {}
    record SyncGitNoUpgradeNeeded(String skillName, String version) implements ContextFact {}

    // ---- Error management (sources/<name>.json mutations) ----
    record ErrorAdded(String skillName, SkillSource.ErrorKind kind) implements ContextFact {}
    record ErrorCleared(String skillName, SkillSource.ErrorKind kind) implements ContextFact {}
    record ErrorValidated(String skillName, SkillSource.ErrorKind kind, boolean cleared)
            implements ContextFact {}

    /**
     * One-per-error fact emitted by the {@link SkillEffect.LoadOutstandingErrors}
     * effect — the closing report walks these to render the "skills with
     * outstanding errors" banner.
     */
    record OutstandingError(String skillName, SkillSource.ErrorKind kind, String message)
            implements ContextFact {}

    // ---- Skill-store mutations ----
    record SkillRemovedFromStore(String name) implements ContextFact {}
    record AgentSkillUnlinked(String agentId, String skillName) implements ContextFact {}
    record AgentSkillUnlinkFailed(String agentId, String skillName, String message) implements ContextFact {}
    record AgentMcpEntryRemoved(String agentId) implements ContextFact {}

    // ---- Gateway lifecycle ----
    record GatewayStopped() implements ContextFact {}
    record GatewayConfigured(String url) implements ContextFact {}

    // ---- Scaffolding / config bootstrap ----
    record SkillScaffolded(String name, String path) implements ContextFact {}
    record PolicyInitialized(String path) implements ContextFact {}

    // ---- Package-manager runtime ----
    record PackageManagerReady(String pmId, String version, boolean wasMissing) implements ContextFact {}
    record PackageManagerUnavailable(String pmId, String message) implements ContextFact {}
    record PackageManagerInstalled(String pmId, String version, String installPath) implements ContextFact {}

    // ---- Decomposed plan-action effects ----
    record ToolEnsured(String toolId, boolean missingOnPath, boolean bundled) implements ContextFact {}
    record CliInstalled(String skillName, String depName, String backend) implements ContextFact {}
    record CliInstallFailed(String skillName, String depName, String message) implements ContextFact {}
    record McpServerRegistered(String skillName, String serverId) implements ContextFact {}
    record McpServerRegistrationFailed(String skillName, String serverId, String message) implements ContextFact {}
}
