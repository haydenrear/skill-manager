package dev.skillmanager.effects;

import dev.skillmanager.mcp.InstallResult;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.source.InstalledUnit;

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

    /**
     * Carry a specific process exit code out of a {@link EffectStatus#HALTED}
     * effect. Decoders translate this into the command-level exit code so
     * effects like {@link SkillEffect.CheckInstallPolicyGate} (exit 5/6),
     * {@link SkillEffect.BuildResolveGraphFromSource} (auth → 9, generic
     * fetch → 10), and similar can encode a typed failure mode that
     * persists through the report rather than collapsing into the
     * default "halted" exit.
     */
    record HaltWithExitCode(int code, String reason) implements ContextFact {}

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
    record SkillOnboarded(String skillName, InstalledUnit.Kind kind) implements ContextFact {}

    // ---- Transitives ----
    record TransitiveInstalled(String name) implements ContextFact {}
    /**
     * One transitive resolve in a batch failed. Emitted once per
     * failure so {@link ConsoleProgramRenderer} can list every
     * problem in one run (vs. fail-fast on the first one, which
     * masks the rest).
     *
     * @param coord       the source string we tried to resolve
     *                    (registry name, github:owner/repo, file:..., etc.)
     * @param requestedBy unit that declared this transitive ref —
     *                    null for top-level coords the user passed
     *                    directly on the CLI
     * @param reason      one-line summary of the failure (auth refused,
     *                    not found, network, etc.); the full message
     *                    is on the underlying exception
     */
    record TransitiveFailed(String coord, String requestedBy, String reason) implements ContextFact {}

    /**
     * Summary fact emitted by every {@code BuildResolveGraphFrom*}
     * effect once the resolver has run, so the renderer can show
     * "resolved N skills, M failures" without summing facts itself.
     */
    record GraphResolved(int resolved, int failures) implements ContextFact {}

    /**
     * Onboard's discovery facts. Emitted per bundled-skill decision
     * so the user sees which ones the effect picked up vs skipped.
     */
    record BundledSkillFound(String publishedName, String sourcePath) implements ContextFact {}
    record BundledSkillAlreadyInstalled(String publishedName, String storePath) implements ContextFact {}
    record BundledSkillMissing(String publishedName, String expectedPath) implements ContextFact {}
    record BundledSkillFromGithub(String publishedName, String coord) implements ContextFact {}

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
    record SyncGitRefused(String skillName, String upstream, boolean gitLatest) implements ContextFact {}
    record SyncGitConflicted(String skillName, java.util.List<String> conflictedFiles) implements ContextFact {}
    record SyncGitFailed(String skillName, String reason) implements ContextFact {}
    record SyncGitNotGitTracked(String skillName) implements ContextFact {}
    record SyncGitNoOrigin(String skillName) implements ContextFact {}
    record SyncGitRegistryUnavailable(String skillName) implements ContextFact {}
    /** Registry rejected the cached bearer + couldn't refresh — user must {@code skill-manager login}. */
    record SyncGitAuthRequired(String skillName, String message) implements ContextFact {}
    record SyncGitNoUpgradeNeeded(String skillName, String version) implements ContextFact {}

    // ---- Error management (sources/<name>.json mutations) ----
    record ErrorAdded(String skillName, InstalledUnit.ErrorKind kind) implements ContextFact {}
    record ErrorCleared(String skillName, InstalledUnit.ErrorKind kind) implements ContextFact {}
    record ErrorValidated(String skillName, InstalledUnit.ErrorKind kind, boolean cleared)
            implements ContextFact {}

    /**
     * One-per-error fact emitted by the {@link SkillEffect.LoadOutstandingErrors}
     * effect — the closing report walks these to render the "skills with
     * outstanding errors" banner.
     */
    record OutstandingError(String skillName, InstalledUnit.ErrorKind kind, String message)
            implements ContextFact {}

    /**
     * One markdown frontmatter {@code skill-imports} validation problem.
     * The validation effect is advisory: it reports every violation but
     * does not halt install/sync/publish flows.
     */
    record MarkdownImportViolation(String unitName, String unitKind, String file, String message)
            implements ContextFact {}

    /**
     * One installed unit directory existed but could not be parsed. The
     * operation continues with the readable units, but the user should
     * fix the broken unit so future dependency and projection scans see it.
     */
    record CantReadUnit(String unitName, String unitKind, String path, String message)
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
    /** Carries the full {@link InstallResult} so the renderer can emit the JSON block + summary. */
    record McpServerRegistered(String skillName, InstallResult result) implements ContextFact {}
    record McpServerRegistrationFailed(String skillName, InstallResult result) implements ContextFact {}

    // ---- units.lock.toml ----
    record UnitsLockUpdated(String path, int unitCount) implements ContextFact {}
    record UnitsLockRestored(String path) implements ContextFact {}

    // ---- Harness plugin marketplace + CLI ----
    /**
     * The skill-manager-owned plugin marketplace at {@code path} has
     * been regenerated to list {@code pluginCount} plugins.
     */
    record PluginMarketplaceRegenerated(String path, int pluginCount) implements ContextFact {}
    /**
     * One harness CLI completed a per-plugin operation.
     * {@code op} is one of {@code "install"}, {@code "uninstall"},
     * {@code "marketplace-add"}, {@code "marketplace-update"}.
     */
    record HarnessPluginCli(String agentId, String pluginName, String op, boolean ok, String message)
            implements ContextFact {}
    /**
     * One harness CLI was not on PATH and the plugin couldn't register.
     * {@code installHint} is the {@code brew install <bin>} command the
     * banner surfaces.
     */
    record HarnessCliMissing(String agentId, String binary, String installHint) implements ContextFact {}

    // ---- Bindings + projection ledger (ticket 49) ----
    /** {@code installed/<unit>.projections.json} now lists this binding. */
    record BindingCreated(String unitName, String bindingId, String targetRoot, String subElement)
            implements ContextFact {}
    /** {@code installed/<unit>.projections.json} no longer lists this binding. */
    record BindingRemoved(String unitName, String bindingId) implements ContextFact {}
    /** One filesystem action a binding produced was applied. */
    record ProjectionMaterialized(String bindingId, String destPath, String kind) implements ContextFact {}
    /** One filesystem action was reversed (unbind / uninstall / rollback). */
    record ProjectionUnmaterialized(String bindingId, String destPath, String kind) implements ContextFact {}
    /** {@link dev.skillmanager.bindings.ConflictPolicy#SKIP} fired — destination already occupied. */
    record ProjectionSkippedConflict(String bindingId, String destPath) implements ContextFact {}

    /**
     * One per-binding outcome from {@link SkillEffect.SyncDocRepo}.
     * {@code severity} drives the renderer (INFO = silent or ok line,
     * WARN = warning, ERROR = errored). {@code description} carries
     * the human-readable action ("upgraded", "preserved local edits",
     * "orphan source", etc.).
     */
    record DocBindingSynced(
            String unitName,
            String bindingId,
            String subElement,
            String description,
            Severity severity) implements ContextFact {
        public enum Severity { INFO, WARN, ERROR }
    }

    /**
     * Per-binding outcome from {@link SkillEffect.SyncHarness}.
     * {@code action} is one of: APPLIED (new or replaced binding
     * landed), UPGRADED (existing binding's bytes/symlink refreshed),
     * REMOVED (binding was in the ledger but no longer in the
     * template — torn down), FAILED (couldn't apply / remove).
     */
    record HarnessBindingSynced(
            String harnessName,
            String instanceId,
            String bindingId,
            String unitName,
            Action action,
            String description) implements ContextFact {
        public enum Action { APPLIED, UPGRADED, REMOVED, FAILED }
    }
}
