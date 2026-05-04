package dev.skillmanager.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillSource(
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
        List<SkillError> errors
) {
    public SkillSource {
        errors = errors == null ? new ArrayList<>() : new ArrayList<>(errors);
    }

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
    public record SkillError(ErrorKind kind, String message, String firstSeenAt) {}

    public enum ErrorKind {
        /** Gateway didn't respond — MCP register / unregister couldn't run. */
        GATEWAY_UNAVAILABLE,
        /** {@link dev.skillmanager.mcp.McpWriter#registerAll} reported an error for an MCP dep. */
        MCP_REGISTRATION_FAILED,
        /** Sync left UU files in the working tree (3-way merge or stash-pop conflict). */
        MERGE_CONFLICT,
        /** Git-tracked but no origin remote — sync/upgrade has nothing to fetch from. */
        NO_GIT_REMOTE,
        /** Skill has no .git/ in the store — can't sync or upgrade until reinstalled from a git source. */
        NEEDS_GIT_MIGRATION,
        /** {@link InstallSource#REGISTRY} install but the registry was unreachable. */
        REGISTRY_UNAVAILABLE,
        /** A specific agent's symlink/copy of this skill failed — message carries the agent id. */
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
        for (SkillError e : errors) if (e.kind() == k) return true;
        return false;
    }

    public SkillSource withErrorAdded(SkillError e) {
        List<SkillError> next = new ArrayList<>(errors == null ? List.of() : errors);
        next.removeIf(existing -> existing.kind() == e.kind());
        next.add(e);
        return new SkillSource(name, version, kind, installSource, origin, gitHash, gitRef, installedAt, next);
    }

    public SkillSource withErrorRemoved(ErrorKind k) {
        List<SkillError> next = new ArrayList<>(errors == null ? List.of() : errors);
        next.removeIf(existing -> existing.kind() == k);
        return new SkillSource(name, version, kind, installSource, origin, gitHash, gitRef, installedAt, next);
    }

    public SkillSource withGitMoved(String newHash, String at) {
        return new SkillSource(name, version, kind, installSource, origin, newHash, gitRef, at, errors);
    }
}
