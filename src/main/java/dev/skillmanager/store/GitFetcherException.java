package dev.skillmanager.store;

/**
 * Base type for every error {@link Fetcher#gitClone} can surface
 * (subprocess non-zero exit, JGit transport failure, checkout failure,
 * missing host {@code git} for an SSH URL). Routes through the CLI's
 * top-level exception handler so the user sees a stable banner with
 * the offending URL and a remediation hint, never a raw stack trace.
 *
 * <p>Subclasses carry the same {@link #url()} accessor + a stable
 * {@link #EXIT_CODE} so agents and shell wrappers can branch on the
 * git-fetch failure mode without parsing stderr. The subclass
 * hierarchy lets the handler match the most specific case first
 * ({@link GitCloneAuthException}) and fall back to this base for
 * everything else.
 *
 * <p>Mirrors the shape of
 * {@link dev.skillmanager.registry.RegistryUnavailableException} —
 * both are "fetch infrastructure couldn't reach the source", just for
 * the registry vs git transport.
 */
public class GitFetcherException extends RuntimeException {

    /**
     * Stable exit code so agents / shell wrappers can branch on
     * "generic git fetch failure". Sits next to
     * {@link GitCloneAuthException#EXIT_CODE} (9, auth-specific) and
     * {@link dev.skillmanager.registry.RegistryUnavailableException#EXIT_CODE}
     * (8, registry unreachable) so the three "fetch refused" signals
     * stay distinguishable.
     */
    public static final int EXIT_CODE = 10;

    private final String url;

    public GitFetcherException(String url, String message, Throwable cause) {
        super(message, cause);
        this.url = url;
    }

    /** The clone URL the CLI tried (verbatim — useful for the banner). */
    public String url() { return url; }
}
