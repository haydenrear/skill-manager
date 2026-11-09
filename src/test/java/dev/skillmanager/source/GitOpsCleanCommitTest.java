package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #410: a clean checkout's commit is its identity, and every way a clean status
 * could be a claim the commit cannot back reads as "not clean".
 */
public final class GitOpsCleanCommitTest {

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== GitOpsCleanCommitTest — SKIPPED, git is not on PATH");
            return 0;
        }
        return Tests.suite("GitOpsCleanCommitTest")

                .test("a clean checkout answers its HEAD", () -> {
                    Path r = repo();
                    assertEquals(git(r, "rev-parse", "HEAD").trim(), clean(r), "HEAD");
                })

                .test("a non-checkout, and a directory inside someone's checkout, answer nothing", () -> {
                    Path plain = Files.createTempDirectory("plain-");
                    assertEquals("", clean(plain), "no repo");
                    Path r = repo();
                    Path nested = Files.createDirectories(r.resolve("nested"));
                    assertEquals("", clean(nested), "must not answer for the enclosing repo");
                })

                .test("a tracked edit, an untracked file and a chmod are all not clean", () -> {
                    Path r = repo();
                    Files.writeString(r.resolve("a.txt"), "changed\n");
                    assertEquals("", clean(r), "tracked edit");
                    git(r, "checkout", "--", ".");
                    Files.writeString(r.resolve("u.txt"), "x");
                    assertEquals("", clean(r), "untracked");
                    Files.delete(r.resolve("u.txt"));
                    git(r, "config", "core.fileMode", "false");
                    assertTrue(r.resolve("a.txt").toFile().setExecutable(true), "chmod");
                    assertEquals("", clean(r), "chmod is content even with core.fileMode=false");
                })

                .test("the user's untracked-files setting cannot hide an untracked file", () -> {
                    Path r = repo();
                    git(r, "config", "status.showUntrackedFiles", "no");
                    Files.writeString(r.resolve("u.txt"), "x");
                    assertEquals("", clean(r), "untracked despite the config");
                })

                .test("a committed gitignore hides its path; a global or local exclude does not", () -> {
                    Path r = repo();
                    Files.writeString(r.resolve("ignored.txt"), "x");
                    assertEquals(git(r, "rev-parse", "HEAD").trim(), clean(r), "committed rule");

                    Files.writeString(r.resolve("global.txt"), "x");
                    Path global = Files.createTempFile("excludes-", "");
                    Files.writeString(global, "global.txt\n");
                    git(r, "config", "core.excludesFile", global.toString());
                    assertEquals("", clean(r), "machine-level excludes are not identity");
                    Files.delete(r.resolve("global.txt"));

                    Files.writeString(r.resolve(".git/info/exclude"), "# a comment\n");
                    assertEquals(git(r, "rev-parse", "HEAD").trim(), clean(r), "comments are not rules");
                    Files.writeString(r.resolve(".git/info/exclude"), "local.txt\n");
                    assertEquals("", clean(r), "info/exclude cannot be pinned, so it falls back");
                })

                .test("assume-unchanged and skip-worktree cannot hide an edit", () -> {
                    Path r = repo();
                    git(r, "update-index", "--assume-unchanged", "a.txt");
                    assertEquals("", clean(r), "assume-unchanged");
                    git(r, "update-index", "--no-assume-unchanged", "a.txt");
                    git(r, "update-index", "--skip-worktree", "a.txt");
                    assertEquals("", clean(r), "skip-worktree");
                })

                .test("a rename is not clean; a non-content path is", () -> {
                    Path r = repo();
                    git(r, "mv", "a.txt", "b.txt");
                    assertEquals("", clean(r), "staged rename");
                    git(r, "reset", "--hard", "--quiet");
                    Files.createDirectories(r.resolve("x/__pycache__"));
                    Files.writeString(r.resolve("x/__pycache__/c.pyc"), "x");
                    assertEquals("", clean(r), "no predicate: it is a change");
                    assertEquals(git(r, "rev-parse", "HEAD").trim(),
                            GitOps.cleanCommit(r, rel -> rel.contains("__pycache__")).orElse(""),
                            "the predicate clears it, even though only a nested file is untracked");
                })

                .test("a repository with no commit answers nothing", () -> {
                    Path r = Files.createTempDirectory("empty-");
                    git(r, "init", "-b", "main", "--quiet");
                    assertEquals("", clean(r), "unborn HEAD");
                })

                .test("pruning keeps the directory a pruned child made non-empty", () -> {
                    Path r = Files.createTempDirectory("prune-");
                    Files.createDirectories(r.resolve("kept/.git"));
                    Files.writeString(r.resolve("kept/.git/config"), "x");
                    Files.createDirectories(r.resolve("empty"));
                    Files.writeString(r.resolve("f.txt"), "x");
                    var entries = ChildHomeMaterializer.entryDigests(r, Set.of(".git"));
                    assertTrue(entries.containsKey("kept"), "dir with only a pruned child stays: " + entries.keySet());
                    assertTrue(entries.containsKey("empty"), "an authored empty dir stays");
                    assertTrue(entries.containsKey("f.txt"), "files stay");
                    assertFalse(entries.keySet().stream().anyMatch(k -> k.contains(".git")), "pruned");
                })

                .runAll();
    }

    private static String clean(Path r) {
        return GitOps.cleanCommit(r, rel -> false).orElse("");
    }

    private static Path repo() throws Exception {
        Path r = Files.createTempDirectory("clean-commit-");
        Files.writeString(r.resolve("a.txt"), "a\n");
        Files.writeString(r.resolve(".gitignore"), "ignored.txt\n");
        git(r, "init", "-b", "main", "--quiet");
        git(r, "add", "-A");
        git(r, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "A");
        return r;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "git " + argv + ": " + out);
        return out;
    }
}
