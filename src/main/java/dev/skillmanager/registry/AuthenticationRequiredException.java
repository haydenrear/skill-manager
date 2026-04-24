package dev.skillmanager.registry;

/**
 * The registry refused the cached bearer and we couldn't transparently
 * refresh it — the user needs to re-authenticate.
 *
 * <p>Thrown from {@link RegistryClient} when a 401 persists through the
 * refresh attempt (or when there's nothing cached to refresh). The CLI
 * top-level catches it and emits the stable {@code ACTION_REQUIRED:}
 * banner that agents parse to ask the user for {@code skill-manager
 * login}.
 */
public class AuthenticationRequiredException extends RuntimeException {

    /** Stable exit code so agents and scripts can branch on it. */
    public static final int EXIT_CODE = 7;

    public AuthenticationRequiredException(String message) {
        super(message);
    }
}
