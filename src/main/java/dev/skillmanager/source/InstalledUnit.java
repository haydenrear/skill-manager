package dev.skillmanager.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.model.UnitKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-unit metadata persisted to {@code $SKILL_MANAGER_HOME/installed/<name>.json}.
 * Records where a unit was installed from, what version landed, and any
 * outstanding errors the reconciler has surfaced.
 *
 * <p>Replaces the legacy {@code SkillSource}. Adds a {@link UnitKind}
 * ({@link #unitKind()}) so plugin and skill installs share one record
 * shape; legacy records (no {@code unitKind} field on disk)
 * deserialize as {@link UnitKind#SKILL}.
 *
 * <p>The pre-existing {@link #kind()} accessor — naming the
 * <em>storage transport</em> ({@link Kind#GIT} / {@link Kind#LOCAL_DIR}
 * / {@link Kind#UNKNOWN}) — keeps its meaning unchanged so the
 * dozens of callers reading it don't have to migrate. The new SKILL /
 * PLUGIN distinction lives on {@link #unitKind()}.
 *
 * <p>Migration from {@code sources/<name>.json} → {@code installed/<name>.json}
 * runs automatically on the next {@link dev.skillmanager.lifecycle.SkillReconciler}
 * pass after upgrade — see {@link UnitStore#migrateFromLegacy}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledUnit(
        String name,
        String version,
        Kind kind,
        /**
         * Where the install came from — drives sync/upgrade routing.
         * {@link InstallSource#REGISTRY} → server is the source of truth for
         * version + git_sha. {@link InstallSource#GIT} / {@link
         * InstallSource#LOCAL_FILE} → git remote (origin) is the source.
         */
        InstallSource installSource,
        String origin,
        String gitHash,
        /** Branch / tag the install tracks for {@code sync --git-latest}; null when sha-detached. */
        String gitRef,
        String installedAt,
        List<UnitError> errors,
        /**
         * Plugin vs. skill. Defaults to {@link UnitKind#SKILL} for legacy
         * records that predate this field. Set to {@link UnitKind#PLUGIN}
         * by plugin installs (ticket 08+).
         */
        UnitKind unitKind
) {
    public InstalledUnit {
        errors = errors == null ? new ArrayList<>() : new ArrayList<>(errors);
        if (unitKind == null) unitKind = UnitKind.SKILL;
    }

    /** Storage transport — how the unit's bytes landed on disk. */
    public enum Kind { GIT, LOCAL_DIR, UNKNOWN }

    public enum InstallSource {
        /** {@code install <name>[@version]} via the skill registry. */
        REGISTRY,
        /** {@code install github:user/repo} or git+/ssh git URL. */
        GIT,
        /** {@code install file:<path>} or {@code install ./path}. */
        LOCAL_FILE,
        /** Pre-tracking install — onboarded by the reconciler. */
        UNKNOWN
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UnitError(ErrorKind kind, String message, String firstSeenAt) {}

    public enum ErrorKind {
        /** Gateway didn't respond — MCP register / unregister couldn't run. */
        GATEWAY_UNAVAILABLE,
        /** {@link dev.skillmanager.mcp.McpWriter#registerAll} reported an error for an MCP dep. */
        MCP_REGISTRATION_FAILED,
        /** Sync left UU files in the working tree (3-way merge or stash-pop conflict). */
        MERGE_CONFLICT,
        /** Git-tracked but no origin remote — sync/upgrade has nothing to fetch from. */
        NO_GIT_REMOTE,
        /** Unit has no .git/ in the store — can't sync or upgrade until reinstalled from a git source. */
        NEEDS_GIT_MIGRATION,
        /** {@link InstallSource#REGISTRY} install but the registry was unreachable. */
        REGISTRY_UNAVAILABLE,
        /** A specific agent's symlink/copy of this unit failed — message carries the agent id. */
        AGENT_SYNC_FAILED
    }

    @JsonIgnore
    public boolean isGit() {
        return kind == Kind.GIT && gitHash != null && !gitHash.isBlank();
    }

    @JsonIgnore
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    @JsonIgnore
    public boolean hasError(ErrorKind k) {
        if (errors == null) return false;
        for (UnitError e : errors) if (e.kind() == k) return true;
        return false;
    }

    public InstalledUnit withErrorAdded(UnitError e) {
        List<UnitError> next = new ArrayList<>(errors == null ? List.of() : errors);
        next.removeIf(existing -> existing.kind() == e.kind());
        next.add(e);
        return new InstalledUnit(name, version, kind, installSource, origin, gitHash, gitRef, installedAt, next, unitKind);
    }

    public InstalledUnit withErrorRemoved(ErrorKind k) {
        List<UnitError> next = new ArrayList<>(errors == null ? List.of() : errors);
        next.removeIf(existing -> existing.kind() == k);
        return new InstalledUnit(name, version, kind, installSource, origin, gitHash, gitRef, installedAt, next, unitKind);
    }

    public InstalledUnit withGitMoved(String newHash, String at) {
        return new InstalledUnit(name, version, kind, installSource, origin, newHash, gitRef, at, errors, unitKind);
    }
}
