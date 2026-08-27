package dev.skillmanager.effects;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>The third state between "already has it" and "diverged".</b>
 *
 * <h2>The state that had no name</h2>
 *
 * <p>A sync asks one question before it moves a store — "is there local work
 * here a sync would destroy" — and {@code isAuthoredDirty} answers it from two
 * halves: uncommitted work in the tree, OR a HEAD carrying commits the
 * installed record does not name. The second half is what stops an overwrite,
 * and it must.
 *
 * <p>But the second half is also true of a store somebody ran {@code git pull}
 * in. Nothing was authored; the record simply fell behind its own store. The
 * handler already released one shape of that — {@code alreadyContainsTarget},
 * for a store at or PAST the sync target — and refused the other, where the
 * store sits strictly BETWEEN its record and the target:
 *
 * <pre>
 *   record  ────►  HEAD  ────►  target        clean tree
 *           behind        behind
 * </pre>
 *
 * <p>That is a pure fast-forward. There is no commit upstream does not already
 * contain and nothing in the tree to overwrite, so there is nothing a sync
 * could destroy — yet it refused, and the {@code --merge} it printed did
 * exactly this fast-forward one round trip later. Measured on the operator's
 * root home on 2026-08-27: {@code spec-double-compiler}, record {@code 436c78c5},
 * store {@code 902cfd7f}, upstream {@code 973c7807}, clean tree, refused.
 *
 * <h2>Why this is not a licence to overwrite</h2>
 *
 * <p>The release is conjunctive and each half is load-bearing. A dirty tree
 * refuses however the commits sit, and a HEAD that is NOT an ancestor of the
 * target refuses however clean the tree is — that second case is a real branch,
 * and fast-forwarding it would delete commits that exist nowhere else. The two
 * negative cases below are the ones that matter; the positive case only proves
 * the release is reachable.
 */
public final class SyncFastForwardReleaseTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SyncFastForwardReleaseTest");

        if (!gitAvailable()) {
            System.out.println("== SyncFastForwardReleaseTest — SKIPPED, git is not on PATH");
            return 0;
        }

        suite.test("a store strictly BEHIND the target is a fast-forward, and is released", () -> {
            Path repo = repo("ff-release");
            commit(repo, "a.txt", "one", "first");
            String head = head(repo);
            commit(repo, "a.txt", "two", "second");
            String target = head(repo);
            reset(repo, head);

            assertTrue(SyncGitHandler.targetIsStrictlyAhead(repo, target),
                    "HEAD is an ancestor of the target, so the move can lose nothing");
        });

        suite.test("a store that has DIVERGED is not a fast-forward, and still refuses", () -> {
            Path repo = repo("ff-diverged");
            commit(repo, "a.txt", "one", "first");
            String base = head(repo);
            commit(repo, "a.txt", "upstream", "upstream moved");
            String target = head(repo);

            // A local commit on top of the shared base: the shape the second
            // half of `dirty` exists to protect. It is NOT an ancestor of the
            // target, so nothing here may be fast-forwarded away.
            reset(repo, base);
            commit(repo, "b.txt", "local work", "local");

            assertFalse(SyncGitHandler.targetIsStrictlyAhead(repo, target),
                    "a real local commit is not an ancestor of the target, so it must keep "
                            + "refusing — releasing it would delete work held nowhere else");
        });

        suite.test("a store already AT the target is not 'strictly ahead' either", () -> {
            Path repo = repo("ff-equal");
            commit(repo, "a.txt", "one", "first");
            String target = head(repo);

            // git calls a commit its own ancestor, so this predicate alone
            // would say "fast-forward" and fall through to a no-op merge.
            // `alreadyContainsTarget` runs FIRST and takes this case, which is
            // why the ordering in the handler is not incidental.
            assertTrue(SyncGitHandler.targetIsStrictlyAhead(repo, target),
                    "documents the overlap: this predicate does not exclude equality, and "
                            + "alreadyContainsTarget is what claims that case ahead of it");
        });

        suite.test("an unresolvable target refuses rather than releasing", () -> {
            Path repo = repo("ff-null");
            commit(repo, "a.txt", "one", "first");
            assertFalse(SyncGitHandler.targetIsStrictlyAhead(repo, null),
                    "a target that could not be resolved is not evidence that a move is safe");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    private static Path repo(String name) throws Exception {
        Path dir = Files.createTempDirectory("ffrelease-" + name);
        git(dir, "init", "--initial-branch=main");
        git(dir, "config", "user.email", "ff@test.invalid");
        git(dir, "config", "user.name", "ff");
        return dir;
    }

    private static void commit(Path repo, String file, String body, String message)
            throws Exception {
        Files.writeString(repo.resolve(file), body + "\n");
        git(repo, "add", "-A", "-f");
        git(repo, "commit", "-m", message);
    }

    private static void reset(Path repo, String to) throws Exception {
        git(repo, "reset", "--hard", to);
    }

    private static String head(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out;
    }

    private static void git(Path cwd, String... argv) throws Exception {
        String[] full = new String[argv.length + 1];
        full[0] = "git";
        System.arraycopy(argv, 0, full, 1, argv.length);
        Process p = new ProcessBuilder(full).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", full) + ": " + out);
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
