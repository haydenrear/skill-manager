package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * One declared group of <em>vendored</em> paths: files or directories that live
 * in the project's own working tree but whose content belongs to an installed
 * unit, and which are therefore expected to be links into the project's own
 * skill-manager home.
 *
 * <h2>Why this has to be declared</h2>
 *
 * <p>A vendored path is the one thing a project checkout carries that a
 * checkout cannot describe by itself. {@code test_graph/sdk} is a tracked
 * symlink; git stores the target <em>bytes</em>, so whatever
 * {@code SKILL_MANAGER_HOME} happened to be when the scaffolder ran is what
 * every later clone of that repo gets. Across this integration repository that
 * produced 16 broken links in 6 repos, in three shapes:
 *
 * <ul>
 *   <li>13 tracked absolute links into one developer's global home, so the repo
 *       builds on exactly one machine;</li>
 *   <li>1 link whose text is <em>relative</em>
 *       ({@code standard-nodes -> sdk/../standard-nodes}) but which resolves
 *       through an absolute sibling link and lands in that same global home —
 *       invisible to any check that reads link text rather than resolving it;</li>
 *   <li>3 links pointing at a path that escapes the repository entirely, so the
 *       project cannot build at all.</li>
 * </ul>
 *
 * <p>Nothing in the manifest said those paths were supposed to point anywhere in
 * particular, so nothing could say they were wrong. This block is that
 * statement, and {@code dev.skillmanager.project.ProjectVendoredResolver} is the
 * check it enables.
 *
 * <h2>Manifest shape</h2>
 *
 * <pre>{@code
 * [[vendored]]
 * name = "test-graph-sdk"
 * paths = ["test_graph/sdk", "test_graph/build-logic", "test_graph/standard-nodes"]
 * from_unit = "test-graph"
 * from_subpath = "project_sdk_sources"
 * on_invalid = "error"        # error | warn
 * }</pre>
 *
 * <p>An array of tables, parsed with the same {@code getArray}/{@code getTable}
 * idiom as {@code [[libs]]}. {@code paths} is per-declaration rather than a
 * global rule because the sets genuinely differ: {@code spec-double-compiler}
 * vendors three paths where most repos vendor two.
 *
 * @param name        identifies the group in diagnostics; not a filesystem name
 * @param paths       project-root-relative paths, each of which must stay inside
 *                    the project
 * @param fromUnit    installed unit whose content these paths come from
 * @param fromSubpath directory inside that unit holding the vendored trees;
 *                    null when the unit root is the source
 * @param onInvalid   whether a surviving finding fails the command or only warns
 */
public record ProjectVendored(
        String name,
        List<String> paths,
        String fromUnit,
        String fromSubpath,
        OnInvalid onInvalid
) {

    /** What a finding that survives validation (and repair) should do. */
    public enum OnInvalid {
        /** Fail the command. The default: a declared contract that cannot fail is a comment. */
        ERROR,
        /** Report and continue. For a project mid-migration. */
        WARN;

        public static OnInvalid parse(String raw) {
            if (raw == null || raw.isBlank()) return ERROR;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "error" -> ERROR;
                case "warn" -> WARN;
                default -> throw new IllegalArgumentException(
                        "on_invalid must be \"error\" or \"warn\", got: " + raw);
            };
        }
    }

    public ProjectVendored {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project vendored name must not be blank");
        }
        paths = paths == null ? List.of() : List.copyOf(paths);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("project vendored " + name + " declares no paths");
        }
        for (String candidate : paths) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalArgumentException(
                        "project vendored " + name + " declares a blank path");
            }
            Path p = Path.of(candidate);
            if (p.isAbsolute()) {
                throw new IllegalArgumentException("project vendored " + name
                        + " path must be relative to the project root: " + candidate);
            }
            // A declared path that escapes the project is the very defect this
            // block exists to catch, so it cannot be spelled in the declaration.
            if (p.normalize().startsWith("..")) {
                throw new IllegalArgumentException("project vendored " + name
                        + " path escapes the project root: " + candidate);
            }
        }
        if (fromUnit == null || fromUnit.isBlank()) {
            throw new IllegalArgumentException(
                    "project vendored " + name + " must name from_unit");
        }
        fromSubpath = fromSubpath == null || fromSubpath.isBlank() ? null : fromSubpath;
        onInvalid = onInvalid == null ? OnInvalid.ERROR : onInvalid;
    }

    /** True when a surviving finding in this group must fail the command. */
    public boolean fatal() { return onInvalid == OnInvalid.ERROR; }

    /**
     * Where this group's content sits below a skill-manager home:
     * {@code skills/<from_unit>[/<from_subpath>]}.
     */
    public Path sourceDirIn(Path homeRoot) {
        Path unitDir = homeRoot.resolve("skills").resolve(fromUnit);
        return fromSubpath == null ? unitDir : unitDir.resolve(fromSubpath);
    }
}
