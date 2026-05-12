package dev.skillmanager.resolve;

import dev.skillmanager.registry.AuthenticationRequiredException;
import dev.skillmanager.registry.RegistryUnavailableException;
import dev.skillmanager.store.GitCloneAuthException;
import dev.skillmanager.store.GitFetcherException;

import java.util.List;

/**
 * Exit-code mapping for the failures from
 * {@link Resolver#resolveAll(List)}. Rendering for those failures flows
 * through {@link dev.skillmanager.effects.ContextFact.TransitiveFailed}
 * facts and the renderer's existing case, not through this helper —
 * each {@code Resolver.ResolveFailure} is converted to one fact at the
 * effect-handler boundary so the renderer is the single rendering
 * surface.
 */
public final class TransitiveFailures {

    private TransitiveFailures() {}

    /**
     * Map the FIRST failure's cause onto a stable exit code so agents
     * and shell wrappers can branch on the failure kind without
     * parsing stderr. Mirrors the matches the CLI's top-level
     * exception handler uses for single-failure paths.
     *
     * <ul>
     *   <li>{@link GitCloneAuthException} → 9 (git credentials refused)</li>
     *   <li>{@link GitFetcherException} → 10 (generic git fetch failure)</li>
     *   <li>{@link AuthenticationRequiredException} → 7 (skill-manager registry auth)</li>
     *   <li>{@link RegistryUnavailableException} → 8 (registry network unreachable)</li>
     *   <li>anything else → 1 (generic failure)</li>
     * </ul>
     */
    public static int exitCodeFor(List<Resolver.ResolveFailure> failures) {
        if (failures.isEmpty()) return 0;
        Throwable cause = failures.get(0).cause();
        for (Throwable c = cause; c != null; c = c.getCause()) {
            if (c instanceof GitCloneAuthException) return GitCloneAuthException.EXIT_CODE;
            if (c instanceof GitFetcherException) return GitFetcherException.EXIT_CODE;
            if (c instanceof AuthenticationRequiredException) return AuthenticationRequiredException.EXIT_CODE;
            if (c instanceof RegistryUnavailableException) return RegistryUnavailableException.EXIT_CODE;
        }
        return 1;
    }
}
