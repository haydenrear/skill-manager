package dev.skillmanager.bindings;

/**
 * What kind of filesystem action a {@link Projection} represents.
 *
 * <p>This is the "how" of materialization — the per-effect compensation
 * dispatches on it to reverse the right action.
 */
public enum ProjectionKind {
    /** {@code Files.createSymbolicLink(destPath, sourcePath)}. The default path for skills + plugins. */
    SYMLINK,
    /** Recursive copy from {@code sourcePath} to {@code destPath}. Reserved for filesystems that refuse symlinks. */
    COPY,
    /**
     * The original contents of {@code destPath} were renamed to a
     * timestamped sibling (recorded in {@link Projection#destPath}) so
     * a {@link ConflictPolicy#RENAME_EXISTING} binding can be reversed
     * by moving the backup back into place on unbind.
     */
    RENAMED_ORIGINAL_BACKUP,
    /**
     * Tracked real-file copy with a SHA-256 of the bytes recorded in
     * {@link Projection#boundHash}. The default path for doc-repo
     * sources (#48): the project repo holds real bytes (portable
     * wherever the repo travels) and the hash lets {@code sync}
     * detect drift in either direction (upstream vs. user-edited
     * dest) and route to the four-state matrix.
     */
    MANAGED_COPY,
    /**
     * Upsert a single {@code @<path>} line inside a bracket-marked
     * {@code # skill-manager-imports} section of a markdown file.
     * skill-manager owns the content between the markers; outside
     * is user-owned and never touched. Removal prunes the line; if
     * the managed section ends up empty, the whole section
     * (markers + heading) is removed too.
     *
     * <p>For {@code IMPORT_DIRECTIVE}, {@link Projection#destPath}
     * is the markdown file being edited ({@code CLAUDE.md} or
     * {@code AGENTS.md}); {@link Projection#sourcePath} is unused
     * (the import line text is derived from the binding's other
     * {@link #MANAGED_COPY} projection).
     */
    IMPORT_DIRECTIVE
}
