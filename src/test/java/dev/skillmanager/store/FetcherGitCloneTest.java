package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;
import static dev.skillmanager._lib.test.Tests.assertFalse;

/**
 * Behavior of the Fetcher's git-clone path that doesn't require an actual
 * network round-trip: the auth-failure pattern matcher and the
 * exception hierarchy. The real `git clone` smoke is in the test_graph
 * source-tracking / git-latest graphs.
 */
public final class FetcherGitCloneTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("FetcherGitCloneTest");

        // -- auth-pattern matcher ------------------------------------------

        suite.test("looksLikeAuthFailure: 'Permission denied (publickey)' matches", () ->
                assertTrue(Fetcher.looksLikeAuthFailure(
                        "git@github.com: Permission denied (publickey)."),
                        "SSH publickey rejection should match")
        );

        suite.test("looksLikeAuthFailure: 'Authentication failed' matches", () ->
                assertTrue(Fetcher.looksLikeAuthFailure(
                        "remote: Invalid username or password.\n"
                                + "fatal: Authentication failed for 'https://github.com/x/y.git/'"),
                        "HTTPS auth failure should match")
        );

        suite.test("looksLikeAuthFailure: 'could not read Username' matches", () ->
                assertTrue(Fetcher.looksLikeAuthFailure(
                        "fatal: could not read Username for 'https://github.com': terminal prompts disabled"),
                        "GIT_TERMINAL_PROMPT=0 stderr should match")
        );

        suite.test("looksLikeAuthFailure: JGit 'no CredentialsProvider' matches", () ->
                assertTrue(Fetcher.looksLikeAuthFailure(
                        "https://github.com/x/y.git: Authentication is required but no "
                                + "CredentialsProvider has been registered"),
                        "JGit's TransportException message should match")
        );

        suite.test("looksLikeAuthFailure: '403 Forbidden' matches", () ->
                assertTrue(Fetcher.looksLikeAuthFailure(
                        "fatal: unable to access 'https://github.com/x/y.git/': "
                                + "The requested URL returned error: 403"),
                        "HTTP 403 should match (private repo seen as 404 unauth)")
        );

        suite.test("looksLikeAuthFailure: github's 'Repository not found' matches", () ->
                assertTrue(Fetcher.looksLikeAuthFailure(
                        "remote: Repository not found.\n"
                                + "fatal: repository 'https://github.com/x/private/' not found"),
                        "GitHub returns 'not found' for private repos when unauth'd")
        );

        suite.test("looksLikeAuthFailure: network error does NOT match", () ->
                assertFalse(Fetcher.looksLikeAuthFailure(
                        "fatal: unable to access 'https://github.com/x/y.git/': "
                                + "Could not resolve host: github.com"),
                        "DNS failure is not an auth issue")
        );

        suite.test("looksLikeAuthFailure: empty string", () ->
                assertFalse(Fetcher.looksLikeAuthFailure(""), "empty input")
        );

        suite.test("looksLikeAuthFailure: null", () ->
                assertFalse(Fetcher.looksLikeAuthFailure(null), "null input")
        );

        // -- exception hierarchy --------------------------------------------

        suite.test("GitCloneAuthException IS-A GitFetcherException", () -> {
            GitCloneAuthException auth = new GitCloneAuthException(
                    "git@github.com:owner/repo.git", "Permission denied", null);
            assertTrue(auth instanceof GitFetcherException,
                    "auth subclass extends base — CLI handler's unwrapCause "
                            + "matches the more specific class first, but the "
                            + "fall-through GitFetcherException catch would "
                            + "still catch this if someone reorders the handler");
            assertEquals("git@github.com:owner/repo.git", auth.url(),
                    "url preserved through the auth subclass");
        });

        suite.test("GitFetcherException carries url + message", () -> {
            GitFetcherException ex = new GitFetcherException(
                    "https://gitlab/x.git",
                    "git clone https://gitlab/x.git failed (rc=128):\nsome stderr",
                    null);
            assertEquals("https://gitlab/x.git", ex.url(), "url");
            assertTrue(ex.getMessage().contains("rc=128"), "message includes rc");
            assertTrue(ex.getMessage().contains("some stderr"), "message includes stderr");
        });

        suite.test("Stable exit codes: 9 = auth, 10 = generic fetch", () -> {
            assertEquals(9, GitCloneAuthException.EXIT_CODE,
                    "auth-specific exit code so agents can branch on credential failure");
            assertEquals(10, GitFetcherException.EXIT_CODE,
                    "generic git-fetch exit code; sits next to registry's 8 and auth's 7");
        });

        return suite.runAll();
    }
}
