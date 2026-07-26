package dev.skillmanager.launch;

import java.nio.file.Path;

/**
 * A launch was refused because the environment it would hand the child process
 * does not actually redirect the agent off the operator's real config
 * directory.
 *
 * <h2>Why this is an error and not a warning</h2>
 *
 * <p>{@code CLAUDE_CONFIG_DIR} is where skills live. A launch that sets
 * {@code SKILL_MANAGER_HOME} into a project-local home but leaves Claude
 * reading {@code ~/.claude} is <em>not</em> isolated: the agent loads the
 * global skill set, and anything it writes through a skill lands in the shared
 * home. Nothing about the resulting session looks wrong, which is exactly the
 * problem — a warning scrolls past and the run continues believing it is
 * isolated. The whole point of the per-worktree home is defeated silently.
 *
 * <p>So the launch stops before the child process is created. The remedy is
 * always the same shape (repair the home's {@code home.runtime.json} env
 * block, or stop overriding {@code CLAUDE_CONFIG_DIR} in
 * {@code envContributions}), and it is named in the message.
 */
public final class UnredirectedLaunchException extends Exception {

    /** Distinct exit code so a caller can tell this apart from the child failing. */
    public static final int EXIT_CODE = 7;

    private final Path homeRoot;
    private final Path resolvedConfigDir;

    public UnredirectedLaunchException(Path homeRoot, Path resolvedConfigDir, String detail) {
        super(detail);
        this.homeRoot = homeRoot;
        this.resolvedConfigDir = resolvedConfigDir;
    }

    public Path homeRoot() { return homeRoot; }

    public Path resolvedConfigDir() { return resolvedConfigDir; }
}
