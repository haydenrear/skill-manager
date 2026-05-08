package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Contract for the local-source branch of {@link Fetcher#fetch}:
 *
 * <ul>
 *   <li>An explicit-local source ({@code file:}, {@code ./...},
 *       {@code ../...}, {@code /...}) that doesn't resolve to a
 *       directory must <strong>fail fast with a clear "local source
 *       not found" message</strong> — never fall through to the
 *       registry. The fallthrough used to surface as a confusing
 *       "registry unreachable" or HTTP 404 that hid the real mistake
 *       (typo in path, wrong CWD).</li>
 *   <li>A bare {@code name} (no path-shape prefix) keeps falling
 *       through to the registry — that's the documented "is this a
 *       registry coord or a directory?" disambiguation.</li>
 * </ul>
 */
public final class FetcherLocalSourceTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("FetcherLocalSourceTest");

        suite.test("file:<missing-path> fails fast with 'local source not found'", () -> {
            Path workspace = Files.createTempDirectory("fetcher-test-");
            SkillStore store = new SkillStore(Files.createTempDirectory("fetcher-store-"));
            store.init();
            String source = "file:/no/such/dir/from/test-" + System.nanoTime();

            IOException thrown = expectIOException(() ->
                    Fetcher.fetch(source, null, workspace, store));

            String msg = thrown.getMessage();
            assertTrue(msg != null && msg.contains("local source"),
                    "message names the failing class (was: " + msg + ")");
            assertTrue(msg.contains(source) || msg.contains("/no/such/dir/from/test-"),
                    "message includes the offending path (was: " + msg + ")");
            assertTrue(msg.contains("does not exist"),
                    "message states the directory doesn't exist (was: " + msg + ")");
        });

        suite.test("./<missing> fails fast even without the file: prefix", () -> {
            Path workspace = Files.createTempDirectory("fetcher-test-");
            SkillStore store = new SkillStore(Files.createTempDirectory("fetcher-store-"));
            store.init();
            String source = "./definitely-not-here-" + System.nanoTime();

            IOException thrown = expectIOException(() ->
                    Fetcher.fetch(source, null, workspace, store));
            assertTrue(thrown.getMessage().contains("local source"),
                    "explicit-local path-shape ./ fails fast (was: " + thrown.getMessage() + ")");
        });

        suite.test("/abs-but-missing fails fast", () -> {
            Path workspace = Files.createTempDirectory("fetcher-test-");
            SkillStore store = new SkillStore(Files.createTempDirectory("fetcher-store-"));
            store.init();
            String source = "/var/empty/no-such-skill-" + System.nanoTime();

            IOException thrown = expectIOException(() ->
                    Fetcher.fetch(source, null, workspace, store));
            assertTrue(thrown.getMessage().contains("local source"),
                    "absolute path-shape fails fast (was: " + thrown.getMessage() + ")");
        });

        suite.test("file:<existing-file-not-dir> fails fast with 'exists but is not a directory'", () -> {
            Path workspace = Files.createTempDirectory("fetcher-test-");
            SkillStore store = new SkillStore(Files.createTempDirectory("fetcher-store-"));
            store.init();
            // A regular file, not a directory.
            Path nonDirFile = Files.createTempFile("fetcher-file-", ".txt");
            String source = "file:" + nonDirFile;

            IOException thrown = expectIOException(() ->
                    Fetcher.fetch(source, null, workspace, store));
            String msg = thrown.getMessage();
            assertTrue(msg.contains("exists but is not a directory"),
                    "distinguishes file-vs-dir from missing (was: " + msg + ")");
        });

        suite.test("bare <name> still falls through to registry resolution (not fail-fast)", () -> {
            // The bare-name branch should NOT trip the fail-fast even
            // when no directory of that name exists in CWD — that's
            // the documented "treat ambiguous coords as registry
            // names" behavior. We assert this by checking the
            // exception is NOT the "local source not found" variety.
            // The actual outcome (registry lookup result) depends on
            // network state, so we either get a registry-related
            // failure or a successful lookup; either way, the
            // fail-fast message must NOT appear.
            Path workspace = Files.createTempDirectory("fetcher-test-");
            SkillStore store = new SkillStore(Files.createTempDirectory("fetcher-store-"));
            store.init();
            String source = "definitely-not-a-real-skill-name-" + System.nanoTime();

            try {
                Fetcher.fetch(source, null, workspace, store);
                // Either path is fine here — we only care that the
                // local-source fail-fast didn't fire.
            } catch (Throwable thrown) {
                String msg = thrown.getMessage() == null ? "" : thrown.getMessage();
                assertTrue(!msg.contains("local source"),
                        "bare name must not be treated as local-source-fail-fast "
                                + "(was: " + msg + ")");
            }
        });

        return suite.runAll();
    }

    private static IOException expectIOException(ThrowingRunnable r) {
        try {
            r.run();
        } catch (IOException io) {
            return io;
        } catch (Throwable t) {
            throw new AssertionError("expected IOException, got " + t.getClass().getName()
                    + ": " + t.getMessage(), t);
        }
        throw new AssertionError("expected IOException, but call returned normally");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
