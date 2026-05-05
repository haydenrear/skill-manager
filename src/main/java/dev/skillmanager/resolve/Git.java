package dev.skillmanager.resolve;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Minimal git port the resolver needs. Default impl wraps
 * {@code GitOps}; tests substitute {@code FakeGit} so the resolver
 * can be exercised without spawning real {@code git} subprocesses.
 *
 * <p>Kept narrow on purpose — adding methods to this interface fans
 * out across every implementation. If a future ticket needs more,
 * extract a specialized port (e.g. {@code GitWritable} for stash/pop
 * during sync).
 */
public interface Git {

    /**
     * Clone {@code url} (optionally checking out {@code ref}) into
     * {@code dest}. {@code ref} may be a branch, tag, or sha; if null
     * the default branch is used. Returns the path of the working
     * tree (typically equal to {@code dest}). Throws {@link IOException}
     * on any failure.
     */
    Path cloneAt(String url, String ref, Path dest) throws IOException;

    /**
     * The sha of the current HEAD in {@code dir}, or null if the
     * directory is not a git repo or HEAD is unreadable.
     */
    String headHash(Path dir);
}
