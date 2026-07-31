package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * THE ENCLOSING REPOSITORY IS NOT OURS TO WRITE TO.
 *
 * <p>Every case here builds a THROWAWAY repository under the system temp dir
 * and asserts against that one. Nothing in this file may be pointed at a real
 * checkout: the behaviour under test is "skill-manager silently repointed a
 * repository it does not own", and a reproduction that used a real repository
 * would be the defect rather than a test of it.
 *
 * <h2>What went wrong</h2>
 *
 * A Skill Manager home resolved INSIDE somebody's git working tree — a
 * run-scoped store under a repository's {@code build/} dir, a per-agent home
 * under a run directory. Unit dirs in that home are not repositories, but
 * every git command {@link GitOps} ran with {@code cwd = unitDir} WALKED UP
 * and answered for the enclosing checkout. So
 * {@code setOrigin(unitDir, "<some local source path>")} read the enclosing
 * repository's origin, took the {@code set-url} branch, and rewrote
 * {@code remote.origin.url} of a real repository to a vendored source
 * directory. Worktrees share {@code .git/config}, so the primary checkout went
 * with it. The next {@code git push} would have written into a source tree.
 *
 * <h2>Why the obvious fix is not the fix</h2>
 *
 * "Read the LOCAL config instead" does not scope anything:
 * {@code git -C <nested> config --local --get remote.origin.url} still prints
 * the enclosing repository's URL, because git DISCOVERS the repository by
 * walking up and {@code --local} then names THAT repository's config file.
 * Measured on git 2.50.1. The scope has to come from asking whether
 * {@code dir} is a repository ROOT, which is what {@link GitOps#isGitRepo}
 * now means.
 */
public final class GitOpsRepoBoundaryTest {

    public static int run() throws Exception {
        return Tests.suite("GitOpsRepoBoundaryTest")

                .test("originUrl reports a repository root's own remote", () -> {
                    Path repo = throwawayRepo("plain");
                    git(repo, "remote", "add", "origin", "https://example.invalid/enclosing.git");
                    assertEquals("https://example.invalid/enclosing.git", GitOps.originUrl(repo),
                            "a repository root still answers for itself");
                })

                .test("originUrl does not report the ENCLOSING repository's remote", () -> {
                    Path repo = throwawayRepo("nested-read");
                    git(repo, "remote", "add", "origin", "https://example.invalid/enclosing.git");
                    Path home = repo.resolve("build/run/.skill-manager/skills/some-unit");
                    Files.createDirectories(home);

                    assertEquals(null, GitOps.originUrl(home),
                            "a directory that is not a repository has no origin of its own");
                })

                .test("setOrigin leaves the enclosing repository's remote INTACT", () -> {
                    Path repo = throwawayRepo("nested-rewrite");
                    git(repo, "remote", "add", "origin", "https://example.invalid/enclosing.git");
                    Path unit = repo.resolve("build/run/.skill-manager/skills/some-unit");
                    Files.createDirectories(unit);

                    boolean ok = GitOps.setOrigin(unit, repo.resolve("constituents/vendored").toString());

                    assertFalse(ok, "setOrigin refuses a directory that is not its own repository");
                    assertEquals("https://example.invalid/enclosing.git", configuredOrigin(repo),
                            "the enclosing repository's origin is untouched");
                })

                .test("setOrigin does not ADD an origin to an enclosing repository that has none", () -> {
                    // The `add` branch is as destructive as `set-url` when it
                    // lands on the wrong repository: a repository with no
                    // origin acquires one pointing at a vendored directory.
                    Path repo = throwawayRepo("nested-add");
                    Path unit = repo.resolve("build/run/.skill-manager/skills/some-unit");
                    Files.createDirectories(unit);

                    GitOps.setOrigin(unit, repo.resolve("constituents/vendored").toString());

                    assertEquals(null, configuredOrigin(repo),
                            "the enclosing repository still has no origin");
                })

                .test("a WORKTREE's shared config is not rewritten through a nested home", () -> {
                    // Worktrees share .git/config with the primary checkout, so
                    // this is the blast-radius cell: a home inside a worktree
                    // reached the PRIMARY checkout's remote.
                    Path primary = throwawayRepo("worktree-primary");
                    git(primary, "remote", "add", "origin", "https://example.invalid/primary.git");
                    Files.writeString(primary.resolve("f.txt"), "seed\n");
                    git(primary, "add", "-A");
                    git(primary, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "seed");
                    Path wt = primary.getParent().resolve("wt");
                    git(primary, "worktree", "add", "-q", wt.toString(), "-b", "side");

                    Path unit = wt.resolve("run/.skill-manager/skills/some-unit");
                    Files.createDirectories(unit);
                    GitOps.setOrigin(unit, wt.resolve("constituents/vendored").toString());

                    assertEquals("https://example.invalid/primary.git", configuredOrigin(primary),
                            "the primary checkout's origin is untouched");
                    assertEquals("https://example.invalid/primary.git", GitOps.originUrl(wt),
                            "and the worktree still reads the shared remote");
                })

                .test("setOrigin still rewrites a directory that IS a repository", () -> {
                    // The positive control. Without it the guard above could be
                    // satisfied by a setOrigin that never writes anything.
                    Path repo = throwawayRepo("own-root");
                    git(repo, "remote", "add", "origin", "https://example.invalid/old.git");

                    assertTrue(GitOps.setOrigin(repo, "https://example.invalid/new.git"),
                            "setOrigin succeeds on a repository root");
                    assertEquals("https://example.invalid/new.git", configuredOrigin(repo),
                            "and the root's own origin is rewritten");
                })

                .test("setOrigin adds an origin to a repository root that has none", () -> {
                    Path repo = throwawayRepo("own-root-add");

                    assertTrue(GitOps.setOrigin(repo, "https://example.invalid/added.git"),
                            "setOrigin succeeds on a repository root with no origin");
                    assertEquals("https://example.invalid/added.git", configuredOrigin(repo),
                            "and the origin is added to that root");
                })

                .test("isGitRepo is about the directory itself, not an enclosing checkout", () -> {
                    Path repo = throwawayRepo("is-repo");
                    Path nested = repo.resolve("build/run/.skill-manager/skills/some-unit");
                    Files.createDirectories(nested);

                    assertTrue(GitOps.isGitRepo(repo), "a repository root is a repository");
                    assertFalse(GitOps.isGitRepo(nested),
                            "a plain directory inside somebody's checkout is not one");
                })

                .test("isGitRepo accepts a linked worktree root, whose .git is a FILE", () -> {
                    Path primary = throwawayRepo("is-repo-worktree");
                    Files.writeString(primary.resolve("f.txt"), "seed\n");
                    git(primary, "add", "-A");
                    git(primary, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "seed");
                    Path wt = primary.getParent().resolve("wt");
                    git(primary, "worktree", "add", "-q", wt.toString(), "-b", "side");

                    assertTrue(Files.isRegularFile(wt.resolve(".git")),
                            "a linked worktree carries a .git FILE, so a .git-directory probe would miss it");
                    assertTrue(GitOps.isGitRepo(wt), "a linked worktree root is its own repository");
                })

                .test("initLocalSnapshot inside a checkout initialises the SNAPSHOT, not the checkout", () -> {
                    Path repo = throwawayRepo("snapshot");
                    git(repo, "remote", "add", "origin", "https://example.invalid/enclosing.git");
                    Path unit = repo.resolve("build/run/.skill-manager/skills/some-unit");
                    Files.createDirectories(unit);
                    Files.writeString(unit.resolve("SKILL.md"), "---\nname: u\n---\nbody\n");

                    assertTrue(GitOps.initLocalSnapshot(unit, "https://example.invalid/unit.git"),
                            "the snapshot is initialised");
                    assertEquals("https://example.invalid/unit.git", configuredOrigin(unit),
                            "the snapshot got its own origin");
                    assertEquals("https://example.invalid/enclosing.git", configuredOrigin(repo),
                            "and the enclosing repository's origin is untouched");
                })

                .runAll();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A fresh git repository under the system temp dir. NEVER a real checkout —
     * see the class comment.
     */
    private static Path throwawayRepo(String label) throws Exception {
        Path root = Files.createTempDirectory("gitops-boundary-" + label + "-");
        Path repo = root.resolve("enclosing");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        return repo;
    }

    /**
     * The origin recorded in {@code repo}'s OWN config file, read without git —
     * deliberately not through {@link GitOps}, so the assertion cannot be
     * satisfied by the same walk-up behaviour it is checking for.
     */
    private static String configuredOrigin(Path repo) throws Exception {
        Path gitDir = repo.resolve(".git");
        Path config;
        if (Files.isDirectory(gitDir)) {
            config = gitDir.resolve("config");
        } else if (Files.isRegularFile(gitDir)) {
            // linked worktree: ".git" is "gitdir: <path>"; config is shared and
            // lives in the common dir one level up from <path>/worktrees/<name>.
            String pointer = Files.readString(gitDir).trim();
            Path linked = Path.of(pointer.substring(pointer.indexOf(':') + 1).trim());
            config = linked.getParent().getParent().resolve("config");
        } else {
            return null;
        }
        if (!Files.isRegularFile(config)) return null;
        boolean inOrigin = false;
        for (String line : Files.readString(config).split("\\R")) {
            String t = line.trim();
            if (t.startsWith("[")) {
                inOrigin = t.startsWith("[remote \"origin\"]");
                continue;
            }
            if (inOrigin && t.startsWith("url")) {
                return t.substring(t.indexOf('=') + 1).trim();
            }
        }
        return null;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + exit + "): " + out);
        }
    }
}
