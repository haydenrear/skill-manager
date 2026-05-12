package dev.skillmanager.bindings;

/**
 * What kind of filesystem action a {@link Projection} represents.
 *
 * <p>This is the "how" of materialization — the per-effect compensation
 * dispatches on it to reverse the right action.
 */
public enum ProjectionKind {
    /** {@code Files.createSymbolicLink(destPath, sourcePath)}. The default path. */
    SYMLINK,
    /** Recursive copy from {@code sourcePath} to {@code destPath}. Reserved for filesystems that refuse symlinks. */
    COPY,
    /**
     * The original contents of {@code destPath} were renamed to a
     * timestamped sibling (recorded in {@link Projection#destPath}) so
     * a {@link ConflictPolicy#RENAME_EXISTING} binding can be reversed
     * by moving the backup back into place on unbind.
     */
    RENAMED_ORIGINAL_BACKUP
}
