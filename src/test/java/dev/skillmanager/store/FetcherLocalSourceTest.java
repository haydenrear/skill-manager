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
 *   <li>A bare {@code name} (no path-shape prefix) used to fall through
 *       to the registry. The registry was removed (#427), so it now fails
 *       with a message naming the github:/git+/file: forms.</li>
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

        // #427: the registry is gone, so a bare name has nowhere to resolve.
        // It must fail as a coordinate problem (not as a missing local
        // directory), and the message must name every form that does work.
        for (String bare : new String[]{
                "definitely-not-a-real-skill-name-" + System.nanoTime(),
                "hello-skill@1.0.0",
                "skill:hello-skill",
                "plugin:hello-plugin"}) {
            suite.test("bare '" + bare.replaceAll("-\\d+$", "-N") + "' fails and names github:/git+/file:", () -> {
                Path workspace = Files.createTempDirectory("fetcher-test-");
                SkillStore store = new SkillStore(Files.createTempDirectory("fetcher-store-"));
                store.init();

                IOException thrown = expectIOException(() ->
                        Fetcher.fetch(bare, null, workspace, store));
                String msg = thrown.getMessage() == null ? "" : thrown.getMessage();
                assertTrue(!msg.contains("local source"),
                        "a bare name is not a missing local directory (was: " + msg + ")");
                assertTrue(msg.contains("'" + bare + "'"), "names the coordinate (was: " + msg + ")");
                for (String form : new String[]{"github:", "git+", "file:"}) {
                    assertTrue(msg.contains(form), "names the " + form + " form (was: " + msg + ")");
                }
                assertTrue(msg.length() <= 240,
                        "fits under ResolveFailure.reason()'s cut, so no form is truncated: "
                                + msg.length());
            });
        }

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
