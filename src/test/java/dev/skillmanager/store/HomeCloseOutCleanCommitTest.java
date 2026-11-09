package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.BindingJson;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.bindings.MaterializationMode;
import dev.skillmanager.model.UnitKind;
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
 * {@code home close-out} settles a pair of clean checkouts from git (#401), and
 * nothing else from git. Every case but the first plants something the shortcut
 * must not read past, and asserts the verdict it had before the shortcut existed.
 *
 * <p>The shortcut is told apart by its own detail sentence, so a pair that was
 * decided the slow way cannot pass the first case by coincidence.
 */
public final class HomeCloseOutCleanCommitTest {

    private HomeCloseOutCleanCommitTest() {}

    private static final String UNIT = "large-unit";
    private static final String GIT_DECIDED = "clean checkouts of the same commit";

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== HomeCloseOutCleanCommitTest — SKIPPED, git is not on PATH");
            return 0;
        }
        Tests.Suite suite = Tests.suite("HomeCloseOutCleanCommitTest");

        suite.test("clean checkouts of one commit on the same refs are settled by git", () -> {
            Fixture f = Fixture.create("twins");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertTrue(verdict.safe(), "nothing differs: " + HomeCloseOut.render(verdict));
            assertEquals(SyncStatus.UNCHANGED, only(verdict).status(), only(verdict).detail());
            assertContains(only(verdict).detail(), GIT_DECIDED,
                    "decided from git, not by reading: " + only(verdict).detail());
        });

        suite.test("an uncommitted edit in the worktree home still blocks", () -> {
            Fixture f = Fixture.create("edit");
            Path skill = f.worktreeUnit().resolve("SKILL.md");
            Files.writeString(skill, Files.readString(skill) + "\nan unpublished edit\n");

            assertBlockedBySync(f, HomeCloseOut.inspect(f.worktree, f.project));
        });

        suite.test("an untracked file in the worktree home still blocks", () -> {
            Fixture f = Fixture.create("untracked");
            Files.writeString(f.worktreeUnit().resolve("FINDINGS.md"), "exists only here\n");

            assertBlockedBySync(f, HomeCloseOut.inspect(f.worktree, f.project));
        });

        suite.test("an unpushed commit on HEAD still blocks", () -> {
            Fixture f = Fixture.create("unpushed");
            Files.writeString(f.worktreeUnit().resolve("NOTES.md"), "committed here only\n");
            git(f.worktreeUnit(), "add", "-A");
            commit(f.worktreeUnit(), "work that was never pushed");

            assertBlockedBySync(f, HomeCloseOut.inspect(f.worktree, f.project));
        });

        suite.test("a side-branch commit with HEAD and worktree unmoved still blocks", () -> {
            // The case a commit-only comparison destroys: both copies are clean
            // at one commit, and the only thing that differs is a ref.
            Fixture f = Fixture.create("sidebranch");
            String head = GitOps.headHash(f.worktreeUnit());
            git(f.worktreeUnit(), "checkout", "--quiet", "-b", "side");
            Files.writeString(f.worktreeUnit().resolve("NOTES.md"), "on a side branch\n");
            git(f.worktreeUnit(), "add", "-A");
            commit(f.worktreeUnit(), "side work");
            git(f.worktreeUnit(), "checkout", "--quiet", "main");
            assertEquals(head, GitOps.headHash(f.worktreeUnit()), "precondition: HEAD is unmoved");
            assertEquals(GitOps.headHash(f.projectUnit()), head, "precondition: one commit on both");
            assertEquals("", GitOps.porcelainStatusNoLock(f.worktreeUnit()).strip(),
                    "precondition: the worktree copy is clean");

            assertBlockedBySync(f, HomeCloseOut.inspect(f.worktree, f.project));
        });

        suite.test("a stash with HEAD and worktree unmoved still blocks", () -> {
            Fixture f = Fixture.create("stash");
            Path skill = f.worktreeUnit().resolve("SKILL.md");
            Files.writeString(skill, Files.readString(skill) + "\nstashed\n");
            git(f.worktreeUnit(), "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                    "stash", "push", "--quiet");
            assertEquals("", GitOps.porcelainStatusNoLock(f.worktreeUnit()).strip(),
                    "precondition: the stash left the tree clean");

            assertBlockedBySync(f, HomeCloseOut.inspect(f.worktree, f.project));
        });

        suite.test("a destination edit is not read as equality", () -> {
            // A clean commit in the worktree home proves nothing about a
            // destination that is not clean. Safe, as it was: the source holds
            // nothing the destination lacks. But not by the equality route.
            Fixture f = Fixture.create("destedit");
            Path skill = f.projectUnit().resolve("SKILL.md");
            Files.writeString(skill, Files.readString(skill) + "\nan edit in the project home\n");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertTrue(verdict.safe(), "the worktree holds nothing of its own: "
                    + HomeCloseOut.render(verdict));
            assertEquals(SyncStatus.UNCHANGED, only(verdict).status(), only(verdict).detail());
            assertFalse(only(verdict).detail().contains(GIT_DECIDED),
                    "a dirty destination is not 'the same': " + only(verdict).detail());
            assertContains(only(verdict).detail(), "the destination is ahead", only(verdict).detail());
        });

        suite.test("clean checkouts of different commits do not share the verdict", () -> {
            Fixture f = Fixture.create("twocommits");
            Path unit = f.projectUnit();
            Files.writeString(unit.resolve("NOTES.md"), "pushed by another worktree\n");
            git(unit, "add", "-A");
            commit(unit, "newer");
            git(unit, "push", "--quiet", "origin", "main");

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertFalse(only(verdict).detail().contains(GIT_DECIDED),
                    "two commits are not one commit: " + only(verdict).detail());
        });

        suite.test("a unit only the worktree home holds still blocks", () -> {
            Fixture f = Fixture.create("newunit");
            Fs_deleteTree(f.projectUnit());

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertFalse(verdict.safe(), "it exists nowhere else: " + HomeCloseOut.render(verdict));
            assertEquals(SyncStatus.NEW, only(verdict).status(), only(verdict).detail());
            assertContains(verdict.blockers().get(0).remedy(), "home sync --from",
                    verdict.blockers().get(0).remedy());
        });

        suite.test("custody: a destination materialized as a git checkout still holds back", () -> {
            Fixture f = Fixture.create("custody-dest");
            writeCheckoutRecord(new ChildHomeMaterializer(f.worktree, f.project), UNIT);

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertFalse(verdict.safe(), "custody is a blocker: " + HomeCloseOut.render(verdict));
            assertEquals(SyncStatus.HELD_BACK, only(verdict).status(), only(verdict).detail());
            assertFalse(only(verdict).detail().contains(GIT_DECIDED),
                    "custody is decided before identity: " + only(verdict).detail());
            assertContains(verdict.blockers().get(0).remedy(), "unit publish " + UNIT,
                    verdict.blockers().get(0).remedy());
        });

        suite.test("custody: a worktree checkout with unaccounted history blocks though the pair is clean", () -> {
            // The interplay that matters: identity settles the pair as UNCHANGED,
            // and the checkout's own git-history question still has to fire.
            Fixture f = Fixture.create("custody-source");
            writeCheckoutRecord(new ChildHomeMaterializer(f.project, f.worktree), UNIT);

            HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.worktree, f.project);

            assertContains(only(verdict).detail(), GIT_DECIDED, only(verdict).detail());
            assertFalse(verdict.safe(), "unaccounted history blocks: " + HomeCloseOut.render(verdict));
            assertContains(verdict.blockers().get(0).remedy(), "a file copy cannot carry it",
                    verdict.blockers().get(0).remedy());
        });

        return suite.runAll();
    }

    /** A CHECKOUT record with no history digest: git has nothing to clear it with. */
    private static void writeCheckoutRecord(ChildHomeMaterializer materializer, String unit)
            throws IOException {
        Path file = materializer.recordFile(unit, UnitKind.SKILL);
        Files.createDirectories(file.getParent());
        BindingJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),
                new ChildHomeMaterializer.MaterializationRecord(
                        2, unit, UnitKind.SKILL.name(),
                        MaterializationMode.CHECKOUT.name(), null, null, null, null, null, null,
                        "2026-01-01T00:00:00Z", null, null));
    }

    private static void assertBlockedBySync(Fixture f, HomeCloseOut.Verdict verdict) {
        assertFalse(verdict.safe(), "there is work only the worktree holds: "
                + HomeCloseOut.render(verdict));
        assertFalse(only(verdict).detail().contains(GIT_DECIDED),
                "not decided by identity: " + only(verdict).detail());
        assertEquals(1, verdict.blockers().size(), "one blocker: " + verdict.blockers());
        assertContains(verdict.blockers().get(0).remedy(), "home sync --from",
                "the existing remedy: " + verdict.blockers().get(0).remedy());
        assertEquals(SyncStatus.UPDATED, only(verdict).status(), only(verdict).detail());
    }

    // ------------------------------------------------------------- fixture

    /** One origin, cloned independently into a worktree home and a project home. */
    private static final class Fixture {
        final SkillStore worktree;
        final SkillStore project;

        private Fixture(SkillStore worktree, SkillStore project) {
            this.worktree = worktree;
            this.project = project;
        }

        static Fixture create(String label) throws Exception {
            Path root = Files.createTempDirectory("close-out-clean-commit-" + label + "-");
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

            Fixture f = new Fixture(store(root.resolve("worktree-home")),
                    store(root.resolve("project-home")));
            Files.createDirectories(f.worktreeUnit().getParent());
            Files.createDirectories(f.projectUnit().getParent());
            git(root, "clone", "--quiet", "--no-local", origin.toString(), f.worktreeUnit().toString());
            git(root, "clone", "--quiet", "--no-local", origin.toString(), f.projectUnit().toString());
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

    private static void Fs_deleteTree(Path dir) throws IOException {
        dev.skillmanager.shared.util.Fs.deleteRecursive(dir);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        if (run(dir, argv) != 0) {
            throw new IOException("fixture git " + String.join(" ", args) + " failed in " + dir);
        }
    }

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
