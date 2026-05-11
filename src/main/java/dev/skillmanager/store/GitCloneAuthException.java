package dev.skillmanager.store;

/**
 * A {@code git clone} of a unit's source git URL failed because the
 * remote rejected the credentials we presented (or we had none to
 * present). Distinct from generic {@link java.io.IOException}s that
 * surface during the clone — those are real bugs worth a stack trace.
 *
 * <p>Thrown from {@link Fetcher#gitClone} when:
 * <ul>
 *   <li>the host's {@code git} subprocess prints a known
 *       authentication-failure pattern to stderr
 *       ({@code Permission denied}, {@code Authentication failed},
 *       {@code could not read Username}, {@code HTTP 401}, {@code HTTP 403},
 *       …);</li>
 *   <li>or, on the JGit fallback path, the underlying
 *       {@code TransportException} carries one of the same
 *       indicators.</li>
 * </ul>
 *
 * <p>The CLI top-level catches this and renders a stable banner —
 * mirroring the registry's {@code AuthenticationRequiredException} —
 * so the user gets an actionable "you need to configure git access
 * to {@code <url>}" message instead of a {@code TransportException}
 * stack.
 */
public final class GitCloneAuthException extends GitFetcherException {

    /**
     * Stable exit code so agents and shell wrappers can branch on
     * "git auth failed". Sits next to
     * {@link dev.skillmanager.registry.AuthenticationRequiredException#EXIT_CODE}
     * (7) but distinct — different remediation (configure git / SSH
     * agent / a credential helper, not {@code skill-manager login}).
     *
     * <p>The handler should match this subclass BEFORE the
     * {@link GitFetcherException} base so the auth-specific banner
     * (and exit code) wins.
     */
    public static final int EXIT_CODE = 9;

    public GitCloneAuthException(String url, String detail, Throwable cause) {
        super(url,
                "git clone " + url + " refused: "
                        + (detail == null || detail.isBlank()
                                ? (cause == null ? "authentication required" : cause.getMessage())
                                : detail),
                cause);
    }
}
