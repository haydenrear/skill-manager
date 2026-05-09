package dev.skillmanager.registry;

/**
 * The configured registry server isn't responding at the network
 * layer — TCP connect refused, DNS doesn't resolve, or the connect
 * handshake timed out. Distinct from {@link
 * AuthenticationRequiredException} (server reachable, credentials
 * rejected) and from arbitrary {@link java.io.IOException}s during
 * request body / response handling (those are real bugs and should
 * bubble with a full stack trace).
 *
 * <p>Thrown from {@link RegistryClient} whenever the underlying
 * {@code HttpClient.send} fails with one of {@code ConnectException},
 * {@code UnknownHostException}, or {@code HttpConnectTimeoutException}.
 * The CLI top-level catches it and renders a stable banner naming the
 * registry URL plus how to override it — so the user gets a one-line
 * actionable error instead of a {@code java.net.ConnectException}
 * stack trace.
 *
 * <p>Carries {@link #baseUrl()} so the banner can show which URL the
 * CLI was actually trying — most often the source of the user's
 * confusion ("oh, I forgot I had {@code SKILL_MANAGER_REGISTRY_URL}
 * pointing at staging").
 */
public final class RegistryUnavailableException extends RuntimeException {

    /**
     * Stable exit code so agents and shell wrappers can branch on
     * "registry down" without parsing stderr. Picked one above
     * {@link AuthenticationRequiredException#EXIT_CODE} (7) so the
     * two unauth/unreachable signals stay distinguishable.
     */
    public static final int EXIT_CODE = 8;

    private final String baseUrl;

    public RegistryUnavailableException(String baseUrl, Throwable cause) {
        super("registry at " + baseUrl + " is not reachable"
                + (cause == null || cause.getMessage() == null
                        ? "" : " (" + cause.getClass().getSimpleName()
                                + ": " + cause.getMessage() + ")"),
                cause);
        this.baseUrl = baseUrl;
    }

    /** Configured registry URL the CLI was attempting to reach. */
    public String baseUrl() { return baseUrl; }
}
