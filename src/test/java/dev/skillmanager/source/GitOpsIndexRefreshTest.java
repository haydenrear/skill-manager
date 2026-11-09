package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #450: a copied checkout's index is refreshed without changing what it describes,
 * and {@link GitOps#cleanTree} answers only for a subtree HEAD really holds.
 */
public final class GitOpsIndexRefreshTest {

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== GitOpsIndexRefreshTest — SKIPPED, git is not on PATH");
            return 0;
        }
        return Tests.suite("GitOpsIndexRefreshTest")

                .test("a copy is stat-stale until refreshed, and refreshing changes no file", () -> {
                    Path copy = copyOf(repo());
                    assertFalse(git(copy, "diff-files").isBlank(), "the copy's index is stale as copied");
                    byte[] before = Files.readAllBytes(copy.resolve("a.txt"));

                    assertEquals(null, GitOps.refreshIndex(copy), "refreshed");

                    assertEquals("", git(copy, "diff-files"), "no entry needs re-hashing");
                    assertEquals("", git(copy, "status", "--porcelain"), "still clean");
                    assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(copy.resolve("a.txt"))),
                            "tracked bytes untouched");
                })

                .test("an edit after a refresh is still seen", () -> {
                    Path copy = copyOf(repo());
                    GitOps.refreshIndex(copy);
                    Files.writeString(copy.resolve("a.txt"), "b\n");
                    assertContains(git(copy, "status", "--porcelain"), "a.txt", "same size, new content");
                })

                .test("a sparse checkout's excluded paths stay absent and stay skip-worktree", () -> {
                    Path r = sparseRepo();
                    Path copy = copyOf(r);
                    assertEquals(null, GitOps.refreshIndex(copy), "refreshed");
                    assertFalse(Files.exists(copy.resolve("excluded/x.txt")), "still absent");
                    assertContains(git(copy, "ls-files", "-v", "excluded"), "S excluded/x.txt", "still skip-worktree");
                    assertEquals("", git(copy, "status", "--porcelain"), "clean");
                })

                .test("a partial clone fetches no blob", () -> {
                    Path origin = repo();
                    git(origin, "config", "uploadpack.allowfilter", "true");
                    Path parent = Files.createTempDirectory("index-refresh-partial-");
                    Path clone = parent.resolve("c");
                    git(parent, "clone", "--quiet", "--filter=blob:none", "--no-checkout",
                            "file://" + origin.toRealPath(), clone.toString());
                    assertTrue(SparseInstall.apply(clone, List.of("excluded")), "sparse-checkout set");
                    git(clone, "checkout", "--quiet", "main");
                    Path copy = copyOf(clone);
                    git(copy, "remote", "set-url", "origin", "file:///nonexistent/remote");
                    String objects = git(copy, "count-objects", "-v");

                    assertEquals(null, GitOps.refreshIndex(copy), "refreshed with no remote reachable");
                    assertEquals(objects, git(copy, "count-objects", "-v"), "no object was fetched");
                })

                .test("a .git file pointing at another checkout is refused, and that index is untouched", () -> {
                    Path other = repo();
                    Path index = other.resolve(".git/index");
                    long stamp = Files.getLastModifiedTime(index).toMillis();
                    byte[] bytes = Files.readAllBytes(index);
                    Path foreign = Files.createTempDirectory("index-refresh-foreign-");
                    Files.writeString(foreign.resolve(".git"), "gitdir: " + other.resolve(".git") + "\n");

                    assertTrue(GitOps.refreshIndex(foreign) != null, "refused");
                    assertEquals(stamp, Files.getLastModifiedTime(index).toMillis(), "index not rewritten");
                    assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(index)), "index bytes same");
                })

                .test("a held index.lock fails open with a reason", () -> {
                    Path copy = copyOf(repo());
                    Files.writeString(copy.resolve(".git/index.lock"), "");
                    assertTrue(GitOps.refreshIndex(copy) != null, "a reason, not an exception");
                    assertEquals("a\n", Files.readString(copy.resolve("a.txt")), "checkout intact");
                })

                .test("cleanTree names the subtree, ignoring edits elsewhere and refusing edits inside", () -> {
                    Path r = repo();
                    String tree = git(r, "rev-parse", "HEAD:scripts").trim();
                    assertEquals(tree, GitOps.cleanTree(r, "scripts").orElse(""), "clean subtree");

                    Files.writeString(r.resolve("a.txt"), "elsewhere\n");
                    assertEquals(tree, GitOps.cleanTree(r, "scripts").orElse(""), "an edit outside does not matter");
                    git(r, "checkout", "--", ".");

                    Files.writeString(r.resolve("scripts/s.sh"), "edited\n");
                    assertEquals("", GitOps.cleanTree(r, "scripts").orElse(""), "tracked edit");
                    git(r, "checkout", "--", ".");

                    Files.writeString(r.resolve("scripts/new.sh"), "new\n");
                    assertEquals("", GitOps.cleanTree(r, "scripts").orElse(""), "untracked file");
                    Files.delete(r.resolve("scripts/new.sh"));

                    git(r, "update-index", "--assume-unchanged", "scripts/s.sh");
                    assertEquals("", GitOps.cleanTree(r, "scripts").orElse(""), "assume-unchanged hides nothing");
                    git(r, "update-index", "--no-assume-unchanged", "scripts/s.sh");

                    assertEquals("", GitOps.cleanTree(r, "absent").orElse(""), "not in HEAD");
                    assertEquals("", GitOps.cleanTree(r, "").orElse(""), "no subtree named");
                })

                .runAll();
    }

    private static Path repo() throws Exception {
        Path r = Files.createTempDirectory("index-refresh-");
        Files.writeString(r.resolve("a.txt"), "a\n");
        Files.createDirectories(r.resolve("scripts"));
        Files.writeString(r.resolve("scripts/s.sh"), "s\n");
        git(r, "init", "-b", "main", "--quiet");
        git(r, "add", "-A");
        git(r, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "A");
        return r;
    }

    private static Path sparseRepo() throws Exception {
        Path r = repo();
        Files.createDirectories(r.resolve("excluded"));
        Files.writeString(r.resolve("excluded/x.txt"), "x\n");
        git(r, "add", "-A");
        git(r, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "B");
        assertTrue(SparseInstall.apply(r, List.of("excluded")), "sparse-checkout set");
        return r;
    }

    /** A copy the way a clone makes one: new inodes, mtimes kept. */
    private static Path copyOf(Path source) throws Exception {
        Path target = Files.createTempDirectory("index-refresh-copy-");
        try (var walk = Files.walk(source)) {
            for (Path p : walk.toList()) {
                Path to = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return target;
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
