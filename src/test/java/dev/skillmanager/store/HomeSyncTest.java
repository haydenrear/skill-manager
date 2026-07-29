package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.policy.FrozenHomeException;
import dev.skillmanager.policy.HomePolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Home-to-home reconciliation: {@code home sync} and the {@code home close-out}
 * gate a worktree teardown runs before it deletes a home.
 *
 * <p>The regression these exist for is a silent one. A ticket agent improves a
 * skill inside its worktree home, the worktree is removed, and nothing anywhere
 * says the improvement is gone — the teardown succeeded. So the assertions here
 * are almost all about <em>bytes that must still be there</em> rather than
 * about exit codes: a command that refuses for the wrong reason and a command
 * that refuses for the right one are indistinguishable from a status code, and
 * only one of them has actually kept anything.
 */
public final class HomeSyncTest {

    private static final String UNIT = "sync-skill";

    /**
     * One path per shape of re-derivable output the reconcile must not own: a
     * tool-private cache directory nested well inside the unit (which is where
     * #41 actually found it), a compiled-python file whose name and not whose
     * directory is the signal, and a build-output root holding a produced
     * artifact — a jar of exactly this shape was carried worktree → project by
     * a merge as though it were somebody's work.
     */
    private static final List<String> BUILD_OUTPUT = List.of(
            "project_sdk_sources/build-logic/.gradle/8.5/executionHistory/executionHistory.bin",
            "scripts/__pycache__/discover.cpython-312.pyc",
            "build/libs/validation-graph-build-logic-0.1.0.jar");

    public static int run() throws Exception {
        return Tests.suite("HomeSyncTest")

                .test("a clean fast-forward copies the source unit into the destination", () -> {
                    Homes homes = Homes.create("ff");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    HomeSync.Report first = sync(homes, false, false);
                    assertEquals(SyncStatus.NEW, only(first).status(), "first pass creates the unit");
                    assertEquals("SOURCE V1\n", read(homes.destUnit().resolve("SKILL.md")),
                            "destination gets the source content");

                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");
                    write(homes.sourceUnit().resolve("added.md"), "new upstream file\n");
                    HomeSync.Report second = sync(homes, false, false);

                    UnitSync outcome = only(second);
                    assertEquals(SyncStatus.UPDATED, outcome.status(), "second pass is a fast-forward");
                    assertEquals("SOURCE V2\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the destination is refreshed");
                    assertTrue(Files.isRegularFile(homes.destUnit().resolve("added.md")),
                            "a file added upstream arrives");
                    assertTrue(outcome.files().contains("SKILL.md"), "the report names the changed file");

                    // Even a pure fast-forward reports every unit it saw.
                    assertEquals(1, second.units().size(), "the report covers every unit");
                    assertEquals(SyncStatus.UNCHANGED, only(sync(homes, false, false)).status(),
                            "a repeat pass is unchanged");
                })

                .test("a locally modified destination unit is held back with its bytes intact", () -> {
                    Homes homes = Homes.create("hold");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);

                    write(homes.destUnit().resolve("SKILL.md"), "AGENT WORK IN PROGRESS\n");
                    write(homes.destUnit().resolve("agent-notes.md"), "notes only the agent has\n");
                    String destDigestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");

                    HomeSync.Report report = sync(homes, false, false);
                    UnitSync outcome = only(report);

                    // Bytes first, deliberately. A hold-back that reports the
                    // right status and overwrites anyway, and one that reports
                    // the wrong status and keeps the bytes, are very different
                    // failures; only the first one loses work, so it is the one
                    // this test has to notice before anything else.
                    assertEquals("AGENT WORK IN PROGRESS\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the agent's edit is still exactly where it was");
                    assertEquals("notes only the agent has\n",
                            read(homes.destUnit().resolve("agent-notes.md")),
                            "a file only the agent added is still there");
                    assertEquals(destDigestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "not one byte of the destination unit changed");
                    assertEquals(SyncStatus.HELD_BACK, outcome.status(), "the edited unit is held back");
                    assertFalse(report.clean(), "a held-back unit makes the report not clean");
                })

                .test("--merge folds disjoint edits from both homes together", () -> {
                    Homes homes = Homes.create("merge");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED V1\n");
                    write(homes.sourceUnit().resolve("upstream.md"), "upstream v1\n");
                    write(homes.sourceUnit().resolve("local.md"), "local v1\n");
                    sync(homes, false, false);

                    // Each side moves a different file.
                    write(homes.destUnit().resolve("local.md"), "LOCAL AGENT EDIT\n");
                    write(homes.destUnit().resolve("agent-only.md"), "agent-only file\n");
                    write(homes.sourceUnit().resolve("upstream.md"), "UPSTREAM EDIT\n");
                    write(homes.sourceUnit().resolve("source-only.md"), "source-only file\n");

                    UnitSync outcome = only(sync(homes, true, false));

                    assertEquals(SyncStatus.MERGED, outcome.status(), "disjoint edits merge");
                    assertEquals("LOCAL AGENT EDIT\n", read(homes.destUnit().resolve("local.md")),
                            "the local edit survives the merge");
                    assertEquals("agent-only file\n", read(homes.destUnit().resolve("agent-only.md")),
                            "a file only the destination had survives");
                    assertEquals("UPSTREAM EDIT\n", read(homes.destUnit().resolve("upstream.md")),
                            "the source edit is folded in");
                    assertEquals("source-only file\n", read(homes.destUnit().resolve("source-only.md")),
                            "a file only the source had arrives");
                    assertEquals("SHARED V1\n", read(homes.destUnit().resolve("SKILL.md")),
                            "a file neither side touched is untouched");

                    // A merge result is local work, not a pristine copy: the next
                    // source move must merge again, never overwrite.
                    assertEquals(SyncStatus.UNCHANGED, only(sync(homes, true, false)).status(),
                            "re-running the merge is idempotent");
                    write(homes.sourceUnit().resolve("upstream.md"), "UPSTREAM EDIT 2\n");
                    assertEquals(SyncStatus.HELD_BACK, only(sync(homes, false, false)).status(),
                            "a merge result is not overwritten as if it were a pristine copy");
                    assertEquals("LOCAL AGENT EDIT\n", read(homes.destUnit().resolve("local.md")),
                            "the merged-in local work is still there");
                })

                .test("a file changed on both sides is reported as a conflict and nothing is written", () -> {
                    Homes homes = Homes.create("conflict");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED V1\n");
                    write(homes.sourceUnit().resolve("both.md"), "base\n");
                    sync(homes, false, false);

                    write(homes.destUnit().resolve("both.md"), "DESTINATION VERSION\n");
                    write(homes.sourceUnit().resolve("both.md"), "SOURCE VERSION\n");
                    String destDigestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());

                    HomeSync.Report report = sync(homes, true, false);
                    UnitSync outcome = only(report);

                    assertEquals("DESTINATION VERSION\n", read(homes.destUnit().resolve("both.md")),
                            "the destination's version of the conflicted file is untouched");
                    assertEquals(destDigestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "a conflict writes nothing at all into the destination unit");
                    assertEquals(SyncStatus.CONFLICTED, outcome.status(), "both sides moved one file");
                    assertEquals(List.of("both.md"), outcome.conflicts(), "the conflict names the file");
                    assertEquals(1, report.conflicted().size(), "the conflict is reported");
                })

                .test("--dry-run reports the whole reconciliation and writes nothing", () -> {
                    Homes homes = Homes.create("dry");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");
                    UnitFixtures.scaffoldSkill(homes.source().skillsDir(), "extra-skill", DepSpec.empty());

                    String homeDigestBefore = homeDigest(homes.dest().root());
                    HomeSync.Report planned = sync(homes, false, true);
                    String homeDigestAfter = homeDigest(homes.dest().root());

                    assertEquals(homeDigestBefore, homeDigestAfter,
                            "a dry run leaves the destination home byte-identical");
                    assertEquals(2, planned.units().size(), "the dry run reports every unit");
                    assertEquals(SyncStatus.UPDATED, planned.units().stream()
                                    .filter(u -> u.unitName().equals(UNIT)).findFirst().orElseThrow()
                                    .status(),
                            "the dry run says what the real run would do to the existing unit");
                    assertEquals(SyncStatus.NEW, planned.units().stream()
                                    .filter(u -> u.unitName().equals("extra-skill")).findFirst()
                                    .orElseThrow().status(),
                            "the dry run says the new unit would be created");
                    assertFalse(Files.exists(homes.dest().skillDir("extra-skill")),
                            "the dry run did not create the new unit");
                    assertEquals("SOURCE V1\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the dry run did not refresh the existing unit");

                    // And the real run then does exactly what was reported.
                    HomeSync.Report real = sync(homes, false, false);
                    assertEquals("SOURCE V2\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the real run does what the dry run described");
                    assertEquals(2, real.moved().size(), "both reported writes happened");

                    // A destination that does not exist yet is the case where
                    // "writes nothing" is easiest to get wrong: the store's own
                    // init() would lay out the whole home before anything was
                    // reported. Taking the home lock was the OTHER way, and it
                    // was live until issue #42 — a dry run created
                    // .materialization/ and a zero-byte .home.lock inside it,
                    // measured in the operator's own read-only home. NOTHING is
                    // tolerated here now, because the lock file's own absence is
                    // what proves no peer holds the lock.
                    Path fresh = Files.createTempDirectory("home-sync-dry-fresh-").resolve("home");
                    HomeSync.Report onFresh = HomeSync.run(homes.source(), new SkillStore(fresh),
                            new HomeSync.Options(false, true));
                    assertEquals(2, onFresh.units().size(), "a fresh destination is still reported on");
                    assertFalse(Files.exists(fresh),
                            "a dry run against a destination that does not exist does not create it");
                    assertFalse(Files.exists(HomeLock.file(fresh)),
                            "and takes the home lock without creating the lock file");

                    // The same claim where the destination directory DOES exist,
                    // so that "nothing was created" is measured rather than
                    // inferred from the parent's absence.
                    Path empty = Files.createTempDirectory("home-sync-dry-empty-");
                    HomeSync.Report onEmpty = HomeSync.run(homes.source(), new SkillStore(empty),
                            new HomeSync.Options(false, true));
                    assertEquals(2, onEmpty.units().size(), "an empty destination is reported on too");
                    assertEquals(List.of(), listing(empty),
                            "a dry run creates nothing at all in the destination home");

                    // And a real run into the same home still takes the lock the
                    // ordinary way, so the file that was removed from the dry
                    // path is not missing from the write path.
                    HomeSync.run(homes.source(), new SkillStore(empty),
                            new HomeSync.Options(false, false));
                    assertTrue(Files.isRegularFile(HomeLock.file(empty)),
                            "a real run still creates and holds the lock file");
                })

                // ------------------------- build output is not unit content

                .test("build output written inside a unit does not make the unit differ", () -> {
                    // Issue #41, in the smallest form that still is it. Running
                    // `discover.py` once in a ticket worktree — the thing a
                    // worktree exists to do — leaves .gradle/ and __pycache__/
                    // INSIDE a unit. Those used to move the unit's digest, so
                    // the unit came back conflicted, the remedy it printed named
                    // executionHistory.bin as a file to "resolve", running that
                    // remedy verbatim exited 1, and close-out could never be
                    // satisfied again.
                    Homes homes = Homes.create("derived");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);

                    for (String rel : BUILD_OUTPUT) {
                        write(homes.destUnit().resolve(rel), "derived " + rel + "\n");
                    }

                    UnitSync outcome = only(sync(homes, false, false));
                    assertEquals(SyncStatus.UNCHANGED, outcome.status(),
                            "a unit that was only built in is not a unit that was edited");
                    assertTrue(HomeCloseOut.inspect(homes.dest(), homes.source()).safe(),
                            "so the teardown gate clears instead of demanding a resolution "
                                    + "nobody can perform");
                    for (String rel : BUILD_OUTPUT) {
                        assertEquals("derived " + rel + "\n", read(homes.destUnit().resolve(rel)),
                                "and " + rel + " is still on disk: not owned is not deleted");
                    }
                })

                .test("a refresh keeps the build output it does not own", () -> {
                    // The other half of "not owned". swapIn replaces the
                    // destination WHOLESALE, so excluding these paths from the
                    // view without carrying them across the swap would DELETE
                    // them as a side effect of not comparing them. "Not mine to
                    // compare" and "mine to destroy" cannot both be true.
                    Homes homes = Homes.create("derivedkept");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);
                    for (String rel : BUILD_OUTPUT) {
                        write(homes.destUnit().resolve(rel), "derived " + rel + "\n");
                    }

                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");
                    UnitSync outcome = only(sync(homes, false, false));

                    assertEquals(SyncStatus.UPDATED, outcome.status(),
                            "the upstream change still arrives");
                    assertEquals("SOURCE V2\n", read(homes.destUnit().resolve("SKILL.md")),
                            "with the source's bytes");
                    for (String rel : BUILD_OUTPUT) {
                        assertEquals("derived " + rel + "\n", read(homes.destUnit().resolve(rel)),
                                rel + " survived a refresh that rewrote the unit around it");
                    }
                })

                .test("a .git directory inside a unit is content, never skipped as build output", () -> {
                    // THE GUARD ON THE FIX ABOVE, and issue #29 is why it is
                    // spelled out as a test rather than as a comment. A .git
                    // directory churns on every read-only command, so every
                    // instinct that skips .gradle says to skip .git too — and a
                    // unit whose agent COMMITTED work holds those commits in
                    // .git and nowhere else. Skipped, that unit reads as
                    // unmodified (its worktree files really are unchanged), the
                    // gate clears, the worktree is removed, and the commits stop
                    // existing.
                    Homes homes = Homes.create("gitcontent");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);

                    write(homes.destUnit().resolve(".git/objects/ab/cdef"), "a commit object\n");
                    write(homes.destUnit().resolve(".git/HEAD"), "ref: refs/heads/ticket\n");

                    UnitSync outcome = only(sync(homes, false, false));
                    assertEquals(SyncStatus.HELD_BACK, outcome.status(),
                            "a unit carrying git history the source has never had is held back");
                    assertFalse(HomeCloseOut.inspect(homes.dest(), homes.source()).safe(),
                            "and the teardown gate REFUSES: those commits exist in one home only");
                    assertEquals("a commit object\n",
                            read(homes.destUnit().resolve(".git/objects/ab/cdef")),
                            "nothing touched the object it refused over");
                })

                .test("a sync that fails part way leaves the destination unit exactly as it was", () -> {
                    Homes homes = Homes.create("atomic");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    write(homes.sourceUnit().resolve("keep.md"), "keep me\n");
                    sync(homes, false, false);

                    String contentBefore = read(homes.destUnit().resolve("SKILL.md"));
                    String keepBefore = read(homes.destUnit().resolve("keep.md"));
                    String digestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());

                    // Force a refresh, then break the staging area the write has
                    // to build before it may touch the live unit.
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");
                    Path records = homes.dest().root().resolve(ChildHomeMaterializer.RECORDS_DIR);
                    Set<PosixFilePermission> original = Files.getPosixFilePermissions(records);
                    Files.setPosixFilePermissions(records, EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                    // Running as root would defeat the injection and make every
                    // assertion below vacuous.
                    assertFalse(Files.isWritable(records),
                            "the failure injection took effect (not running as root)");

                    boolean threw = false;
                    try {
                        sync(homes, false, false);
                    } catch (IOException expected) {
                        threw = true;
                    } finally {
                        Files.setPosixFilePermissions(records, original);
                    }

                    assertTrue(threw, "the interrupted sync failed rather than half-succeeding");
                    assertTrue(Files.isDirectory(homes.destUnit(), LinkOption.NOFOLLOW_LINKS),
                            "the destination unit still exists");
                    assertEquals(contentBefore, read(homes.destUnit().resolve("SKILL.md")),
                            "the destination unit's bytes are exactly what they were");
                    assertEquals(keepBefore, read(homes.destUnit().resolve("keep.md")),
                            "no file in the destination unit was removed");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "the destination unit tree is unchanged");
                    assertFalse(Files.exists(homes.destUnit().resolveSibling(
                                    homes.destUnit().getFileName() + ".tmp")),
                            "no half-written tree was left beside the unit");

                    // And the failure was recoverable, not a wedged home.
                    assertEquals(SyncStatus.UPDATED, only(sync(homes, false, false)).status(),
                            "the same sync succeeds once the injected failure is removed");
                    assertEquals("SOURCE V2\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the recovered sync produced the source content");
                })

                .test("two concurrent syncs into one home do not interleave", () -> {
                    Path root = Files.createTempDirectory("home-sync-race-");
                    SkillStore left = store(root.resolve("left"));
                    SkillStore right = store(root.resolve("right"));
                    SkillStore dest = store(root.resolve("dest"));
                    UnitFixtures.scaffoldSkill(left.skillsDir(), UNIT, DepSpec.empty());
                    UnitFixtures.scaffoldSkill(right.skillsDir(), UNIT, DepSpec.empty());
                    // Enough files that a copy takes long enough for two
                    // unsynchronised passes to land inside each other.
                    for (int i = 0; i < 40; i++) {
                        write(left.skillDir(UNIT).resolve("f" + i + ".md"), "LEFT " + i + "\n".repeat(64));
                        write(right.skillDir(UNIT).resolve("f" + i + ".md"), "RIGHT " + i + "\n".repeat(64));
                    }
                    String leftDigest = ChildHomeMaterializer.treeDigest(left.skillDir(UNIT));
                    String rightDigest = ChildHomeMaterializer.treeDigest(right.skillDir(UNIT));
                    HomeSync.run(left, dest, new HomeSync.Options(false, false));

                    List<Throwable> failures = new CopyOnWriteArrayList<>();
                    CountDownLatch start = new CountDownLatch(1);
                    List<Thread> threads = new ArrayList<>();
                    for (SkillStore source : List.of(left, right, left, right)) {
                        Thread thread = new Thread(() -> {
                            try {
                                start.await();
                                for (int pass = 0; pass < 3; pass++) {
                                    HomeSync.run(source, dest, new HomeSync.Options(false, false));
                                }
                            } catch (Throwable t) {
                                failures.add(t);
                            }
                        });
                        threads.add(thread);
                        thread.start();
                    }
                    start.countDown();
                    for (Thread thread : threads) thread.join();

                    assertTrue(failures.isEmpty(), "no concurrent sync failed: " + failures);
                    String finalDigest = ChildHomeMaterializer.treeDigest(dest.skillDir(UNIT));
                    assertTrue(finalDigest.equals(leftDigest) || finalDigest.equals(rightDigest),
                            "the destination unit is exactly one source's tree, not a mix of both");
                    // The corruption an interleave produces is a record that
                    // describes a tree which never existed on disk.
                    ChildHomeMaterializer.MaterializationRecord record =
                            new ChildHomeMaterializer(left, dest).readRecord(UNIT, UnitKind.SKILL)
                                    .orElseThrow();
                    assertEquals(finalDigest, record.contentDigest(),
                            "the materialization record describes the tree that is actually there");
                    assertFalse(Files.exists(dest.root().resolve(ChildHomeMaterializer.RECORDS_DIR)
                                    .resolve("tmp")),
                            "no staging leftovers survive a contended run");
                })

                .test("a unit only the source has arrives; one only the destination has is reported", () -> {
                    Homes homes = Homes.create("asym");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);
                    UnitFixtures.scaffoldSkill(homes.dest().skillsDir(), "dest-only", DepSpec.empty());
                    write(homes.dest().skillDir("dest-only").resolve("precious.md"), "only here\n");

                    HomeSync.Report report = sync(homes, false, false);

                    UnitSync destOnly = report.units().stream()
                            .filter(u -> u.unitName().equals("dest-only")).findFirst().orElseThrow();
                    assertEquals(SyncStatus.REMOVED_UPSTREAM, destOnly.status(),
                            "a unit the source does not have is reported as removed upstream");
                    assertTrue(Files.isRegularFile(
                                    homes.dest().skillDir("dest-only").resolve("precious.md")),
                            "a unit the source does not have is never deleted");
                })

                .test("a frozen destination refuses and a frozen source is only read", () -> {
                    Homes homes = Homes.create("frozen");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    HomePolicy.write(homes.dest(), HomePolicy.FROZEN);
                    boolean refused = false;
                    try {
                        sync(homes, false, false);
                    } catch (FrozenHomeException expected) {
                        refused = true;
                    }
                    assertTrue(refused, "a frozen destination refuses");
                    assertFalse(Files.exists(homes.destUnit()), "and nothing was written into it");

                    boolean dryRefused = false;
                    try {
                        sync(homes, false, true);
                    } catch (FrozenHomeException expected) {
                        dryRefused = true;
                    }
                    assertTrue(dryRefused, "a frozen destination refuses a dry run too");

                    HomePolicy.write(homes.dest(), HomePolicy.LIVE);
                    HomePolicy.write(homes.source(), HomePolicy.FROZEN);
                    assertEquals(SyncStatus.NEW, only(sync(homes, false, false)).status(),
                            "a frozen source is fine — it is only read");
                    assertEquals(HomePolicy.FROZEN, HomePolicy.load(homes.source()),
                            "the source home is still frozen afterwards");
                })

                .test("close-out refuses while the worktree holds work and passes once it is synced", () -> {
                    Homes homes = Homes.create("closeout");
                    write(homes.sourceUnit().resolve("SKILL.md"), "PROJECT V1\n");
                    // source = the worktree home, dest = the project home.
                    sync(homes, false, false);
                    assertEquals(0, HomeCloseOut.inspect(homes.source(), homes.dest()).exitCode(),
                            "a worktree with nothing new closes out cleanly");

                    write(homes.sourceUnit().resolve("SKILL.md"), "AGENT IMPROVED THE SKILL\n");
                    HomeCloseOut.Verdict blocked =
                            HomeCloseOut.inspect(homes.source(), homes.dest());
                    assertFalse(blocked.safe(), "an unsynced agent edit blocks the teardown");
                    assertEquals(1, blocked.blockers().size(), "exactly the unit at risk is named");
                    assertEquals("skill:" + UNIT, blocked.blockers().get(0).label(),
                            "the blocker names the unit");
                    assertTrue(blocked.blockers().get(0).remedy().contains("home sync"),
                            "the blocker says what to run");
                    assertEquals("PROJECT V1\n", read(homes.destUnit().resolve("SKILL.md")),
                            "close-out writes nothing");

                    // Repeatable: the same answer twice.
                    assertFalse(HomeCloseOut.inspect(homes.source(), homes.dest()).safe(),
                            "close-out is repeatable and gives the same verdict");

                    sync(homes, false, false);
                    assertTrue(HomeCloseOut.inspect(homes.source(), homes.dest()).safe(),
                            "once the work has been synced the teardown is allowed");
                    assertEquals("AGENT IMPROVED THE SKILL\n",
                            read(homes.destUnit().resolve("SKILL.md")),
                            "the agent's work reached the project home");
                })

                .test("close-out refuses a path that is not a home rather than clearing it", () -> {
                    // The gate FAILED OPEN. `--home <the worktree directory>`
                    // instead of `<worktree>/.skill-manager`, or a `--home`
                    // that did not exist at all, exited 0 with "safe": true and
                    // "blockers": [] — naming the directory that held the only
                    // copy of the agent's edit. The arithmetic: a non-home
                    // contributes zero units to the union, every remaining unit
                    // becomes REMOVED_UPSTREAM, and REMOVED_UPSTREAM maps to no
                    // blocker. That mistake is maximally likely, because
                    // <worktree> is exactly what `git worktree remove` takes.
                    Homes homes = Homes.create("nothome");
                    write(homes.sourceUnit().resolve("SKILL.md"), "AGENT WORK NOBODY ELSE HAS\n");

                    // 1. The worktree DIRECTORY rather than the home inside it.
                    //    Built to look like a real worktree: a checkout with
                    //    files in it, and the home one level down.
                    Path worktreeDir = Files.createTempDirectory("home-sync-worktree-");
                    write(worktreeDir.resolve("README.md"), "a checkout, not a home\n");
                    SkillStore inside = store(worktreeDir.resolve(".skill-manager"));
                    UnitFixtures.scaffoldSkill(inside.skillsDir(), UNIT, DepSpec.empty());
                    write(inside.skillDir(UNIT).resolve("SKILL.md"), "THE ONLY COPY\n");

                    assertThrowsNotAHome(() -> HomeCloseOut.inspect(
                                    new SkillStore(worktreeDir), homes.dest()),
                            "the worktree directory is not the home inside it");
                    // The message has to point at the fix, not merely refuse.
                    assertTrue(notAHomeMessage(() -> HomeCloseOut.inspect(
                                    new SkillStore(worktreeDir), homes.dest()))
                                    .contains(worktreeDir.resolve(".skill-manager").toString()),
                            "the refusal names the home the operator meant");

                    // 2. A path that does not exist at all.
                    Path missing = worktreeDir.resolve("no/such/home");
                    assertThrowsNotAHome(() -> HomeCloseOut.inspect(
                                    new SkillStore(missing), homes.dest()),
                            "a --home that does not exist is not a home");

                    // 3. A regular file wearing the name of a home.
                    Path file = worktreeDir.resolve("not-a-directory");
                    write(file, "definitely not a home\n");
                    assertThrowsNotAHome(() -> HomeCloseOut.inspect(
                                    new SkillStore(file), homes.dest()),
                            "a regular file is not a home");

                    // 4. The other end too: --into.
                    assertThrowsNotAHome(() -> HomeCloseOut.inspect(
                                    homes.source(), new SkillStore(worktreeDir)),
                            "an --into that is not a home is refused as well");

                    // And the same hole on the sync side, which created the
                    // destination layout and printed "✓ reconciled" with
                    // all-zero counts before this.
                    Path freshDest = worktreeDir.resolve("would-have-been-created");
                    assertThrowsNotAHome(() -> HomeSync.run(new SkillStore(missing),
                                    new SkillStore(freshDest), new HomeSync.Options(false, false)),
                            "home sync --from a non-home is refused");
                    assertFalse(Files.exists(freshDest),
                            "and the refusal happened before anything was written");

                    // The gate still works for the home it was pointed past.
                    assertFalse(HomeCloseOut.inspect(inside, homes.dest()).safe(),
                            "naming the real home gives the real answer: it holds work");
                })

                .test("a symlinked unit or kind directory is reported, never silently skipped", () -> {
                    // Measured before the fix: a home whose skills/ was a
                    // symlink reconciled as {"clean":true,"units":[]} and the
                    // destination's skills/ stayed empty; a single symlinked
                    // unit beside a normal one synced only the normal one,
                    // reported clean=true, and never named the other. Worse,
                    // the enumerator and the reconciler disagreed — reconcile
                    // applies NOFOLLOW_LINKS only to the final component, so it
                    // read THROUGH a linked skills/ and close-out reported the
                    // same unit conflicted purely because the destination
                    // contributed the name.
                    Path root = Files.createTempDirectory("home-sync-linked-");
                    Path elsewhere = root.resolve("elsewhere");
                    UnitFixtures.scaffoldSkill(elsewhere, "linked-unit", DepSpec.empty());
                    write(elsewhere.resolve("linked-unit/SKILL.md"), "LIVES SOMEWHERE ELSE\n");

                    // --- a single linked unit beside a normal one ------------
                    SkillStore source = store(root.resolve("source"));
                    SkillStore dest = store(root.resolve("dest"));
                    UnitFixtures.scaffoldSkill(source.skillsDir(), UNIT, DepSpec.empty());
                    Files.createSymbolicLink(source.skillsDir().resolve("linked-unit"),
                            elsewhere.resolve("linked-unit"));

                    HomeSync.Report report =
                            HomeSync.run(source, dest, new HomeSync.Options(false, false));
                    UnitSync linked = report.units().stream()
                            .filter(u -> u.unitName().equals("linked-unit")).findFirst()
                            .orElseThrow(() -> new AssertionError(
                                    "the linked unit was not named at all: " + report.units()));
                    assertEquals(SyncStatus.LINKED, linked.status(),
                            "a linked unit directory is reported as linked");
                    assertTrue(linked.detail().contains("symlink"),
                            "and the report says why: " + linked.detail());
                    assertFalse(report.clean(),
                            "a report that could not account for a unit is not clean");
                    assertFalse(Files.exists(dest.skillsDir().resolve("linked-unit"),
                                    LinkOption.NOFOLLOW_LINKS),
                            "nothing was written for it");
                    assertEquals(SyncStatus.NEW, report.units().stream()
                                    .filter(u -> u.unitName().equals(UNIT)).findFirst()
                                    .orElseThrow().status(),
                            "the ordinary unit beside it still reconciles");

                    // The gate must refuse rather than clear: it cannot say
                    // whether the link points inside the home about to be
                    // removed or outside it.
                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(source, dest);
                    assertFalse(verdict.safe(), "close-out blocks on a unit it cannot account for");
                    assertTrue(verdict.blockers().stream()
                                    .anyMatch(b -> b.label().equals("skill:linked-unit")),
                            "and the linked unit is one of the blockers: " + verdict.blockers());

                    // --- the whole kind directory is a link ------------------
                    SkillStore linkedHome = store(root.resolve("linked-home"));
                    dev.skillmanager.shared.util.Fs.deleteRecursive(linkedHome.skillsDir());
                    Files.createSymbolicLink(linkedHome.skillsDir(), elsewhere);
                    SkillStore target = store(root.resolve("target"));

                    HomeSync.Report viaLink =
                            HomeSync.run(linkedHome, target, new HomeSync.Options(false, false));
                    assertFalse(viaLink.units().isEmpty(),
                            "a home whose skills/ is a link does not reconcile as an empty home");
                    assertEquals(SyncStatus.LINKED, viaLink.units().stream()
                                    .filter(u -> u.unitName().equals("linked-unit")).findFirst()
                                    .orElseThrow(() -> new AssertionError(
                                            "the unit under the linked kind directory was not "
                                                    + "named: " + viaLink.units()))
                                    .status(),
                            "the unit under a linked kind directory is reported as linked");
                    assertFalse(viaLink.clean(), "and the report is not clean");
                    assertEquals(List.of(), listing(target.skillsDir()),
                            "nothing was copied through the link");
                    assertFalse(HomeCloseOut.inspect(linkedHome, target).safe(),
                            "close-out refuses to clear a home it reaches through a link");
                })

                .test("a cloned home records the baseline that lets its edits merge back", () -> {
                    Path root = Files.createTempDirectory("home-sync-clone-");
                    SkillStore project = store(root.resolve("project/.skill-manager"));
                    UnitFixtures.scaffoldSkill(project.skillsDir(), UNIT, DepSpec.empty());
                    write(project.skillDir(UNIT).resolve("SKILL.md"), "PROJECT V1\n");
                    write(project.skillDir(UNIT).resolve("shared.md"), "shared\n");

                    // Exactly what `home clone` does: copy, then write down what
                    // the copy started from while that is still knowable.
                    Path worktreeRoot = root.resolve("worktree/.skill-manager");
                    HomeCloner.cloneHome(project.root(), worktreeRoot, false);
                    SkillStore worktree = new SkillStore(worktreeRoot);
                    assertFalse(ChildHomeMaterializer.recordCloneBaselines(worktree).isEmpty(),
                            "the clone records a baseline for the units it copied");

                    // The ticket agent improves the skill inside its worktree.
                    write(worktree.skillDir(UNIT).resolve("SKILL.md"), "AGENT IMPROVED THIS\n");
                    write(worktree.skillDir(UNIT).resolve("agent-note.md"), "agent note\n");
                    // The project home moves on independently, on another file.
                    write(project.skillDir(UNIT).resolve("shared.md"), "shared, edited in project\n");

                    HomeSync.Report report = HomeSync.run(worktree, project,
                            new HomeSync.Options(true, false));
                    UnitSync outcome = only(report);

                    assertEquals(SyncStatus.MERGED, outcome.status(),
                            "the clone-time baseline makes the worktree edit mergeable");
                    assertEquals("AGENT IMPROVED THIS\n",
                            read(project.skillDir(UNIT).resolve("SKILL.md")),
                            "the agent's edit reached the project home");
                    assertEquals("agent note\n",
                            read(project.skillDir(UNIT).resolve("agent-note.md")),
                            "a file the agent added reached the project home");
                    assertEquals("shared, edited in project\n",
                            read(project.skillDir(UNIT).resolve("shared.md")),
                            "the project's own edit was not overwritten");

                    // The record the clone wrote must not name the home it came
                    // from — that is the leak `home verify` exists to catch.
                    Path record = worktreeRoot.resolve(ChildHomeMaterializer.RECORDS_DIR)
                            .resolve("skill").resolve(UNIT + ".json");
                    assertFalse(Files.readString(record).contains(project.root().toString()),
                            "the adopted baseline records no path back into the source home");
                })

                .test("a clone of a home that edited a unit in place restates the baseline", () -> {
                    // The other direction of the same question, and the one
                    // that costs usability rather than data: a record says what
                    // a home was HANDED, and editing a unit in place does not
                    // update it. `home clone` copies those records, so a
                    // worktree cloned from a project home that had improved the
                    // unit inherited a baseline describing content NEITHER home
                    // holds any more — older than their real common ancestor,
                    // which is the clone itself. Measured before the fix: a
                    // worktree whose content strictly CONTAINS the project's
                    // reported CONFLICTED and wrote nothing, and close-out then
                    // demanded a manual resolution that was not needed.
                    Path root = Files.createTempDirectory("home-sync-stale-");
                    SkillStore upstream = store(root.resolve("root"));
                    SkillStore project = store(root.resolve("project/.skill-manager"));
                    UnitFixtures.scaffoldSkill(upstream.skillsDir(), UNIT, DepSpec.empty());
                    write(upstream.skillDir(UNIT).resolve("SKILL.md"), "V1\n");
                    HomeSync.run(upstream, project, new HomeSync.Options(false, false));

                    // The project home improves the unit itself. Its record
                    // still describes V1 — correctly.
                    String projectText = "V1\nthe project home improved this first\n";
                    write(project.skillDir(UNIT).resolve("SKILL.md"), projectText);

                    Path worktreeRoot = root.resolve("worktree/.skill-manager");
                    HomeCloner.cloneHome(project.root(), worktreeRoot, false);
                    SkillStore worktree = new SkillStore(worktreeRoot);
                    List<String> restated = ChildHomeMaterializer.recordCloneBaselines(worktree)
                            .stream().map(ChildHomeMaterializer.UnitRef::label).toList();

                    // The ticket agent builds on exactly what it was given, so
                    // the worktree's content strictly contains the project's.
                    String worktreeText = projectText + "and the agent improved it further\n";
                    write(worktree.skillDir(UNIT).resolve("SKILL.md"), worktreeText);

                    UnitSync outcome = only(HomeSync.run(worktree, project,
                            new HomeSync.Options(true, false)));

                    // The consequence first: what the clone wrote down is only
                    // interesting because of what it lets the return path do.
                    //
                    // UPDATED rather than MERGED since issue #43, and it is the
                    // same clone-time baseline doing the work either way. The
                    // project home has not moved SINCE the clone, so the tree
                    // this pass is about to replace is exactly the tree the
                    // worktree is on record as having been handed — which is
                    // the wholesale-copy showing, so there is nothing for a
                    // three-way merge to decide. (The test above is the case
                    // where the project DID move afterwards; it still merges.)
                    assertEquals(SyncStatus.UPDATED, outcome.status(),
                            "the clone-time baseline settles it without a human");
                    assertEquals(worktreeText, read(project.skillDir(UNIT).resolve("SKILL.md")),
                            "and the project home ends up with the agent's bytes");
                    assertTrue(HomeCloseOut.inspect(worktree, project).safe(),
                            "so close-out lets the worktree go");
                    assertEquals(List.of("skill:" + UNIT), restated,
                            "and the clone said so: it restated the baseline of the unit whose "
                                    + "inherited record describes content it does not hold");
                })

                .test("close-out refuses a unit whose two sides conflict, and names it", () -> {
                    Homes homes = Homes.create("closeout-conflict");
                    write(homes.sourceUnit().resolve("SKILL.md"), "V1\n");
                    write(homes.sourceUnit().resolve("both.md"), "base\n");
                    sync(homes, false, false);
                    write(homes.sourceUnit().resolve("both.md"), "WORKTREE VERSION\n");
                    write(homes.destUnit().resolve("both.md"), "PROJECT VERSION\n");

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(homes.source(), homes.dest());

                    assertFalse(verdict.safe(), "an unmergeable unit blocks the teardown");
                    assertEquals(SyncStatus.CONFLICTED, verdict.blockers().get(0).unit().status(),
                            "the blocker is the conflict");
                    assertTrue(verdict.blockers().get(0).remedy().contains("both.md"),
                            "the remedy names the conflicting file");
                    assertEquals("PROJECT VERSION\n", read(homes.destUnit().resolve("both.md")),
                            "neither side was written");
                    assertEquals("WORKTREE VERSION\n", read(homes.sourceUnit().resolve("both.md")),
                            "the worktree's version is still in the worktree");
                })

                .runAll();
    }

    // ------------------------------------------------------------- helpers

    /** A source home and a destination home, each holding {@link #UNIT}. */
    private record Homes(SkillStore source, SkillStore dest) {

        static Homes create(String label) throws IOException {
            Path root = Files.createTempDirectory("home-sync-" + label + "-");
            SkillStore source = store(root.resolve("source"));
            SkillStore dest = store(root.resolve("dest"));
            UnitFixtures.scaffoldSkill(source.skillsDir(), UNIT, DepSpec.empty());
            return new Homes(source, dest);
        }

        Path sourceUnit() { return source.skillDir(UNIT); }

        Path destUnit() { return dest.skillDir(UNIT); }
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static HomeSync.Report sync(Homes homes, boolean merge, boolean dryRun)
            throws IOException {
        return HomeSync.run(homes.source(), homes.dest(), new HomeSync.Options(merge, dryRun));
    }

    private static UnitSync only(HomeSync.Report report) {
        return report.units().stream()
                .filter(unit -> unit.unitName().equals(UNIT))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome reported for " + UNIT));
    }

    /** Something that runs and may refuse. */
    @FunctionalInterface
    private interface HomeCall {
        void run() throws Exception;
    }

    /**
     * Assert that {@code call} refuses with {@link NotAHomeException}.
     *
     * <p>The exception TYPE, never merely "it threw". Before the fix these
     * calls did not throw at all — they returned a clean verdict — and after
     * it, a call that failed for some unrelated IO reason would satisfy a bare
     * "it threw" and hide the fact that the check had stopped running.
     */
    private static void assertThrowsNotAHome(HomeCall call, String why) {
        try {
            call.run();
        } catch (NotAHomeException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError(why + " — refused, but with " + other, other);
        }
        throw new AssertionError(why + " — it did NOT refuse, which is the defect");
    }

    private static String notAHomeMessage(HomeCall call) {
        try {
            call.run();
        } catch (NotAHomeException expected) {
            return expected.getMessage();
        } catch (Exception other) {
            throw new AssertionError("expected a not-a-home refusal, got " + other, other);
        }
        throw new AssertionError("expected a not-a-home refusal, nothing was thrown");
    }

    /** Digest of a whole home directory, lock file and records included. */
    private static String homeDigest(Path root) throws IOException {
        return ChildHomeMaterializer.entryDigests(root, Set.of()).toString();
    }

    /** Sorted names directly under {@code dir}; empty when it does not exist. */
    private static List<String> listing(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String read(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }
}
