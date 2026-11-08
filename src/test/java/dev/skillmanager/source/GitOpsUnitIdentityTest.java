package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.source.GitOps.UnitGitState;

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
 * #412: a unit's Git identity is the unit's own, or the unit is DAMAGED.
 *
 * <p>Every case builds throwaway repositories under the system temp dir. The
 * hazard is git DISCOVERY: from a directory whose {@code .git} is partial (no
 * HEAD, no config), {@code git rev-parse} silently walks up and answers for
 * whatever repository encloses it -- in the consumer close-out that was the
 * application worktree, whose branches were then read as unpublished skill
 * history.
 */
public final class GitOpsUnitIdentityTest {

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== GitOpsUnitIdentityTest — SKIPPED, git is not on PATH");
            return 0;
        }
        return Tests.suite("GitOpsUnitIdentityTest")

                .test("a valid nested repository is its own unit, enclosing repo or not", () -> {
                    Path app = enclosingApp("valid");
                    Path unit = unitRepo(app.resolve("homes/h/skills/u"));
                    GitOps.UnitGitIdentity id = GitOps.unitGitIdentity(unit);
                    assertEquals(UnitGitState.OWN, id.state(), "own repo: " + id);
                    assertTrue(Files.isSameFile(unit, id.toplevel()), "toplevel is the unit: " + id);
                    assertEquals(GitOps.headHash(unit), git(unit, "rev-parse", "HEAD").trim(),
                            "queries still answer for the unit");
                })

                .test("no .git at all is ABSENT, not damaged (that is #333's territory)", () -> {
                    Path app = enclosingApp("absent");
                    Path unit = app.resolve("homes/h/skills/plain");
                    Files.createDirectories(unit);
                    assertEquals(UnitGitState.ABSENT, GitOps.unitGitIdentity(unit).state(), "absent");
                })

                .test("a partial .git (no HEAD, no config) inside an application repo is DAMAGED", () -> {
                    Path app = enclosingApp("partial");
                    Path unit = unitRepo(app.resolve("homes/h/skills/u"));
                    Files.delete(unit.resolve(".git/HEAD"));
                    Files.delete(unit.resolve(".git/config"));
                    assertTrue(Files.isSameFile(app,
                                    Path.of(git(unit, "rev-parse", "--show-toplevel").trim())),
                            "precondition: plain git discovery answers for the application repo");

                    GitOps.UnitGitIdentity id = GitOps.unitGitIdentity(unit);
                    assertEquals(UnitGitState.DAMAGED, id.state(), "damaged: " + id);
                    assertFalse(String.valueOf(id.reason()).contains("app-feature"),
                            "the reason does not borrow the application's identity: " + id.reason());
                })

                .test("every unit query on a partial .git refuses instead of reading the enclosing repo", () -> {
                    Path app = enclosingApp("queries");
                    String appHead = git(app, "rev-parse", "HEAD").trim();
                    Path unit = unitRepo(app.resolve("homes/h/skills/u"));
                    Files.delete(unit.resolve(".git/HEAD"));
                    assertEquals(null, GitOps.headHash(unit), "no HEAD of its own -> no HEAD");
                    String refs = GitOps.refListing(unit);
                    assertTrue(refs == null || !refs.contains("app-feature-unpublished"),
                            "the application's branch is not this unit's ref: " + refs);
                    assertFalse(GitOps.isAncestor(unit, appHead, appHead),
                            "ancestry is not answered from the application's object store");
                    assertEquals(null, GitOps.porcelainStatusNoLock(unit),
                            "status is not the application's status");
                    assertFalse(GitOps.isGitRepo(unit), "not a repository root");
                })

                .test("a .git missing only its config is DAMAGED", () -> {
                    Path unit = unitRepo(Files.createTempDirectory("gitops-identity-noconfig-").resolve("u"));
                    Files.delete(unit.resolve(".git/config"));
                    assertEquals(UnitGitState.DAMAGED, GitOps.unitGitIdentity(unit).state(), "damaged");
                })

                .test("a partial .git with no enclosing repository is DAMAGED too", () -> {
                    Path unit = unitRepo(Files.createTempDirectory("gitops-identity-lonely-").resolve("u"));
                    Files.delete(unit.resolve(".git/HEAD"));
                    assertEquals(UnitGitState.DAMAGED, GitOps.unitGitIdentity(unit).state(), "damaged");
                })

                .test("a linked git worktree (.git is a FILE) is its own unit", () -> {
                    Path app = enclosingApp("linked");
                    Path main = unitRepo(Files.createTempDirectory("gitops-identity-main-").resolve("m"));
                    Path linked = app.resolve("homes/h/skills/linked");
                    Files.createDirectories(linked.getParent());
                    git(main, "worktree", "add", "-q", "-b", "side", linked.toString());
                    GitOps.UnitGitIdentity id = GitOps.unitGitIdentity(linked);
                    assertEquals(UnitGitState.OWN, id.state(), "linked worktree: " + id);
                    assertFalse(id.gitDir().equals(id.commonDir()),
                            "the git dir and common dir of a linked worktree differ: " + id);
                })

                .test("a symlinked alias of a valid unit is still its own unit", () -> {
                    Path app = enclosingApp("alias");
                    Path unit = unitRepo(app.resolve("homes/h/skills/u"));
                    Path alias = Files.createTempDirectory("gitops-identity-alias-").resolve("alias");
                    Files.createSymbolicLink(alias, unit);
                    assertEquals(UnitGitState.OWN, GitOps.unitGitIdentity(alias).state(), "alias");
                    assertEquals(GitOps.headHash(unit), GitOps.headHash(alias), "same HEAD via alias");
                })

                .runAll();
    }

    /** An application repository with a branch that exists on no remote. */
    static Path enclosingApp(String label) throws Exception {
        Path app = Files.createTempDirectory("gitops-identity-" + label + "-").resolve("app");
        Files.createDirectories(app);
        git(app, "init", "-q", "-b", "main");
        Files.writeString(app.resolve("README.md"), "app\n");
        git(app, "add", "-A");
        commit(app, "app main");
        git(app, "checkout", "-q", "-b", "app-feature-unpublished");
        Files.writeString(app.resolve("f.txt"), "f\n");
        git(app, "add", "-A");
        commit(app, "app feature");
        git(app, "checkout", "-q", "main");
        return app;
    }

    static Path unitRepo(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: u\ndescription: d\n---\n");
        git(dir, "init", "-q", "-b", "main");
        git(dir, "add", "-A");
        commit(dir, "unit");
        return dir;
    }

    private static void commit(Path dir, String msg) throws Exception {
        git(dir, "-c", "user.email=t@localhost", "-c", "user.name=t", "commit", "-q", "-m", msg);
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        int exit = p.waitFor();
        if (exit != 0) throw new IllegalStateException("git " + String.join(" ", args) + " (" + exit + "): " + out);
        return out.toString();
    }
}
