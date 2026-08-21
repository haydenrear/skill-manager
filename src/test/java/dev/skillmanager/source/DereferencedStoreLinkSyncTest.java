package dev.skillmanager.source;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

        suite.test("the remedy for a stash-pop residue is not the remedy for a merge", () -> {
            Fixture f = Fixture.create("remedy");

            // Clean: the record has simply not caught up, and the reconcile
            // retires it on the next command. Sending anyone to a store
            // directory to fix nothing is the shape being removed.
            String clean = dev.skillmanager.app.ReportUseCase
                    .mergeConflictRemedy(f.unit, "u");
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
                    .mergeConflictRemedy(f.unit, "u");
            assertContains(residue, "reset",
                    "the remedy is the one that clears unmerged stages with no MERGE_HEAD");
            // Not `git add`: the mid-merge instruction is the one that could
            // not clear this state. The message may still SAY that `git commit`
            // has nothing to do -- explaining why is not prescribing it.
            assertFalse(residue.contains("git add"),
                    "the mid-merge instruction is not printed for a stash-pop residue: " + residue);
        });

        return suite.runAll();
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
