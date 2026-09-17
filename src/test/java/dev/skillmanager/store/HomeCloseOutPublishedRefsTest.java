package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.source.GitOps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #390: {@code home close-out} on a worktree home whose copy of a unit is
 * a strict ancestor of the project home's.
 *
 * <p>The measured shape: the worktree copy stood on {@code A}, the project copy
 * on {@code B} with {@code A} an ancestor of {@code B}, both clean. The worktree
 * copy also carried a local {@code skill/<ticket>-<unit>} branch inherited from
 * an earlier {@code unit publish}, whose PR was rebase-merged, so its tip exists
 * on the remote but in no newer single-branch clone. The gate called that
 * "diverged" -- unresolvable -- and proposed a {@code home sync} from the older
 * copy onto the newer one.
 *
 * <p>Both halves are pinned here, and so is the control that keeps the fix
 * honest: the same branch, NOT published, still blocks, and the remedy for it
 * is {@code unit publish}, never a sync.
 */
public final class HomeCloseOutPublishedRefsTest {

    private static final String UNIT = "pinned-skill";
    private static final String PUBLISH_BRANCH = "skill/108-cdc-mvp-023-" + UNIT;

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== HomeCloseOutPublishedRefsTest — SKIPPED, git is not on PATH");
            return 0;
        }
        Tests.Suite suite = Tests.suite("HomeCloseOutPublishedRefsTest");

        suite.test("an inherited, published branch does not make an ancestor copy 'diverged'", () -> {
            Fixture f = Fixture.create("published");
            assertTrue(GitOps.isAncestor(f.projectUnit(), f.a, f.b),
                    "precondition: the worktree copy's HEAD is an ancestor of the project's");
            assertFalse(objectExists(f.projectUnit(), f.publishTip),
                    "precondition: the project copy does not hold the publish branch's tip — "
                            + "without this the old rule would have cleared it too");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertTrue(verdict.safe(),
                    "every ref only the worktree holds is published, and its HEAD is behind: "
                            + HomeCloseOut.render(verdict));
            assertEquals(SyncStatus.UNCHANGED, only(verdict).status(),
                    "reported as settled, not conflicted: " + only(verdict).detail());
            assertEquals(f.b, GitOps.headHash(f.projectUnit()), "the project copy did not move");
        });

        suite.test("a remote ref fetched only in the worktree home is not divergence", () -> {
            Fixture f = Fixture.create("remoteref");
            git(f.worktreeUnit(), "branch", "-D", PUBLISH_BRANCH);
            assertTrue(refExists(f.worktreeUnit(), "refs/remotes/origin/" + PUBLISH_BRANCH),
                    "precondition: the worktree copy still has the remote-tracking ref");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertTrue(verdict.safe(), "a remote-tracking ref is published by definition: "
                    + HomeCloseOut.render(verdict));
        });

        suite.test("#370: identical clean HEADs clear the gate whatever the remote refs say", () -> {
            // Measured: both homes clean at dd2d5176, the worktree copy
            // carrying fetched refs the project copy (single-branch) did not,
            // and close-out reported `would-update` and refused.
            Fixture f = Fixture.create("samehead");
            git(f.worktreeUnit(), "branch", "-D", PUBLISH_BRANCH);
            git(f.worktreeUnit(), "reset", "--quiet", "--hard", f.b);
            assertEquals(GitOps.headHash(f.projectUnit()), GitOps.headHash(f.worktreeUnit()),
                    "precondition: both copies stand on the same commit");
            assertTrue(refExists(f.worktreeUnit(), "refs/remotes/origin/" + PUBLISH_BRANCH),
                    "precondition: the worktree copy holds a remote ref the project copy lacks");
            assertFalse(refExists(f.projectUnit(), "refs/remotes/origin/" + PUBLISH_BRANCH),
                    "precondition: the project copy is single-branch");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertTrue(verdict.safe(), "nothing here exists only in the worktree: "
                    + HomeCloseOut.render(verdict));
            assertEquals(SyncStatus.UNCHANGED, only(verdict).status(),
                    "reported as unchanged, not would-update: " + only(verdict).detail());
        });

        suite.test("an unpublished branch still blocks, and the fix is publish, not a backwards sync",
                () -> {
                    Fixture f = Fixture.create("unpublished");
                    // The remote forgets the branch: now the local branch is work
                    // that exists in this home only.
                    git(f.worktreeUnit(), "update-ref", "-d",
                            "refs/remotes/origin/" + PUBLISH_BRANCH);
                    assertFalse(GitOps.isPublished(f.worktreeUnit(), f.publishTip),
                            "precondition: the branch tip is on no remote-tracking ref");

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

                    assertFalse(verdict.safe(), "an unpublished commit blocks the teardown: "
                            + HomeCloseOut.render(verdict));
                    assertEquals(1, verdict.blockers().size(), "one blocker: " + verdict.blockers());
                    String remedy = verdict.blockers().get(0).remedy();
                    assertContains(remedy, "unit publish " + UNIT,
                            "the remedy carries the refs home: " + remedy);
                    assertFalse(remedy.contains("home sync --from"),
                            "and never proposes syncing an older copy onto a newer one: " + remedy);
                    assertContains(remedy, "ahead of this copy",
                            "and says why: " + remedy);
                });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixture

    /**
     * An origin with {@code main} at A then B, and a publish branch whose tip P
     * was never merged as-is (a rebase-merge put B on main instead). The
     * worktree home's copy is a full clone standing on A with the publish
     * branch checked out locally; the project home's copy is a single-branch
     * clone standing on B, so it has never seen P.
     */
    private static final class Fixture {
        final SkillStore worktree;
        final SkillStore project;
        String a;
        String b;
        String publishTip;

        private Fixture(SkillStore worktree, SkillStore project) {
            this.worktree = worktree;
            this.project = project;
        }

        static Fixture create(String label) throws Exception {
            Path root = Files.createTempDirectory("close-out-published-" + label + "-");
            Path origin = root.resolve("origin.git");
            git(root, "init", "--bare", "-b", "main", "--quiet", origin.toString());

            Path staging = root.resolve("staging");
            UnitFixtures.scaffoldSkill(staging, UNIT, DepSpec.empty());
            Path up = staging.resolve(UNIT);
            git(up, "init", "-b", "main", "--quiet");
            git(up, "remote", "add", "origin", origin.toString());
            git(up, "add", "-A");
            commit(up, "A");
            git(up, "push", "--quiet", "origin", "main");
            String a = GitOps.headHash(up);

            git(up, "checkout", "--quiet", "-b", PUBLISH_BRANCH);
            Files.writeString(up.resolve("NOTES.md"), "published work\n");
            git(up, "add", "-A");
            commit(up, "P: the publish branch");
            git(up, "push", "--quiet", "origin", PUBLISH_BRANCH);
            String p = GitOps.headHash(up);

            git(up, "checkout", "--quiet", "main");
            Files.writeString(up.resolve("NOTES.md"), "published work\n");
            git(up, "add", "-A");
            commit(up, "B: the same change, rebase-merged");
            git(up, "push", "--quiet", "origin", "main");
            String b = GitOps.headHash(up);

            Fixture f = new Fixture(store(root.resolve("worktree-home")),
                    store(root.resolve("project-home")));
            f.a = a;
            f.b = b;
            f.publishTip = p;

            Path wt = f.worktreeUnit();
            Files.createDirectories(wt.getParent());
            git(root, "clone", "--quiet", origin.toString(), wt.toString());
            git(wt, "branch", "--quiet", PUBLISH_BRANCH, "origin/" + PUBLISH_BRANCH);
            git(wt, "reset", "--quiet", "--hard", a);

            Path pj = f.projectUnit();
            Files.createDirectories(pj.getParent());
            // --no-local: a path clone hardlinks the whole object store, and
            // then the project copy would hold P after all.
            git(root, "clone", "--quiet", "--no-local", "--single-branch", "--branch", "main",
                    origin.toString(), pj.toString());
            return f;
        }

        Path worktreeUnit() { return worktree.skillDir(UNIT); }

        Path projectUnit() { return project.skillDir(UNIT); }
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static UnitSync only(HomeCloseOut.Verdict verdict) {
        return verdict.units().stream()
                .filter(unit -> unit.unitName().equals(UNIT))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome reported for " + UNIT));
    }

    private static boolean objectExists(Path unit, String rev) {
        return run(unit, List.of("git", "cat-file", "-e", rev + "^{commit}")) == 0;
    }

    private static boolean refExists(Path unit, String ref) {
        return run(unit, List.of("git", "show-ref", "--verify", "--quiet", ref)) == 0;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        if (run(dir, argv) != 0) {
            throw new IOException("fixture git " + String.join(" ", args) + " failed in " + dir);
        }
    }

    /** Commit with an explicit identity: a CI runner has no global git user. */
    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                "commit", "--quiet", "-m", message);
    }

    private static int run(Path workdir, List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.directory(workdir.toFile());
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            return p.waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }
}
