package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.MaterializationRecord;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.bindings.ChildHomeMaterializer.UnitSync;
import dev.skillmanager.model.UnitKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * The parts of home-to-home reconciliation a Test Graph node cannot reach
 * cheaply: the merge algebra decided path by path, the provenance rule that
 * decides whether a destination is disposable at all, what happens when there
 * is no usable baseline, and the lock.
 *
 * <p>{@link HomeSyncTest} covers the command's shape — a fast-forward, a
 * hold-back, one disjoint merge, one conflict, a dry run, a close-out. This
 * covers the decision underneath it, one case at a time, because the four
 * cases of a three-way merge are four different ways to lose somebody's work
 * and a test that exercises them together can only tell you that the common
 * ones are right.
 *
 * <p>Every assertion here is about bytes or about a file's existence. A status
 * code says which branch the code believed it was taking; only the tree says
 * which one it actually took, and the failure this whole mechanism exists to
 * prevent is one where those two disagree silently.
 */
public final class HomeSyncMergeTest {

    private static final String UNIT = "merge-skill";

    public static int run() throws Exception {
        return Tests.suite("HomeSyncMergeTest")

                // ------------------------------------------------ the algebra

                .test("absence is a value in the merge, not a case of its own", () -> {
                    Homes homes = Homes.create("absence");
                    // The baseline both sides start from.
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED\n");
                    write(homes.sourceUnit().resolve("deleted-upstream.md"), "will go away\n");
                    write(homes.sourceUnit().resolve("deleted-locally.md"), "the agent removes this\n");
                    sync(homes, false, false);

                    // Four one-sided moves, each of which is an absence on one
                    // side: a file added upstream, one added locally, one
                    // deleted upstream, one deleted locally. If "absent" were
                    // not just another value in the comparison, each of these
                    // would need a rule and one of them would be missing it.
                    write(homes.sourceUnit().resolve("added-upstream.md"), "new from the source\n");
                    Files.delete(homes.sourceUnit().resolve("deleted-upstream.md"));
                    write(homes.destUnit().resolve("added-locally.md"), "new from the agent\n");
                    Files.delete(homes.destUnit().resolve("deleted-locally.md"));

                    UnitSync outcome = only(sync(homes, true, false));

                    assertEquals(SyncStatus.MERGED, outcome.status(), "four disjoint moves merge");
                    assertEquals("new from the source\n",
                            read(homes.destUnit().resolve("added-upstream.md")),
                            "a file added upstream arrives");
                    assertEquals("new from the agent\n",
                            read(homes.destUnit().resolve("added-locally.md")),
                            "a file added locally stays");
                    assertFalse(Files.exists(homes.destUnit().resolve("deleted-upstream.md")),
                            "a file deleted upstream is deleted here too");
                    assertFalse(Files.exists(homes.destUnit().resolve("deleted-locally.md")),
                            "a file deleted locally is not resurrected");
                    assertEquals("SHARED\n", read(homes.destUnit().resolve("SKILL.md")),
                            "a file neither side moved is untouched");
                })

                .test("a file deleted on one side and edited on the other is a conflict", () -> {
                    Homes homes = Homes.create("delete-edit");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED\n");
                    write(homes.sourceUnit().resolve("contested.md"), "base\n");
                    sync(homes, false, false);

                    // Deleting and editing are both "moving the path away from
                    // the baseline". Nothing here is entitled to decide that a
                    // deletion beats an edit or the other way round.
                    Files.delete(homes.sourceUnit().resolve("contested.md"));
                    write(homes.destUnit().resolve("contested.md"), "THE AGENT'S VERSION\n");

                    UnitSync outcome = only(sync(homes, true, false));

                    assertEquals(SyncStatus.CONFLICTED, outcome.status(),
                            "delete-versus-edit is a conflict");
                    assertEquals(List.of("contested.md"), outcome.conflicts(),
                            "the conflict names the file");
                    assertEquals("THE AGENT'S VERSION\n",
                            read(homes.destUnit().resolve("contested.md")),
                            "the file the source deleted is still here, with the agent's bytes");
                })

                .test("a conflict anywhere in a unit suppresses the paths that would have merged", () -> {
                    Homes homes = Homes.create("atomic-conflict");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED\n");
                    write(homes.sourceUnit().resolve("mergeable.md"), "base\n");
                    write(homes.sourceUnit().resolve("contested.md"), "base\n");
                    sync(homes, false, false);

                    // One path only the source moved — cleanly mergeable on its
                    // own — and one path both sides moved.
                    write(homes.sourceUnit().resolve("mergeable.md"), "SOURCE MOVED THIS ALONE\n");
                    write(homes.sourceUnit().resolve("contested.md"), "SOURCE VERSION\n");
                    write(homes.destUnit().resolve("contested.md"), "DEST VERSION\n");
                    String digestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());
                    MaterializationRecord recordBefore = record(homes);

                    UnitSync outcome = only(sync(homes, true, false));

                    assertEquals(SyncStatus.CONFLICTED, outcome.status(), "the unit is conflicted");
                    // This is the assertion the property is actually about. A
                    // reconciler that wrote the clean path and reported the
                    // conflict would destroy nothing — mergeable.md is a path
                    // the destination never touched — and would still leave the
                    // unit half one version and half another, with a record
                    // describing a tree nobody chose.
                    assertEquals("base\n", read(homes.destUnit().resolve("mergeable.md")),
                            "the cleanly-mergeable path was NOT written");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "a conflicted unit writes nothing at all");
                    assertEquals(recordBefore.contentDigest(), record(homes).contentDigest(),
                            "and its materialization record is not advanced either");

                    // Both versions still exist, which is what makes the
                    // conflict resolvable by hand rather than merely reported.
                    assertEquals("SOURCE VERSION\n", read(homes.sourceUnit().resolve("contested.md")),
                            "the source still holds its version");
                    assertEquals("DEST VERSION\n", read(homes.destUnit().resolve("contested.md")),
                            "the destination still holds its version");
                })

                // -------------------------------------------- provenance rules

                .test("a merge result survives a second, unrelated source change", () -> {
                    // The defect MaterializationRecord.reconcileKind exists for.
                    // A merge result is byte-indistinguishable from a pristine
                    // copy when compared against its OWN record — both match it
                    // exactly — and a pristine copy may be overwritten the
                    // moment its source moves. Without the distinction the
                    // SECOND source change deletes everything the first merge
                    // folded in.
                    Homes homes = Homes.create("mergekind");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED\n");
                    write(homes.sourceUnit().resolve("upstream.md"), "v1\n");
                    write(homes.sourceUnit().resolve("local.md"), "v1\n");
                    sync(homes, false, false);

                    write(homes.destUnit().resolve("local.md"), "AGENT WORK\n");
                    write(homes.sourceUnit().resolve("upstream.md"), "v2\n");
                    assertEquals(SyncStatus.MERGED, only(sync(homes, true, false)).status(),
                            "the two disjoint edits merge");
                    assertEquals(MaterializationRecord.MERGED, record(homes).reconcileKind(),
                            "the result is recorded as a merge, not as a copy");

                    // Now the source moves AGAIN. The destination still matches
                    // its own record byte for byte, so nothing about the
                    // destination alone says it holds anything unique.
                    write(homes.sourceUnit().resolve("upstream.md"), "v3\n");
                    UnitSync second = only(sync(homes, false, false));

                    assertEquals("AGENT WORK\n", read(homes.destUnit().resolve("local.md")),
                            "the work the first merge folded in is still there");
                    assertEquals(SyncStatus.HELD_BACK, second.status(),
                            "the second source change does not take the wholesale-copy path");
                    assertEquals("v2\n", read(homes.destUnit().resolve("upstream.md")),
                            "and nothing was refreshed behind its back");
                })

                .test("a second --merge keeps the work the first one kept (CHM-10)", () -> {
                    // Was "DEFECT PIN CHM-10", which asserted the opposite and
                    // failed the moment the defect was fixed — deliberately, so
                    // that whoever fixed it had to come here and say what the
                    // right answer is. This is that answer.
                    //
                    // The defect: ChildHomeMaterializer.mergeBase preferred the
                    // DESTINATION's record, and a merge rewrote that record over
                    // the whole merged tree — local work included — so the
                    // destination's own edits became part of its recorded
                    // baseline. On the next --merge the source, which never held
                    // those bytes, was measured against them: `s != d`,
                    // `s != b`, `d == b`, which the algebra reads as "only the
                    // source moved" and takes. The agent's work was reverted to
                    // the source's version with no conflict and no report.
                    //
                    // The fix is one line of meaning rather than one of code:
                    // entryDigests records the SOURCE's tree at the reconcile —
                    // the state the two homes then shared — while contentDigest
                    // keeps recording what was written here. A merge result is
                    // a state the source never passed through, so it was never
                    // eligible to be the next merge's base.
                    //
                    // Two homes and one repeated source change are enough; the
                    // three-tier form of the same root cause is modelled by
                    // External_regression_mergebase.cfg, which must keep
                    // producing a counterexample because it models the OLD rule.
                    Homes homes = Homes.create("mergebase-defect");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SHARED\n");
                    write(homes.sourceUnit().resolve("upstream.md"), "v1\n");
                    write(homes.sourceUnit().resolve("local.md"), "v1\n");
                    sync(homes, false, false);

                    write(homes.destUnit().resolve("local.md"), "AGENT WORK\n");
                    write(homes.sourceUnit().resolve("upstream.md"), "v2\n");
                    assertEquals(SyncStatus.MERGED, only(sync(homes, true, false)).status(),
                            "the first merge keeps the local work");
                    assertEquals("AGENT WORK\n", read(homes.destUnit().resolve("local.md")),
                            "the first merge really did keep it");

                    // The source moves again, on the SAME file it moved before
                    // and nowhere near the agent's file.
                    write(homes.sourceUnit().resolve("upstream.md"), "v3\n");
                    UnitSync second = only(sync(homes, true, false));

                    assertEquals(SyncStatus.MERGED, second.status(),
                            "the second merge reports success");
                    assertEquals("v3\n", read(homes.destUnit().resolve("upstream.md")),
                            "the third source change arrives, as it should");

                    // The assertion the whole ticket is about. Bytes, not
                    // status: a merge that reports MERGED over a reverted file
                    // and one that reports MERGED over the kept file are the
                    // same string.
                    assertEquals("AGENT WORK\n", read(homes.destUnit().resolve("local.md")),
                            "the agent's work survives the second merge");
                    assertTrue(second.conflicts().isEmpty(),
                            "and it did not need a conflict to survive — the source never "
                                    + "moved that file, so there was nothing to resolve");
                    assertEquals(List.of("upstream.md"), second.files(),
                            "exactly the file the source moved was taken, and nothing else");

                    // A third round, because the defect was in what a merge
                    // WRITES DOWN: if the record still described a state the
                    // source never held, the next merge would revert the work
                    // one round later instead of never.
                    write(homes.sourceUnit().resolve("upstream.md"), "v4\n");
                    UnitSync third = only(sync(homes, true, false));
                    assertEquals(SyncStatus.MERGED, third.status(), "a third merge still merges");
                    assertEquals("AGENT WORK\n", read(homes.destUnit().resolve("local.md")),
                            "and the agent's work is still there");
                    assertEquals("v4\n", read(homes.destUnit().resolve("upstream.md")),
                            "with the fourth source change folded in");
                })

                .test("a fast-forward is not disposable to a home it never came from (CHM-9)", () -> {
                    // The whole of CHM-9, in the order it happened in
                    // production: sync up, close out, tear down, sync down.
                    //
                    // A plain (non-merge) `home sync --from <worktree> --to
                    // <project>` is a fast-forward: the project had moved
                    // nothing, so the whole unit is replaced. The record it
                    // writes says reconcileKind = "copy" and `source` names the
                    // worktree — and reconcile used to read only reconcileKind.
                    // So the project home held the agent's ONLY copy of the work
                    // while looking, to the next reconciliation, exactly like a
                    // pristine copy of anything else, and the next sync from the
                    // root home deleted it. Nothing was reported, because by
                    // every measurement of the destination alone the write was
                    // entitled: the project home genuinely had not moved
                    // anything.
                    //
                    // Modelled as External_regression_ffprovenance.cfg, which
                    // must keep producing a counterexample: it models the rule
                    // this test now asserts is gone.
                    Homes homes = Homes.create("ff-provenance");   // source = worktree
                    SkillStore root = store(homes.dest().root().resolveSibling("root"));
                    UnitFixtures.scaffoldSkill(root.skillsDir(), UNIT, DepSpec.empty());
                    write(root.skillDir(UNIT).resolve("SKILL.md"), "PROJECT V1\n");

                    write(homes.sourceUnit().resolve("SKILL.md"), "PROJECT V1\n");
                    sync(homes, false, false);
                    write(homes.sourceUnit().resolve("SKILL.md"), "AGENT IMPROVED THIS\n");
                    write(homes.sourceUnit().resolve("agent-note.md"), "notes only the agent has\n");

                    assertEquals(SyncStatus.UPDATED, only(sync(homes, false, false)).status(),
                            "an unmoved destination fast-forwards");
                    assertEquals("AGENT IMPROVED THIS\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the destination now holds the agent's work");

                    MaterializationRecord written = record(homes);
                    assertEquals(MaterializationRecord.COPIED, written.reconcileKind(),
                            "a fast-forward is recorded as a copy");
                    assertEquals(homes.sourceUnit().toString(), written.source(),
                            "and the record names the home the bytes came from");
                    assertTrue(HomeCloseOut.inspect(homes.source(), homes.dest()).safe(),
                            "close-out then allows that source home to be torn down");

                    // The teardown the gate just cleared. From here the project
                    // home is the only place the agent's work exists.
                    dev.skillmanager.shared.util.Fs.deleteRecursive(homes.source().root());

                    // And now the ROOT home ships its own new version.
                    write(root.skillDir(UNIT).resolve("SKILL.md"), "ROOT V2\n");
                    String digestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());
                    UnitSync fromRoot = only(HomeSync.run(root, homes.dest(),
                            new HomeSync.Options(false, false)));

                    // Bytes first: a status that says UPDATED over a tree that
                    // kept the work and one that says HELD_BACK over a tree that
                    // was overwritten anyway are the same string, and only one
                    // of them has lost the ticket's work.
                    assertEquals("AGENT IMPROVED THIS\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the agent's work is still the project home's content");
                    assertEquals("notes only the agent has\n",
                            read(homes.destUnit().resolve("agent-note.md")),
                            "including the file that existed only in the worktree");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "not one byte was written by the home that never held it");
                    assertEquals(SyncStatus.HELD_BACK, fromRoot.status(),
                            "and it is reported rather than silently skipped");
                    assertContains(fromRoot.detail(), "never held",
                            "the report says why: this source never had these bytes");

                    // --merge does not resolve it either: there is no state the
                    // root home and the project home can be shown to share, so
                    // the answer is a conflict a human settles, not a guess.
                    UnitSync merged = only(HomeSync.run(root, homes.dest(),
                            new HomeSync.Options(true, false)));
                    assertEquals(SyncStatus.CONFLICTED, merged.status(),
                            "with --merge it conflicts rather than inventing a common ancestor");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "and a conflict still writes nothing");
                })

                .test("the work survives the teardown that close-out cleared", () -> {
                    // Every other close-out test stops at the verdict. The
                    // proposition is about what happens AFTER the verdict is
                    // acted on, and a gate that returns the right answer to a
                    // teardown that then loses the work is no gate at all — so
                    // this one actually removes the home.
                    Homes homes = Homes.create("teardown");   // source = worktree, dest = project
                    write(homes.sourceUnit().resolve("SKILL.md"), "PROJECT V1\n");
                    sync(homes, false, false);

                    write(homes.sourceUnit().resolve("SKILL.md"), "AGENT IMPROVED THIS\n");
                    write(homes.sourceUnit().resolve("agent-note.md"), "notes only the agent has\n");
                    String worktreeTree = ChildHomeMaterializer.treeDigest(homes.sourceUnit());

                    assertFalse(HomeCloseOut.inspect(homes.source(), homes.dest()).safe(),
                            "the gate refuses while the worktree is the only place this exists");

                    // The remedy the blocker names, then the teardown it was
                    // guarding.
                    sync(homes, false, false);
                    assertTrue(HomeCloseOut.inspect(homes.source(), homes.dest()).safe(),
                            "the gate clears once the work has somewhere else to be");
                    dev.skillmanager.shared.util.Fs.deleteRecursive(homes.source().root());
                    assertFalse(Files.exists(homes.source().root()),
                            "the worktree home really is gone");

                    assertEquals("AGENT IMPROVED THIS\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the agent's edit outlived the home it was made in");
                    assertEquals("notes only the agent has\n",
                            read(homes.destUnit().resolve("agent-note.md")),
                            "so did the file only the agent had");
                    assertEquals(worktreeTree, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "byte for byte what the worktree held when the gate cleared it");
                })

                // ------------------------------- no baseline, no assumption

                .test("a destination unit with no materialization record is never overwritten", () -> {
                    Homes homes = Homes.create("norecord");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE\n");
                    sync(homes, false, false);

                    // A record can go missing for ordinary reasons: a home from
                    // before records existed, an interrupted write. Without one
                    // there is no evidence the tree is disposable, and "no
                    // evidence" must not read as "safe to delete".
                    Files.delete(recordFile(homes));
                    write(homes.destUnit().resolve("hand-placed.md"), "somebody copied this in\n");

                    String digestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());
                    UnitSync outcome = only(sync(homes, false, false));

                    // Bytes first. A status that says HELD_BACK over a tree that
                    // was overwritten anyway and a status that says UPDATED over
                    // a tree that was left alone are very different failures,
                    // and only one of them has lost anything.
                    assertEquals("somebody copied this in\n",
                            read(homes.destUnit().resolve("hand-placed.md")),
                            "the untracked content is still there");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "not one byte of the unrecorded unit changed");
                    assertEquals(SyncStatus.HELD_BACK, outcome.status(),
                            "a unit with no record is held back");
                    assertContains(outcome.detail(), "no usable materialization record",
                            "and the report says why");
                })

                .test("a record written by a newer skill-manager reads as no baseline, not as a crash", () -> {
                    Homes homes = Homes.create("unknownmode");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE\n");
                    sync(homes, false, false);
                    write(homes.destUnit().resolve("agent.md"), "agent work\n");

                    // A mode this build has never heard of. It must route to the
                    // conservative path, not to a parse failure: refusing to run
                    // at all is a worse failure than refusing to overwrite.
                    Files.writeString(recordFile(homes), """
                            {
                              "schemaVersion" : 1,
                              "unitName" : "%s",
                              "unitKind" : "SKILL",
                              "mode" : "OVERLAY",
                              "sourceDigest" : "deadbeef",
                              "contentDigest" : "deadbeef",
                              "quantumEntanglement" : true
                            }
                            """.formatted(UNIT));

                    String digestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());
                    UnitSync outcome = only(sync(homes, false, false));

                    assertEquals("agent work\n", read(homes.destUnit().resolve("agent.md")),
                            "the tree is left exactly as it was");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "not one byte changed");
                    assertEquals(SyncStatus.HELD_BACK, outcome.status(),
                            "an unrecognized mode means no usable baseline");
                })

                .test("a merge with no per-file baseline anywhere conflicts rather than guessing", () -> {
                    Homes homes = Homes.create("nobaseline");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    sync(homes, false, false);
                    write(homes.destUnit().resolve("SKILL.md"), "AGENT VERSION\n");
                    write(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");

                    // An older record: a whole-tree digest and no entryDigests.
                    // It can still say "this unit changed"; it cannot say WHICH
                    // files each side changed, which is the whole input to the
                    // three-way decision. Neither home has one, so there is no
                    // merge base at all.
                    MaterializationRecord full = record(homes);
                    Files.writeString(recordFile(homes), """
                            {
                              "schemaVersion" : 1,
                              "unitName" : "%s",
                              "unitKind" : "SKILL",
                              "mode" : "COPY",
                              "sourceDigest" : "%s",
                              "contentDigest" : "%s"
                            }
                            """.formatted(UNIT, full.sourceDigest(), full.contentDigest()));
                    String digestBefore = ChildHomeMaterializer.treeDigest(homes.destUnit());

                    UnitSync outcome = only(sync(homes, true, false));

                    assertEquals(SyncStatus.CONFLICTED, outcome.status(),
                            "no per-file baseline means no merge base");
                    assertContains(outcome.detail(), "no merge base",
                            "and the report says so rather than inventing one");
                    assertEquals(digestBefore, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "nothing was written");
                    assertEquals("AGENT VERSION\n", read(homes.destUnit().resolve("SKILL.md")),
                            "the agent's version is intact");
                })

                // ------------------------------------------------------- lock

                .test("the home lock lives where nothing that enumerates a home will find it", () -> {
                    Path root = Files.createTempDirectory("home-lock-where-").resolve("home");
                    SkillStore store = store(root);
                    List<String> rootBefore = listing(root);

                    try (HomeLock ignored = HomeLock.acquire(root, "test")) {
                        // Observed on the filesystem, not asserted against
                        // HomeLock.file: a new entry at the ROOT of a home shows
                        // up in `home clone` reports and in everything that
                        // walks a home, and the whole reason the lock lives
                        // under .materialization/ is that the records directory
                        // is already private bookkeeping already excluded from
                        // every unit scan.
                        assertFalse(listing(root).contains(HomeLock.FILENAME),
                                "taking the lock puts no lock file at the home root");
                        assertEquals(withoutRecords(rootBefore), withoutRecords(listing(root)),
                                "and adds nothing at the home root but the records directory "
                                        + "itself, which every unit scan already excludes");
                        assertTrue(listing(root.resolve(ChildHomeMaterializer.RECORDS_DIR))
                                        .contains(HomeLock.FILENAME),
                                "it appears beside the per-unit records instead");
                        assertEquals(root.resolve(ChildHomeMaterializer.RECORDS_DIR)
                                        .resolve(HomeLock.FILENAME),
                                HomeLock.file(root),
                                "and HomeLock.file agrees with where it actually is");
                    }
                    assertEquals(0L, Files.size(HomeLock.file(root)),
                            "the lock carries no payload, so nothing reads a stale one");
                    assertTrue(store.root().equals(root), "store root unchanged");
                })

                .test("the home lock is re-entrant on one thread and exclusive across threads", () -> {
                    Path root = Files.createTempDirectory("home-lock-reentrant-").resolve("home");
                    store(root);

                    // FileLock belongs to the JVM, not to the thread, so a
                    // lock-holding operation that calls another one would ask
                    // the OS for a lock it already holds and get
                    // OverlappingFileLockException rather than waiting.
                    try (HomeLock outer = HomeLock.acquire(root, "outer")) {
                        try (HomeLock inner = HomeLock.acquire(root, "inner", Duration.ofSeconds(1))) {
                            assertTrue(inner != outer, "the nested acquisition is its own handle");
                        }
                        // ... and the outer one is still held after the inner
                        // one is closed. A peer thread must still be excluded.
                        AtomicReference<Throwable> peer = new AtomicReference<>();
                        Thread thread = new Thread(() -> {
                            try (HomeLock ignored = HomeLock.acquire(root, "peer",
                                    Duration.ofMillis(250))) {
                                peer.set(new AssertionError("the peer acquired a held lock"));
                            } catch (Throwable t) {
                                peer.set(t);
                            }
                        });
                        thread.start();
                        thread.join(10_000);
                        assertTrue(peer.get() instanceof IOException,
                                "a peer thread is refused, not admitted: " + peer.get());
                        assertContains(peer.get().getMessage(), root.toString(),
                                "and the refusal names the contended home");
                    }

                    // Once released, the peer gets it.
                    try (HomeLock ignored = HomeLock.acquire(root, "after", Duration.ofSeconds(2))) {
                        assertTrue(true, "the lock is available again after the outer close");
                    }
                })

                .test("a home lock is released when the operation under it fails", () -> {
                    Path root = Files.createTempDirectory("home-lock-release-").resolve("home");
                    store(root);
                    boolean threw = false;
                    try (HomeLock ignored = HomeLock.acquire(root, "failing")) {
                        throw new IOException("the operation blew up");
                    } catch (IOException expected) {
                        threw = true;
                    }
                    assertTrue(threw, "the failure propagated");
                    // A lock leaked by a failed operation wedges the home for
                    // every later run, and the symptom (a timeout) points at the
                    // innocent caller rather than at the one that leaked it.
                    try (HomeLock ignored = HomeLock.acquire(root, "next", Duration.ofMillis(500))) {
                        assertTrue(true, "the next caller is not blocked by the failed one");
                    }
                })

                .test("two homes do not contend for one lock", () -> {
                    Path root = Files.createTempDirectory("home-lock-distinct-");
                    Path left = root.resolve("left");
                    Path right = root.resolve("right");
                    store(left);
                    store(right);
                    CountDownLatch bothHeld = new CountDownLatch(2);
                    List<Throwable> failures = new ArrayList<>();
                    List<Thread> threads = new ArrayList<>();
                    for (Path home : List.of(left, right)) {
                        Thread thread = new Thread(() -> {
                            try (HomeLock ignored = HomeLock.acquire(home, "distinct",
                                    Duration.ofSeconds(5))) {
                                bothHeld.countDown();
                                // Only completes if the other home's lock is
                                // genuinely independent of this one.
                                if (!bothHeld.await(5, TimeUnit.SECONDS)) {
                                    synchronized (failures) {
                                        failures.add(new AssertionError(
                                                "the two homes serialized against each other"));
                                    }
                                }
                            } catch (Throwable t) {
                                synchronized (failures) { failures.add(t); }
                            }
                        });
                        threads.add(thread);
                        thread.start();
                    }
                    for (Thread thread : threads) thread.join(20_000);
                    assertTrue(failures.isEmpty(), "both locks are held at once: " + failures);
                })

                .runAll();
    }

    // ------------------------------------------------------------- helpers

    private record Homes(SkillStore source, SkillStore dest) {

        static Homes create(String label) throws IOException {
            Path root = Files.createTempDirectory("home-sync-merge-" + label + "-");
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

    /** The destination home's materialization record for {@link #UNIT}. */
    private static MaterializationRecord record(Homes homes) {
        return new ChildHomeMaterializer(homes.source(), homes.dest())
                .readRecord(UNIT, UnitKind.SKILL)
                .orElseThrow(() -> new AssertionError("no materialization record for " + UNIT));
    }

    private static Path recordFile(Homes homes) {
        return new ChildHomeMaterializer(homes.source(), homes.dest())
                .recordFile(UNIT, UnitKind.SKILL);
    }

    private static List<String> withoutRecords(List<String> names) {
        return names.stream()
                .filter(n -> !n.equals(ChildHomeMaterializer.RECORDS_DIR))
                .toList();
    }

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
        return Files.isRegularFile(file) ? Files.readString(file) : "<absent>";
    }
}
