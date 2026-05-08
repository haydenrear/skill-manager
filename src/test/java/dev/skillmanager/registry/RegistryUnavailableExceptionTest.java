package dev.skillmanager.registry;

import dev.skillmanager._lib.test.Tests;

import java.net.ConnectException;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Shape contract for {@link RegistryUnavailableException}: it carries
 * the registry URL, exposes a stable exit code distinct from the
 * auth-required exit code, and produces a message string that the
 * CLI banner can rely on for the "registry unreachable" case.
 *
 * <p>The richer banner-rendering test lives at the CLI layer (where
 * {@code System.err} can be captured); this just pins the data carrier.
 */
public final class RegistryUnavailableExceptionTest {

    public static int run() {
        Tests.Suite suite = Tests.suite("RegistryUnavailableExceptionTest");

        suite.test("baseUrl is preserved + message names the URL", () -> {
            ConnectException cause = new ConnectException("Connection refused");
            RegistryUnavailableException ex =
                    new RegistryUnavailableException("http://localhost:8080", cause);

            assertEquals("http://localhost:8080", ex.baseUrl(), "baseUrl preserved");
            assertTrue(ex.getMessage().contains("http://localhost:8080"),
                    "message names the URL (was: " + ex.getMessage() + ")");
            assertTrue(ex.getMessage().contains("ConnectException"),
                    "message names the underlying error class (was: " + ex.getMessage() + ")");
            assertEquals(cause, ex.getCause(), "cause chained");
        });

        suite.test("EXIT_CODE is stable + distinct from AuthenticationRequiredException", () -> {
            assertEquals(8, RegistryUnavailableException.EXIT_CODE,
                    "stable exit code so wrappers can branch on it");
            assertTrue(RegistryUnavailableException.EXIT_CODE
                            != AuthenticationRequiredException.EXIT_CODE,
                    "registry-down vs auth-expired must surface as different exit codes");
        });

        suite.test("null cause message yields a clean main-message tail", () -> {
            ConnectException cause = new ConnectException();  // no detail message
            RegistryUnavailableException ex =
                    new RegistryUnavailableException("http://localhost:8080", cause);

            // Should not produce a trailing " — null" or similar
            // placeholder: the parenthesized cause-detail tail only
            // appears when the cause has a real message.
            assertTrue(!ex.getMessage().contains("null"),
                    "no 'null' placeholder when cause has no detail message "
                            + "(was: " + ex.getMessage() + ")");
        });

        return suite.runAll();
    }
}
