package dev.skillmanager.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provenance for one installed skill: what it was installed from, what
 * upstream commit (if any) it was last pinned to, and what version that
 * was. Stored as JSON at {@code <store>/sources/<name>.json} so file ops
 * on the skill directory itself (delete + copy during sync / upgrade /
 * uninstall) don't blow it away.
 *
 * <p>Used for:
 *
 * <ul>
 *   <li>Detecting whether the user has local edits (working tree dirty,
 *       commits ahead of {@link #gitHash}) before {@code sync --from} or
 *       {@code upgrade} would overwrite them.</li>
 *   <li>Emitting actionable merge instructions when local edits exist —
 *       agents and humans need the exact upstream URL + ref to pull /
 *       merge.</li>
 *   <li>(Future) Driving registry-supplied hash upgrades end-to-end:
 *       the registry returns the hash for the new version, the CLI
 *       fetches + merges it onto the local edits.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillSource(
        String name,
        String version,
        Kind kind,
        /** For {@link Kind#GIT}: the upstream URL. For {@link Kind#LOCAL_DIR}: the absolute path. */
        String origin,
        /** SHA the skill was last pinned to. Null when {@link #kind} is not git-backed. */
        String gitHash,
        /** ISO-8601 timestamp (string for cross-version JSON portability without a jsr310 dep). */
        String installedAt
) {
    /**
     * Where the skill was installed from. Determines whether sync /
     * upgrade has a meaningful merge story to fall back on, vs. having
     * to ask the user to deal with diffs by hand.
     */
    public enum Kind {
        /** Cloned from a git URL (github:, git+, .git, ssh://). Has .git/. */
        GIT,
        /** Copied from a local directory (./path, file:<path>). Local-dir installs do not get .git tracking by default. */
        LOCAL_DIR,
        /** Source kind not recorded — pre-tracking install. Treat as opaque. */
        UNKNOWN
    }

    @JsonIgnore
    public boolean isGit() {
        return kind == Kind.GIT && gitHash != null && !gitHash.isBlank();
    }
}
