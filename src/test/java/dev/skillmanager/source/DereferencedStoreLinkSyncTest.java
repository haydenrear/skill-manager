package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * HIS-4 (#216): a dereferenced in-unit store link is not somebody's work, and
 * the remedy printed for a stash-pop residue is not the remedy for a merge.
 *
 * <h2>The drag these cases pin</h2>
 *
 * <p>{@code ChildHomeMaterializer} turns an in-unit symlink that points into
 * the parent store into a real directory, so the child home holds its own bytes
 * (CHM-5). Inside a git working tree that reads as a DELETION of a path the
 * repository tracks at mode {@code 120000}, and {@code sync} then refuses
 * forever: {@code 2 of 21} units in the operator's project home, nine days,
 * inherited by every worktree cloned from it. The printed remedy could not
 * clear it — the working tree cannot hold both a directory and a symlink at one
 * path — and the state re-armed on the next sync.
 *
 * <h2>Why the negative cases are the important ones</h2>
 *
 * <p>The cheap fix for all of this is "a symlink in HEAD with a directory on
 * disk is never a local change", and that fix silently discards an author's
 * work: git can represent exactly that shape as an ordinary commit. So three of
 * the five cases below assert that the exclusion does NOT fire —
 *
 * <ul>
 *   <li>a link that resolves INSIDE the unit, which the materializer
 *       deliberately leaves alone and which is therefore the unit's own
 *       content;</li>
 *   <li>a unit that carries a real edit ALONGSIDE the dereferenced tree, which
 *       must still be reported and still refused;</li>
 *   <li>a store link that is still a symlink, which is not a divergence at
 *       all.</li>
 * </ul>
 *
 * <p>Each was run against the fix disabled, and each reddened; the failure
 * messages are recorded in
 * {@code results/epic-home-integrity-sync/tickets/HIS-4/goal-contribution.md}.
 * This epic has had five assertions pass against broken code, one of them in
 * this defect's own graph node, so an assertion nobody ran against the bug is
 * not treated as coverage here.
 */
public final class DereferencedStoreLinkSyncTest {

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== DereferencedStoreLinkSyncTest — SKIPPED, git is not on PATH");
            return 0;
        }
        Tests.Suite suite = Tests.suite("DereferencedStoreLinkSyncTest");

        suite.test("a dereferenced store link is named, and is not an authored change", () -> {
            Fixture f = Fixture.create("deref");
            f.dereference("test_graph/build-logic");

            assertTrue(GitOps.hasWorktreeChanges(f.unit),
                    "git really does read the dereference as a change — without this the rest "
                            + "of the case is vacuous, which is how the first version of this "
                            + "defect's graph node passed while reproducing nothing");

            Set<String> found = DereferencedStoreLinks.in(f.unit);
            assertTrue(found.contains("test_graph/build-logic"),
                    "the dereferenced store link is recognised, got " + found);
            assertFalse(DereferencedStoreLinks.hasAuthoredWorktreeChanges(f.unit),
                    "a unit whose ONLY divergence is the materialization has nothing an "
                            + "author wrote in it");
        });

        suite.test("a link resolving inside the unit is the unit's own content", () -> {
            Fixture f = Fixture.create("internal");
            // The materializer's `internalRelative` case: it does NOT dereference
            // these, so a directory standing where one used to be is somebody's
            // edit and excluding it would discard the edit.
            f.dereference("internal-link");

            assertFalse(DereferencedStoreLinks.in(f.unit).contains("internal-link"),
                    "a link that resolves inside the unit is never a materialization artifact");
            assertTrue(DereferencedStoreLinks.hasAuthoredWorktreeChanges(f.unit),
                    "so replacing it IS an authored change and sync must still refuse");
        });

        suite.test("a real edit alongside the materialization is still reported", () -> {
            Fixture f = Fixture.create("alongside");
            f.dereference("test_graph/build-logic");
            Files.writeString(f.unit.resolve("SKILL.md"), "an agent wrote this\n");

            List<String> authored = DereferencedStoreLinks.worktreeChangesBeyond(
                    f.unit, DereferencedStoreLinks.in(f.unit));
            assertTrue(DereferencedStoreLinks.hasAuthoredWorktreeChanges(f.unit),
                    "the edit is still there to protect");
            assertTrue(authored.size() == 1,
                    "exactly the edit is reported, not the materialization: " + authored);
            assertContains(String.join("|", authored), "SKILL.md",
                    "and it is named, so a refusal can say what it is refusing over");
        });

        suite.test("an intact store link is not a divergence", () -> {
            Fixture f = Fixture.create("intact");
            assertTrue(Files.isSymbolicLink(f.unit.resolve("test_graph/build-logic")),
                    "the fixture really left the link alone");
            assertTrue(DereferencedStoreLinks.in(f.unit).isEmpty(),
                    "nothing to exclude when nothing was dereferenced");
            assertFalse(DereferencedStoreLinks.hasAuthoredWorktreeChanges(f.unit),
                    "and the tree is clean");
        });

        // ------------------------------------------------------------------
        // HIGH-3. The SECOND half of isAuthoredDirty -- "HEAD is past the
        // recorded baseline" -- is the half whose javadoc calls it load-bearing
        // ("a HEAD ahead of the baseline is HIS-4's link 3, and it must still
        // stop an overwrite"), and an adversarial review found it had ZERO
        // coverage: replacing the body with `return
        // hasAuthoredWorktreeChanges(storeDir)` passed 1224 cases in 135 suites
        // with no new failure. The graph node cannot catch it either -- its
        // negative control is a working-TREE edit, never a COMMIT ahead of the
        // baseline. Two probes anticipated the two wrong fixes in the other
        // direction; this is the third, in the direction named as load-bearing,
        // and nothing was watching it.
        // ------------------------------------------------------------------
        suite.test("a commit ahead of the baseline is dirty even with a clean tree", () -> {
            Fixture f = Fixture.create("ahead");
            String baseline = f.head();

            Files.writeString(f.unit.resolve("SKILL.md"), "---\nname: u\ncommitted\n---\n");
            f.commit("an agent committed work in the store copy");

            assertTrue(GitOps.porcelainStatus(f.unit).isBlank(),
                    "the working tree is CLEAN -- the work is in .git and nowhere else, which "
                            + "is exactly the state issue #29 records as the least recoverable");
            assertFalse(f.head().equals(baseline), "and HEAD really moved off the baseline");

            assertTrue(DereferencedStoreLinks.isAuthoredDirty(f.unit, baseline),
                    "a store copy whose HEAD is past the installed baseline has work a sync "
                            + "would overwrite, and the worktree half cannot see it");
            assertFalse(DereferencedStoreLinks.isAuthoredDirty(f.unit, f.head()),
                    "and it is not dirty against the baseline it is actually standing on, or "
                            + "the assertion above would hold for any repository at all");
        });

        suite.test("a commit ahead is dirty even when a dereference is also present", () -> {
            // The two halves together, because the fix makes the FIRST half
            // blind to a dereference and a reader could reasonably expect that
            // blindness to swallow the second half with it.
            Fixture f = Fixture.create("ahead-and-deref");
            String baseline = f.head();
            f.dereference("test_graph/build-logic");
            Files.writeString(f.unit.resolve("SKILL.md"), "---\nname: u\ncommitted\n---\n");
            f.commit("committed alongside the materialization");

            assertFalse(DereferencedStoreLinks.hasAuthoredWorktreeChanges(f.unit),
                    "the worktree half still sees nothing but the materialization");
            assertTrue(DereferencedStoreLinks.isAuthoredDirty(f.unit, baseline),
                    "but the unit is still dirty, because the commit is still there to lose");
        });

        // ------------------------------------------------------------------
        // HIGH-1. `sync --from` applies by Fs.deleteRecursive(storeDir) then
        // Fs.copyRecursive(src, storeDir) -- a WHOLESALE REPLACE with no
        // carry-over. Before HIS-4 a materialized copy never reached it (the
        // dereference made it read dirty and the handler refused); HIS-4 taught
        // the gate that a dereference is not an author's work and thereby
        // walked this path into the delete. Review reproduced it end to end at
        // exit 0: the link came back pointing OUT of the unit, and the child
        // home's own bytes were gone.
        //
        // This pins the mechanism directly: lift, destroy the tree completely,
        // restore, and require the child's bytes back.
        // ------------------------------------------------------------------
        suite.test("a wholesale replace does not destroy the materialized tree", () -> {
            Fixture f = Fixture.create("replace");

            // THE SOURCE IS SNAPSHOTTED BEFORE THE DEREFERENCE, and that
            // ordering is the whole validity of this case. `sync --from <src>`
            // copies the UPSTREAM shape -- which still holds the symlink and
            // has never heard of the child's bytes. An earlier draft of this
            // test snapshotted it AFTER, so the copy itself carried
            // child-only.txt back and the case passed with the escrow removed:
            // a vacuous test of the exact kind this epic keeps meeting, caught
            // by running it against the disabled fix rather than by reading it.
            Path pristine = f.root.resolve("upstream-copy");
            copyTree(f.unit, pristine);

            f.dereference("test_graph/build-logic");
            Path childOnly = f.unit.resolve("test_graph/build-logic/child-only.txt");
            Files.writeString(childOnly, "bytes that exist in the child home and nowhere else\n");

            Path home = f.root.resolve("home");
            Files.createDirectories(home);

            MaterializationEscrow escrow = MaterializationEscrow.lift(f.unit, home, false);
            assertFalse(escrow.isEmpty(),
                    "the escrow really took something — an empty escrow would make the "
                            + "assertions below pass over a test that protected nothing");
            // The destruction the apply path performs, verbatim in shape.
            dev.skillmanager.shared.util.Fs.deleteRecursive(f.unit);
            copyTree(pristine, f.unit);
            escrow.restore();

            Path back = f.unit.resolve("test_graph/build-logic");
            assertFalse(Files.isSymbolicLink(back),
                    "the path is NOT a symlink again — a child home that links back out of "
                            + "itself has lost the independence CHM-5 gave it");
            assertTrue(Files.isDirectory(back, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    "it is a real directory");
            assertTrue(Files.isRegularFile(childOnly),
                    "and the child home's own bytes survived the replace — this is the exact "
                            + "file review measured as `No such file or directory`");
            assertContains(Files.readString(childOnly), "nowhere else", "byte-identical");
        });

        suite.test("restore replaces a path upstream converted to real content", () -> {
            // MED-4's second half. Upstream is entitled to turn a tracked
            // symlink into a real directory -- the managed-bindings migration
            // did exactly that -- and the first version of restore() used
            // deleteIfExists, which throws DirectoryNotEmptyException on a
            // non-empty directory and stranded the escrowed bytes in the cache.
            Fixture f = Fixture.create("converted");
            f.dereference("test_graph/build-logic");
            Files.writeString(f.unit.resolve("test_graph/build-logic/child-only.txt"), "child\n");

            Path home = f.root.resolve("home");
            Files.createDirectories(home);
            MaterializationEscrow escrow = MaterializationEscrow.lift(f.unit, home, false);
            assertFalse(escrow.isEmpty(), "the escrow took the tree");

            // Upstream's version of that path: real content, several files.
            Path converted = f.unit.resolve("test_graph/build-logic");
            dev.skillmanager.shared.util.Fs.deleteRecursive(converted);
            Files.createDirectories(converted);
            Files.writeString(converted.resolve("a.txt"), "upstream a\n");
            Files.writeString(converted.resolve("b.txt"), "upstream b\n");

            escrow.restore();

            assertTrue(Files.isRegularFile(f.unit.resolve("test_graph/build-logic/child-only.txt")),
                    "the escrowed tree replaced the non-empty directory rather than being "
                            + "stranded in the cache");
            assertFalse(Files.exists(f.unit.resolve("test_graph/build-logic/a.txt")),
                    "and upstream's content is not left mixed in with it");
        });

        suite.test("the remedy for a stash-pop residue is not the remedy for a merge", () -> {
            Fixture f = Fixture.create("remedy");

            // Clean: the record has simply not caught up, and the reconcile
            // retires it on the next command. Sending anyone to a store
            // directory to fix nothing is the shape being removed.
            String clean = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(f.unit, "u", null);
            assertContains(clean, "already clear",
                    "a cleared condition says so rather than naming a git operation");

            // Unmerged paths with NO MERGE_HEAD -- the measured state, which
            // `git add` + `git commit` cannot clear.
            f.plantStashPopResidue();
            assertFalse(GitOps.isMidMerge(f.unit),
                    "no MERGE_HEAD -- this is stash-pop residue, not a merge in progress");
            assertFalse(GitOps.unmergedFiles(f.unit).isEmpty(),
                    "and there really are unmerged stages, or the case proves nothing");

            String residue = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(f.unit, "u", null);
            assertContains(residue, "reset",
                    "the remedy is the one that clears unmerged stages with no MERGE_HEAD");
            // Not `git add`: the mid-merge instruction is the one that could
            // not clear this state. The message may still SAY that `git commit`
            // has nothing to do -- explaining why is not prescribing it.
            assertFalse(residue.contains("git add"),
                    "the mid-merge instruction is not printed for a stash-pop residue: " + residue);
        });

        // ------------------------------------------------------------------
        // HIGH-2, second half. After a rolled-back conflict the store is
        // byte-for-byte where it started: no unmerged paths, no MERGE_HEAD.
        // Asked with no other information the remedy said "already clear" --
        // telling an operator whose sync had just refused that nothing was
        // wrong -- so the PRE-ROLLBACK conflict count is passed in and selects
        // its own branch.
        // ------------------------------------------------------------------
        suite.test("a rolled-back conflict is not reported as already clear", () -> {
            Fixture f = Fixture.create("rolledback");
            assertTrue(GitOps.porcelainStatus(f.unit).isBlank(),
                    "the store really is clean after a rollback — which is exactly why the "
                            + "store-derived branches cannot tell this state from a resolved one");

            String clean = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(f.unit, "u", null, 0);
            assertContains(clean, "already clear",
                    "with nothing reported, a clean store still means the record lagged");

            String rolledBack = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(f.unit, "u", null, 3);
            assertFalse(rolledBack.contains("already clear"),
                    "a conflict that was rolled back is NOT 'already clear': " + rolledBack);
            assertContains(rolledBack, "rolled back", "it says what happened");
            assertContains(rolledBack, "3 local file(s)", "and how much conflicts");
            assertContains(rolledBack, "sync u", "and ends in a command that changes the verdict");
        });

        return suite.runAll();
    }

    /** Plain recursive copy that preserves symlinks, for the replace fixture. */
    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isSymbolicLink(p)) {
                    Files.createDirectories(dest.getParent());
                    Files.createSymbolicLink(dest, Files.readSymbolicLink(p));
                } else if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        }
    }

    // ------------------------------------------------------------- fixture

    /**
     * A one-unit git repository shaped like a scaffolded, change-managed unit:
     * a {@code test_graph/.gitignore} carrying the block the test-graph
     * scaffolder generates, a store link TRACKED at mode {@code 120000} that
     * escapes the unit, and a second link that resolves inside it.
     */
    private record Fixture(Path root, Path unit) {

        static Fixture create(String label) throws Exception {
            Path root = Files.createTempDirectory("his4-" + label + "-");
            Path provider = root.resolve("provider/project_sdk_sources/build-logic");
            Files.createDirectories(provider);
            Files.writeString(provider.resolve("build.gradle.kts"), "// provider\n");

            Path unit = root.resolve("unit");
            Files.createDirectories(unit.resolve("test_graph"));
            Files.createDirectories(unit.resolve("own-tree"));
            Files.writeString(unit.resolve("SKILL.md"), "---\nname: u\n---\n");
            Files.writeString(unit.resolve("own-tree/NOTE.md"), "the unit's own\n");
            Files.writeString(unit.resolve("test_graph/.gitignore"), """
                    # TEST-GRAPH-MANAGED-BINDINGS-BEGIN
                    # Generated runtime links; provider-bindings.json is the durable record.
                    /build-logic
                    # TEST-GRAPH-MANAGED-BINDINGS-END
                    """);
            Files.createSymbolicLink(unit.resolve("test_graph/build-logic"),
                    Path.of("../../provider/project_sdk_sources/build-logic"));
            Files.createSymbolicLink(unit.resolve("internal-link"), Path.of("own-tree"));

            git(unit, "init", "--initial-branch=main");
            git(unit, "config", "user.email", "his4@test.invalid");
            git(unit, "config", "user.name", "his4");
            // -f, because the generated .gitignore covers the link and the real
            // units carry it TRACKED from before the managed-bindings migration.
            git(unit, "add", "-A", "-f");
            git(unit, "commit", "-m", "unit");
            return new Fixture(root, unit);
        }

        String head() throws Exception {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(unit.toFile()).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out;
        }

        void commit(String message) throws Exception {
            git(unit, "add", "-A", "-f");
            git(unit, "commit", "-m", message);
        }

        /** Exactly what {@code ChildHomeMaterializer} does: link out, real directory in. */
        void dereference(String rel) throws IOException {
            Path at = unit.resolve(rel);
            Path target = at.getParent().resolve(Files.readSymbolicLink(at)).normalize();
            Files.delete(at);
            Files.createDirectories(at);
            if (Files.isDirectory(target)) {
                try (var s = Files.list(target)) {
                    for (Path child : s.toList()) {
                        Files.copy(child, at.resolve(child.getFileName()));
                    }
                }
            }
        }

        /**
         * The measured state, made by git rather than written by hand: unmerged
         * index stages and NO {@code MERGE_HEAD}. A {@code stash pop} whose
         * stash and whose HEAD both touched one file leaves precisely that.
         */
        void plantStashPopResidue() throws Exception {
            Files.writeString(unit.resolve("SKILL.md"), "---\nname: u\nlocal\n---\n");
            git(unit, "stash", "push", "-m", "local");
            Files.writeString(unit.resolve("SKILL.md"), "---\nname: u\nupstream\n---\n");
            git(unit, "add", "-A");
            git(unit, "commit", "-m", "moved on");
            // Expected to fail: that failure IS the state under test.
            run(unit, "git", "stash", "pop");
        }

        private static void git(Path cwd, String... argv) throws Exception {
            String[] cmd = new String[argv.length + 1];
            cmd[0] = "git";
            System.arraycopy(argv, 0, cmd, 1, argv.length);
            int rc = run(cwd, cmd);
            if (rc != 0) throw new IOException("git " + String.join(" ", argv) + " rc=" + rc);
        }

        private static int run(Path cwd, String... cmd) throws Exception {
            Process p = new ProcessBuilder(cmd).directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor();
        }
    }
}
