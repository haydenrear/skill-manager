package dev.skillmanager.resolve;

import dev.skillmanager.registry.AuthenticationRequiredException;
import dev.skillmanager.registry.RegistryUnavailableException;
import dev.skillmanager.store.GitCloneAuthException;
import dev.skillmanager.store.GitFetcherException;
import dev.skillmanager.util.Log;

import java.util.List;

/**
 * Helpers for rendering / exit-coding the failures from
 * {@link Resolver#resolveAll(List)}.
 *
 * <p>Pre-Program callers (install, onboard) don't have an
 * {@link dev.skillmanager.effects.EffectContext} to thread the failures
 * through as {@link dev.skillmanager.effects.ContextFact.TransitiveFailed}
 * receipts. They render directly via {@link Log} so every failure
 * appears in the run output — same shape as
 * {@link dev.skillmanager.effects.ConsoleProgramRenderer}'s renderer
 * for the {@code TransitiveFailed} fact — and map the failure types
 * onto the same stable exit codes the in-Program exception handler uses
 * for single-failure paths.
 *
 * <p>Sync uses
 * {@link dev.skillmanager.effects.EffectContext#addError(String, dev.skillmanager.source.InstalledUnit.ErrorKind, String)}
 * + ContextFact emission directly, since it has both the context and
 * the in-store parent unit; it does not route through this helper.
 */
public final class TransitiveFailures {

    private TransitiveFailures() {}

    /**
     * Log every failure as a one-line warn matching the renderer's
     * shape ({@code transitive: <coord> [needed by <X>] — <reason>}),
     * so a batch of failures shows up as a list rather than one
     * exception escaping per command run. Then, if the FIRST failure
     * has a recognized actionable cause type, route through the same
     * banner the CLI top-level handler prints when those exceptions
     * escape — so the diagnostic UX is identical regardless of
     * whether the failure surfaced via exception or via a resolve
     * outcome.
     */
    public static void renderAll(List<Resolver.ResolveFailure> failures) {
        for (Resolver.ResolveFailure f : failures) {
            String who = f.requestedBy() == null || f.requestedBy().isBlank()
                    ? "(top-level)"
                    : "needed by " + f.requestedBy();
            Log.warn("transitive: %s [%s] — %s", f.source(), who, f.reason());
        }
        if (failures.isEmpty()) return;
        printActionableBanner(failures.get(0).cause());
    }

    /**
     * Dispatch the actionable banner for {@code cause}. Walks the
     * cause chain so a wrapped exception still picks up the right
     * banner (matches the {@code unwrapCause} lookup in
     * {@code SkillManagerCli}).
     */
    private static void printActionableBanner(Throwable cause) {
        for (Throwable c = cause; c != null; c = c.getCause()) {
            if (c instanceof GitCloneAuthException auth) {
                dev.skillmanager.cli.SkillManagerCli.printGitAuthBanner(auth);
                return;
            }
            if (c instanceof GitFetcherException git) {
                dev.skillmanager.cli.SkillManagerCli.printGitFetcherBanner(git);
                return;
            }
            if (c instanceof AuthenticationRequiredException auth) {
                dev.skillmanager.cli.SkillManagerCli.printAuthBanner(auth.getMessage());
                return;
            }
            if (c instanceof RegistryUnavailableException reg) {
                dev.skillmanager.cli.SkillManagerCli.printRegistryUnreachableBanner(reg);
                return;
            }
        }
        // No known-actionable banner — the warn lines above are the
        // only visible signal. Operator must read them.
    }

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
