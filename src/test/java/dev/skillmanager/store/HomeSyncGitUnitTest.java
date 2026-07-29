package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
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
import java.util.Map;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A unit that was installed from a git source, reconciled between two homes:
 * issue #29, and the reason it needed its own suite is that <b>the obvious fix
 * for it destroys commits.</b>
 *
 * <h2>The defect, and the trap inside the fix</h2>
 *
 * <p>A git-sourced unit keeps {@code .git} inside the home, so a COPY carries
 * it. {@code .git} rewrites itself on every command, {@code git status} and
 * {@code git gc} included, so a whole-tree digest moves off the record the first
 * time anybody touches the unit and every later pass reports it {@code locally
 * modified}. Nothing clears that, ever — a false positive on exactly the units
 * an agent is most likely to be working in.
 *
 * <p>Skipping {@code .git} from the digest fixes that symptom and introduces a
 * data-loss defect, because a commit moves bytes ONLY inside {@code .git}. An
 * agent whose edit is already synced up and who then commits it locally has a
 * working tree byte-identical to the record and history that exists in one home
 * on earth; excluded from the digest that unit reads UNMODIFIED, and the next
 * reconcile or teardown takes the commits with it.
 *
 * <h2>What these cases pin down</h2>
 *
 * <p>Three cases, and the middle two are the halves the fix must keep apart
 * (plus a fourth, on the record-schema edge issue #46 opens next door):
 *
 * <ol>
 *   <li>git's own bookkeeping (here a {@code gc}, which definitely rewrites
 *       {@code .git}) must NOT hold the unit back — each case first asserts the
 *       churn really happened, so a git that had quietly done nothing would fail
 *       the test rather than pass it vacuously;</li>
 *   <li>a commit the source does not have MUST hold the unit back, and the
 *       working tree is asserted byte-identical (through the public
 *       {@code entryDigests(root, Set.of(".git"))}, which is precisely the view
 *       the naive fix would compare) so the hold-back cannot be coming from
 *       anything but the history;</li>
 *   <li>and with the source moved on — the state that turns a hold-back into a
 *       fast-forward — the commit is still readable out of the destination's
 *       object store afterwards. That last one is the assertion the naive fix
 *       fails.</li>
 * </ol>
 *
 * <p>Every assertion is on bytes or on git's answer about a specific object, not
 * on a status: a reconcile that reports {@code held-back} and replaces
 * {@code .git} anyway, and one that reports {@code updated} and keeps the
 * commits, are indistinguishable from a status alone and only one of them has
 * lost anything.
 */
public final class HomeSyncGitUnitTest {

    private static final String UNIT = "git-sourced-skill";

    /** A file present but uncommitted in the source, so the child can commit it. */
    private static final String UNCOMMITTED = "references/agent-notes.md";

    public static int run() throws Exception {
        if (!GitOps.isAvailable()) {
            System.out.println("== HomeSyncGitUnitTest — SKIPPED, git is not on PATH");
            return 0;
        }
        Tests.Suite suite = Tests.suite("HomeSyncGitUnitTest");

        suite.test("git's own bookkeeping inside a unit does not hold the unit back", () -> {
            Homes homes = Homes.create("churn");
            assertEquals(SyncStatus.NEW, only(sync(homes)).status(), "the first pass copies it");
            assertTrue(GitOps.isGitRepo(homes.destUnit()),
                    "the copied unit really is a git repository — a copy that dropped .git "
                            + "would make everything below vacuous");

            String head = head(homes.destUnit());
            Map<String, String> worktreeBefore = worktree(homes.destUnit());
            String wholeTreeBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());

            // Bookkeeping only: gc rewrites .git and touches nothing else.
            git(homes.destUnit(), "gc", "--quiet", "--prune=now");

            assertFalse(wholeTreeBefore.equals(ChildHomeMaterializer.treeDigest(homes.destUnit())),
                    "the gc really did move the whole-tree digest — this is the false positive "
                            + "the old rule reported, and if it did not move there is nothing here "
                            + "to test");
            assertEquals(worktreeBefore, worktree(homes.destUnit()),
                    "and it changed nothing outside .git");
            assertEquals(head, head(homes.destUnit()), "HEAD is where it was");

            UnitSync outcome = only(sync(homes));
            assertFalse(outcome.status() == SyncStatus.HELD_BACK,
                    "a unit whose .git churned is not a unit that was edited, but the reconcile "
                            + "reported " + outcome.status() + ": " + outcome.detail());
            // unresolved() is exactly the set `home close-out` refuses on, so
            // this is the difference between a worktree that can be torn down
            // and one that never can again.
            assertFalse(outcome.unresolved(),
                    "and it is not an outcome a teardown has to refuse on, which is what made "
                            + "the false positive permanent: " + outcome.status() + " — "
                            + outcome.detail());
            assertEquals(head, head(homes.destUnit()),
                    "and nothing rewound the destination's history to clear it");
            assertEquals(worktreeBefore, worktree(homes.destUnit()),
                    "and the working tree is untouched either way");
        });

        suite.test("a commit the source does not have holds the unit back", () -> {
            Homes homes = Homes.create("commit");
            sync(homes);
            String materializedAt = head(homes.destUnit());
            Map<String, String> worktreeBefore = worktree(homes.destUnit());

            // The dangerous shape: the agent commits work that is ALREADY in the
            // worktree the record describes, so the commit moves bytes only
            // inside .git.
            git(homes.destUnit(), "add", "-A");
            commit(homes.destUnit(), "agent: commit work already in the tree");
            String agentCommit = head(homes.destUnit());

            assertFalse(materializedAt.equals(agentCommit), "the commit really was made");
            assertEquals(worktreeBefore, worktree(homes.destUnit()),
                    "and it changed no working-tree file — which is exactly why a digest that "
                            + "skipped .git would call this unit unmodified");

            UnitSync outcome = only(sync(homes));
            assertEquals(SyncStatus.HELD_BACK, outcome.status(),
                    "committed-but-unsent work is work: " + outcome.detail());
            assertFalse(HomeCloseOut.inspect(homes.dest(), homes.source()).safe(),
                    "and the teardown gate refuses: that commit exists in one home only");
            assertEquals(agentCommit, head(homes.destUnit()), "the commit is still HEAD");
            assertEquals(fixtureBody(), showFile(homes.destUnit(), agentCommit, UNCOMMITTED),
                    "and its content is still readable out of the object store");
        });

        suite.test("a reconcile keeps commits made in a unit whose working tree it did not change",
                () -> {
                    // THE ASSERTION THE NAIVE FIX FAILS. Everything above holds
                    // with the source standing still; a fast-forward only becomes
                    // available once the source has moved, and that is the pass
                    // that would replace the destination's .git — HEAD, objects
                    // and all — with the source's.
                    Homes homes = Homes.create("survive");
                    sync(homes);
                    String materializedAt = head(homes.destUnit());

                    git(homes.destUnit(), "add", "-A");
                    commit(homes.destUnit(), "agent: work that exists only as a commit");
                    String agentCommit = head(homes.destUnit());
                    Map<String, String> worktreeBefore = worktree(homes.destUnit());
                    assertEquals(worktreeBefore, worktree(homes.destUnit()),
                            "the destination's working tree is what was materialized");

                    // The source moves on, so this pass has something to offer
                    // and a fast-forward is on the table.
                    Files.writeString(homes.sourceUnit().resolve("upstream.md"), "upstream v2\n");
                    git(homes.sourceUnit(), "add", "-A");
                    commit(homes.sourceUnit(), "upstream: a later commit");

                    UnitSync outcome = only(sync(homes));

                    assertEquals(agentCommit, head(homes.destUnit()),
                            "the agent's commit is STILL the destination's HEAD — a pass that "
                                    + "read this unit as unmodified would have replaced .git with "
                                    + "the source's and taken the commit with it");
                    assertEquals(fixtureBody(), showFile(homes.destUnit(), agentCommit, UNCOMMITTED),
                            "and the commit's content is still in the destination's object store");
                    assertTrue(objectExists(homes.destUnit(), agentCommit),
                            "the commit object itself is still there");
                    assertFalse(materializedAt.equals(head(homes.destUnit())),
                            "the destination was not rewound to what was materialized either");
                    assertEquals(SyncStatus.HELD_BACK, outcome.status(),
                            "and it is reported as held back rather than silently skipped: "
                                    + outcome.detail());
                    assertFalse(Files.exists(homes.destUnit().resolve("upstream.md")),
                            "nothing from the source was written into a held-back unit");
                });

        suite.test("an uncommitted edit in a git-backed unit is still an edit", () -> {
            // The other conjunct. Routing a git-backed unit "through git" and
            // stopping there would let a plain uncommitted edit through: HEAD has
            // not moved, and `git status` is deliberately NOT consulted here
            // because a unit that does not gitignore its build/ would come back
            // dirty and put issue #41 straight back. So the worktree half is the
            // digest's, and it has to be load-bearing on its own.
            Homes homes = Homes.create("dirty");
            sync(homes);
            String head = head(homes.destUnit());

            Files.writeString(homes.destUnit().resolve("SKILL.md"),
                    "---\nname: " + UNIT + "\ndescription: agent edit\n---\nAGENT WORK\n");
            Files.writeString(homes.sourceUnit().resolve("upstream.md"), "upstream moved\n");
            String destDigestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());

            UnitSync outcome = only(sync(homes));

            assertEquals(SyncStatus.HELD_BACK, outcome.status(),
                    "an uncommitted edit holds the unit back: " + outcome.detail());
            assertContains(Files.readString(homes.destUnit().resolve("SKILL.md")), "AGENT WORK",
                    "and the edit is still exactly where it was");
            assertEquals(destDigestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                    "not one byte of the destination unit changed");
            assertEquals(head, head(homes.destUnit()), "HEAD did not move, so git alone could "
                    + "not have told this apart from an untouched unit");
            assertFalse(Files.exists(homes.destUnit().resolve("upstream.md")),
                    "and nothing from the source was written over it");
        });

        suite.test("a record this build cannot read still keeps a checkout a checkout", () -> {
            // Issue #46's safety edge. Distrusting a stale record's DIGESTS is
            // safe — it costs a fast-forward. Distrusting its MODE is not:
            // `mode` is the one field whose whole job is to stop a deletion, and
            // a project-wide COPY default that won over a recorded CHECKOUT
            // would demote a tree holding unpushed commits to an ordinary copy,
            // after which a later refresh may overwrite it. The demotion is the
            // damage, so the assertion is on the record's own bytes.
            Homes homes = Homes.create("staleco");
            Path child = homes.dest().skillDir(UNIT);
            Files.createDirectories(child.getParent());
            dev.skillmanager.shared.util.Fs.copyRecursive(homes.sourceUnit(), child);
            assertTrue(GitOps.isGitRepo(child), "the child unit is a checkout");
            assertEquals(ChildHomeMaterializer.treeDigest(homes.sourceUnit()),
                    ChildHomeMaterializer.treeDigest(child),
                    "the child tree is byte-identical to the store's — which is what makes an "
                            + "'adopt this identical copy' demotion reachable at all, and without "
                            + "it this case proves nothing");

            Path record = new ChildHomeMaterializer(homes.source(), homes.dest())
                    .recordFile(UNIT, dev.skillmanager.model.UnitKind.SKILL);
            Files.createDirectories(record.getParent());
            Files.writeString(record, """
                    {
                      "schemaVersion" : 1,
                      "unitName" : "%s",
                      "unitKind" : "SKILL",
                      "mode" : "CHECKOUT",
                      "source" : "%s",
                      "sourceRevision" : "%s",
                      "materializedAt" : "2026-01-01T00:00:00Z"
                    }
                    """.formatted(UNIT, homes.sourceUnit(), head(child)));

            new ChildHomeMaterializer(homes.source(), homes.dest())
                    .materializeUnit(UNIT, dev.skillmanager.model.UnitKind.SKILL,
                            dev.skillmanager.bindings.MaterializationMode.COPY);

            String after = Files.readString(record);
            assertContains(after, "\"mode\" : \"CHECKOUT\"",
                    "the unit is still recorded as a checkout: " + after);
            assertTrue(GitOps.isGitRepo(child), "and it is still a git checkout on disk");
            assertContains(after, "\"schemaVersion\" : 2",
                    "and the record was re-baselined to the current schema rather than left "
                            + "unreadable forever");
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- helpers

    /**
     * A source home whose unit is a real git repository with one commit and one
     * uncommitted file, plus an empty destination home.
     *
     * <p>The uncommitted file is the whole point: it gives the destination
     * something to commit that is already present in the tree the record
     * describes, which is the only way a commit can move bytes exclusively
     * inside {@code .git}.
     */
    private record Homes(SkillStore source, SkillStore dest) {

        static Homes create(String label) throws Exception {
            Path root = Files.createTempDirectory("home-sync-git-" + label + "-");
            SkillStore source = store(root.resolve("source"));
            SkillStore dest = store(root.resolve("dest"));
            UnitFixtures.scaffoldSkill(source.skillsDir(), UNIT, DepSpec.empty());
            Path unit = source.skillDir(UNIT);
            git(unit, "init", "-b", "main", "--quiet");
            git(unit, "add", "-A");
            commit(unit, "upstream: initial");
            Path notes = unit.resolve(UNCOMMITTED);
            Files.createDirectories(notes.getParent());
            Files.writeString(notes, fixtureBody());
            return new Homes(source, dest);
        }

        Path sourceUnit() { return source.skillDir(UNIT); }

        Path destUnit() { return dest.skillDir(UNIT); }
    }

    private static String fixtureBody() {
        return "work that only a commit will carry\n";
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static HomeSync.Report sync(Homes homes) throws IOException {
        return HomeSync.run(homes.source(), homes.dest(), new HomeSync.Options(false, false));
    }

    private static UnitSync only(HomeSync.Report report) {
        return report.units().stream()
                .filter(unit -> unit.unitName().equals(UNIT))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome reported for " + UNIT));
    }

    /**
     * The unit's per-file digests with {@code .git} excluded — deliberately the
     * public {@code entryDigests} surface, because this is exactly the view the
     * naive "skip .git" fix would compare. Asserting it is unchanged is what
     * makes "the commit moved nothing outside .git" a measured fact rather than
     * an assumption about what git does.
     */
    private static Map<String, String> worktree(Path unit) throws IOException {
        return ChildHomeMaterializer.entryDigests(unit, Set.of(".git"));
    }

    private static String head(Path unit) {
        String head = GitOps.headHash(unit);
        if (head == null) throw new AssertionError("no HEAD in " + unit);
        return head;
    }

    /** {@code git show <rev>:<path>} — reads a file out of the object store. */
    private static String showFile(Path unit, String rev, String rel) {
        Result r = run(unit, List.of("git", "show", rev + ":" + rel));
        return r.exit == 0 ? r.out : "<not in the object store: rc=" + r.exit + " " + r.out + ">";
    }

    private static boolean objectExists(Path unit, String rev) {
        return run(unit, List.of("git", "cat-file", "-e", rev + "^{commit}")).exit == 0;
    }

    // -------------------------------------------------------------- git glue

    private static void git(Path dir, String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        Result r = run(dir, argv);
        if (r.exit != 0) {
            throw new IOException("fixture git " + String.join(" ", args) + " failed in " + dir
                    + " (rc=" + r.exit + "): " + r.out);
        }
    }

    /** Commit with an explicit identity: a CI runner has no global git user. */
    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.email=fixture@localhost", "-c", "user.name=fixture",
                "commit", "--quiet", "-m", message);
    }

    private record Result(int exit, String out) {}

    private static Result run(Path workdir, List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (workdir != null) pb.directory(workdir.toFile());
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return new Result(p.waitFor(), out.toString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
