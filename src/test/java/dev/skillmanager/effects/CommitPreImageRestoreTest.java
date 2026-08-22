package dev.skillmanager.effects;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.source.MaterializationEscrow;
import dev.skillmanager.store.HomeLock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * #186, both halves.
 *
 * <h2>Half one — the walk-back restores what the commit overwrote</h2>
 *
 * <p>{@code Executor.preStateCompensations} paired
 * {@code CommitUnitsToStore} with {@code List.of()}: an EMPTY pre-state
 * compensation, in an exhaustive switch, for the one effect that overwrites
 * bytes already in the home. The commit deletes the destination and copies the
 * new tree in; the walk-back's {@code DeleteUnitDir} then deleted what it found
 * and restored nothing, because nothing had been captured. A resolve that
 * failed after the commit therefore left the previously installed unit
 * <b>absent</b> rather than as it was.
 *
 * <h2>What makes this fixture able to fail</h2>
 *
 * <p>Three properties, and #187 is the standing reminder of what happens when
 * one of them is missing — an empty {@code previousLock} there made the
 * destructive step a no-op, so the assertion could not detect its removal.
 *
 * <ol>
 *   <li>The destination is <b>genuinely non-empty</b> before the run: a real
 *       unit tree, with a nested directory and a marker file, whose digest is
 *       taken path-by-path.</li>
 *   <li>The incoming tree is <b>genuinely different</b>: different SKILL.md
 *       body, and it does not carry the marker file. So
 *       {@code deleteRecursive(dst)} in the commit handler has real work to
 *       do, and a rollback that only deletes is observably lossy.</li>
 *   <li>The failure is injected <b>after</b> the commit, not at it, so the
 *       commit's own mid-copy self-rollback is not what is under test.</li>
 * </ol>
 *
 * <p>Vacuity checked, five ways, all recorded verbatim in
 * {@code results/epic-home-integrity-sync/probes/his-11/vacuity-checks.txt} and
 * re-runnable with {@code jbang RunHis11.java}. The headline one: with
 * {@code case SkillEffect.CommitUnitsToStore e -> List.of();} put back in
 * {@code preStateCompensations}, the skill case fails with
 * {@code expected <{SKILL.md=…/19B, nested/keepme.txt=…/29B,
 * skill-manager.toml=…/72B}> but was <{}>} — 120 pre-existing bytes down to
 * zero, the unit directory not merely different but gone.
 *
 * <h2>Half two — the home lock on the resolve/install path</h2>
 *
 * <p>Nothing on that path held a mutex, so two resolves into one home
 * interleaved staging and commit. {@link Executor} now takes {@link HomeLock}
 * around every program. The two cells below pin the two outcomes the
 * acceptance asks to be distinguishable: the second caller WAITS (and says so
 * on the console before it starts waiting), or it REFUSES with a message
 * naming the home.
 */
public final class CommitPreImageRestoreTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CommitPreImageRestoreTest");

        for (UnitKind kind : List.of(UnitKind.SKILL, UnitKind.PLUGIN)) {
            String label = kind.name().toLowerCase();

            suite.test("the pre-installed " + label + " survives a failure after the commit, "
                    + "byte-for-byte", () -> {
                try (TestHarness h = TestHarness.create()) {
                    Path dst = h.store().unitDir("gamma", kind);
                    installVersionA(dst, kind);
                    Map<String, String> before = digest(dst);
                    assertTrue(before.size() >= 3,
                            "fixture precondition: the destination is genuinely non-empty");
                    System.out.println("  pre-existing " + label + ": " + before.size()
                            + " file(s), " + totalBytes(dst) + " bytes at " + dst);

                    ResolvedGraph graph = versionB(kind);
                    // Step 1 fails; step 0 (the commit) has already run and
                    // succeeded, which is exactly the shape #186 describes.
                    Program<Void> program = new Program<>("preimage-" + label,
                            List.of(new SkillEffect.CommitUnitsToStore(graph),
                                    new SkillEffect.PrintInstalledSummary()),
                            receipts -> null);

                    Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                            .withFaultInjection(i -> i == 1)
                            .runWithContext(program, h.context());

                    assertTrue(outcome.rolledBack(), "the executor walked back");
                    assertEquals(before.toString(), digest(dst).toString(),
                            "the pre-installed " + label + " survives byte-for-byte");
                }
            });
        }

        suite.test("a commit that succeeds keeps the NEW bytes and leaves no escrow behind", () -> {
            try (TestHarness h = TestHarness.create()) {
                Path dst = h.store().unitDir("gamma", UnitKind.SKILL);
                installVersionA(dst, UnitKind.SKILL);

                ResolvedGraph graph = versionB(UnitKind.SKILL);
                Program<Void> program = new Program<>("preimage-success",
                        List.of(new SkillEffect.CommitUnitsToStore(graph)), receipts -> null);

                Executor.Outcome<Void> outcome =
                        new Executor(h.store(), null).runWithContext(program, h.context());

                assertFalse(outcome.rolledBack(), "no rollback — the program committed");
                assertContains(Files.readString(dst.resolve("SKILL.md")), "VERSION-B",
                        "the destination holds the newly committed tree");
                assertFalse(Files.exists(dst.resolve("nested").resolve("keepme.txt")),
                        "and not the superseded one");
                assertEquals(List.of().toString(), escrowDirs(h.store().root()).toString(),
                        "the pre-image is discarded on success — cache/ holds no escrow");
            }
        });

        suite.test("a commit into an EMPTY destination still rolls back to absent", () -> {
            // The guard must not resurrect a unit that was never there. Without
            // this cell, an escrow that restored unconditionally would look
            // correct on every case above and wrong on a fresh install.
            try (TestHarness h = TestHarness.create()) {
                ResolvedGraph graph = versionB(UnitKind.SKILL);
                Program<Void> program = new Program<>("preimage-fresh",
                        List.of(new SkillEffect.CommitUnitsToStore(graph),
                                new SkillEffect.PrintInstalledSummary()),
                        receipts -> null);

                Executor.Outcome<Void> outcome = new Executor(h.store(), null)
                        .withFaultInjection(i -> i == 1)
                        .runWithContext(program, h.context());

                assertTrue(outcome.rolledBack(), "the executor walked back");
                assertFalse(Files.exists(h.store().unitDir("gamma", UnitKind.SKILL)),
                        "a fresh install rolls back to absent, not to a resurrected tree");
                assertEquals(List.of().toString(), escrowDirs(h.store().root()).toString(),
                        "and nothing was parked in cache/ that nothing cleans");
            }
        });

        suite.test("an exception escaping the program releases the escrow instead of "
                + "stranding it", () -> {
            // Found by the adversarial review of #233. `commit` and `walkBack`
            // are the only two drains and both sat on ordinary control-flow
            // edges, so an exception escaping the body reached NEITHER: the
            // commit had already landed, the process was still alive, and the
            // held pre-image stayed in cache/ with nothing referencing it.
            // The reachable trigger is the stage-2 builder, a caller-supplied
            // lambda outside any drain.
            try (TestHarness h = TestHarness.create()) {
                Path dst = h.store().unitDir("gamma", UnitKind.SKILL);
                installVersionA(dst, UnitKind.SKILL);

                ResolvedGraph graph = versionB(UnitKind.SKILL);
                StagedProgram<Void> staged = new StagedProgram<>("preimage-escape",
                        new Program<>("preimage-escape-1",
                                List.of(new SkillEffect.CommitUnitsToStore(graph)),
                                receipts -> null),
                        ctx -> { throw new IllegalStateException("stage 2 builder blew up"); },
                        receipts -> null);

                boolean threw = false;
                try {
                    new Executor(h.store(), null).runStaged(staged);
                } catch (IllegalStateException expected) {
                    threw = true;
                }
                assertTrue(threw, "the exception still propagates — the drain is not a catch");
                assertEquals(List.of().toString(), escrowDirs(h.store().root()).toString(),
                        "and the escrow was released rather than stranded in cache/");
                // The drain DISCARDS, which is what makes the escape path
                // byte-for-byte what it was before this ticket: the commit's
                // bytes stay put, and the only difference is the absent
                // residue. Restoring here would revert a commit nobody rolled
                // back while the rest of the journal stayed applied.
                assertContains(Files.readString(dst.resolve("SKILL.md")), "VERSION-B",
                        "the committed bytes are left alone — an escape is not a rollback signal");
            }
        });

        suite.test("a HALT after the commit keeps the new bytes and discards the pre-image, "
                + "deliberately", () -> {
            // Finding #5 of #233's review: `runStaged` treats halted && !failed
            // as "not going to be walked back", so a cooperative stop AFTER a
            // commit discards the pre-image. That is argued for in
            // Executor.commit's javadoc, and an argued decision that destroys
            // operator bytes should have a cell rather than a paragraph.
            //
            // The LIVE instance is SyncUseCase, whose effect list runs
            // CommitUnitsToStore and then BuildInstallPlan, and BuildInstallPlan
            // returns okAndHalt when the plan is policy-blocked. (The review
            // named RunInstallPlan; that handler only ever returns ok or
            // partial, and InstallUseCase puts both of its halting effects
            // BEFORE the commit. The shape is real, the instance is on the sync
            // path.) Reproduced here with the same shape the interpreter emits:
            // an OK receipt carrying Continuation.HALT.
            try (TestHarness h = TestHarness.create()) {
                Path dst = h.store().unitDir("gamma", UnitKind.SKILL);
                installVersionA(dst, UnitKind.SKILL);

                ResolvedGraph graph = versionB(UnitKind.SKILL);
                // RejectIfAlreadyInstalled is the okAndHalt that is reachable
                // from a fixture: after the commit, `gamma` IS installed, so it
                // halts with status OK — exactly halted && !failed.
                // The decoder reports whether a HALT with a NON-failed status
                // actually occurred. Without it this cell would pass just as
                // well if RejectIfAlreadyInstalled had quietly returned ok --
                // i.e. it would assert the discard on the ordinary success edge
                // and call it the halt edge.
                ResultDecoder<Boolean> haltedOk = receipts -> receipts.stream().anyMatch(
                        r -> r.continuation() == Continuation.HALT
                                && r.status() != EffectStatus.FAILED);
                StagedProgram<Boolean> staged = new StagedProgram<>("preimage-halt",
                        new Program<>("preimage-halt-1",
                                List.of(new SkillEffect.CommitUnitsToStore(graph),
                                        new SkillEffect.RejectIfAlreadyInstalled("gamma")),
                                haltedOk),
                        ctx -> new Program<>("preimage-halt-2", List.of(), receipts -> null),
                        haltedOk);

                Executor.Outcome<Boolean> outcome = new Executor(h.store(), null).runStaged(staged);

                assertTrue(outcome.result(),
                        "fixture precondition: a non-FAILED HALT really did fire after the commit");
                assertFalse(outcome.rolledBack(), "a halt is not a rollback");
                assertContains(Files.readString(dst.resolve("SKILL.md")), "VERSION-B",
                        "the committed bytes stay — the halt was about what comes AFTER the commit");
                assertEquals(List.of().toString(), escrowDirs(h.store().root()).toString(),
                        "and the superseded version is let go, not held forever");
            }
        });

        suite.test("a held escrow says what it is holding and where it goes back", () -> {
            // #233's review, finding #4: moving the bytes out of skills/ fixed
            // the NAMESPACE half of #231's defect and left the DETECTION half
            // untouched -- `.materialization-escrow-` appeared in exactly two
            // places, the class that creates it and a test helper. HIS-13 is
            // asked to name what is damaged in a home and what would repair it,
            // and it cannot own a condition with no marker.
            //
            // This is asserted directly on the escrow rather than through the
            // executor because every executor path that SUCCEEDS releases the
            // escrow, so a stranded one is never observable from outside -- the
            // fix would otherwise ship with nothing checking it.
            try (TestHarness h = TestHarness.create()) {
                Path dst = h.store().unitDir("gamma", UnitKind.SKILL);
                installVersionA(dst, UnitKind.SKILL);

                MaterializationEscrow escrow = MaterializationEscrow.liftPaths(
                        dst.getParent(), h.store().root(), List.of("gamma"), false,
                        "commit pre-image: gamma (SKILL)");
                assertFalse(escrow.isEmpty(), "fixture precondition: something was actually lifted");
                assertFalse(Files.exists(dst), "and it really left the units namespace");

                List<String> dirs = escrowDirs(h.store().root());
                assertEquals(1, dirs.size(), "exactly one holding directory");
                Path manifest = h.store().root().resolve("cache")
                        .resolve(dirs.get(0)).resolve(MaterializationEscrow.MANIFEST);
                assertTrue(Files.isRegularFile(manifest),
                        "the holding directory carries a manifest, so a stranded escrow is findable");
                String text = Files.readString(manifest);
                assertContains(text, "purpose=commit pre-image: gamma (SKILL)",
                        "which says what took it and for which unit");
                assertContains(text, "store-dir=" + dst.getParent(),
                        "and which store directory the paths are relative to");
                assertContains(text, "0=gamma",
                        "and the slot each held tree is in — enough to put it back by hand");

                escrow.restore();
                assertEquals(List.of().toString(), escrowDirs(h.store().root()).toString(),
                        "and the manifest goes with the holding directory it describes");
                assertTrue(Files.isRegularFile(dst.resolve("nested").resolve("keepme.txt")),
                        "and the bytes came back");
            }
        });

        suite.test("a renderer that throws does not strand the escrow either — the journal "
                + "learns about it before the effect runs", () -> {
            // The narrowest of the four escape points #233's review named, and
            // the one a `finally` alone does NOT fix: a drain can only release
            // what the journal knows about. `LiveInterpreter.runOne` catches
            // around `execute` but calls `renderer.onReceipt` OUTSIDE that
            // catch, so a renderer that throws escaped with the bytes already
            // lifted and the journal still empty. Fixed by journalling the
            // pre-state compensation before the effect runs rather than after.
            try (TestHarness h = TestHarness.create()) {
                Path dst = h.store().unitDir("gamma", UnitKind.SKILL);
                installVersionA(dst, UnitKind.SKILL);

                ResolvedGraph graph = versionB(UnitKind.SKILL);
                ProgramRenderer exploding = new ProgramRenderer() {
                    @Override public void onReceipt(EffectReceipt receipt) {
                        throw new IllegalStateException("renderer blew up on " + receipt.status());
                    }
                    @Override public void onComplete() {}
                };
                Program<Void> program = new Program<>("preimage-renderer",
                        List.of(new SkillEffect.CommitUnitsToStore(graph)), receipts -> null);

                boolean threw = false;
                try {
                    new Executor(h.store(), null).runWithContext(
                            program, new EffectContext(h.store(), null, exploding));
                } catch (RuntimeException expected) {
                    threw = true;
                }
                assertTrue(threw, "fixture precondition: the renderer really did escape");
                assertEquals(List.of().toString(), escrowDirs(h.store().root()).toString(),
                        "the escrow was released, not parked with nothing referencing it");
            }
        });

        // ------------------------------------------- half two: the home lock

        suite.test("a second program against the same home WAITS, and says so before it waits", () -> {
            try (TestHarness h = TestHarness.create()) {
                ResolvedGraph graph = versionB(UnitKind.SKILL);
                Program<Void> program = new Program<>("resolve-second",
                        List.of(new SkillEffect.CommitUnitsToStore(graph)), receipts -> null);

                PrintStream realOut = System.out;
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                CompletableFuture<Boolean> second;
                boolean finishedEarly;
                try {
                    try (HomeLock first = HomeLock.acquire(h.store().root(), "resolve-first")) {
                        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                        second = CompletableFuture.supplyAsync(() -> !new Executor(h.store(), null)
                                .runWithContext(program, new EffectContext(h.store(), null))
                                .rolledBack());
                        // Not "it finished late" — it must not have finished AT
                        // ALL while the first holder was still in its try block.
                        try {
                            second.get(600, TimeUnit.MILLISECONDS);
                            finishedEarly = true;
                        } catch (java.util.concurrent.TimeoutException expected) {
                            finishedEarly = false;
                        }
                    }
                    second.get(30, TimeUnit.SECONDS);
                } finally {
                    System.setOut(realOut);
                }
                assertFalse(finishedEarly,
                        "the second program did not run while the first held the home");
                assertTrue(second.get(30, TimeUnit.SECONDS),
                        "and it completed once the first let go");
                assertContains(captured.toString(StandardCharsets.UTF_8), "waiting for",
                        "the wait announces itself rather than looking like a hang");
                assertContains(captured.toString(StandardCharsets.UTF_8),
                        h.store().root().toAbsolutePath().normalize().toString(),
                        "and names which home it is waiting for");
                assertTrue(Files.exists(h.store().unitDir("gamma", UnitKind.SKILL)),
                        "the second program's commit landed after the wait, not during it");
            }
        });

        suite.test("a second program that will not get the home REFUSES, naming it", () -> {
            try (TestHarness h = TestHarness.create()) {
                ResolvedGraph graph = versionB(UnitKind.SKILL);
                Program<Void> program = new Program<>("resolve-refused",
                        List.of(new SkillEffect.CommitUnitsToStore(graph)), receipts -> null);

                String message = null;
                try (HomeLock first = HomeLock.acquire(h.store().root(), "resolve-first")) {
                    CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                        try {
                            new Executor(h.store(), null)
                                    .withHomeLockTimeout(Duration.ofMillis(200))
                                    .runWithContext(program, new EffectContext(h.store(), null));
                            return null;
                        } catch (java.io.UncheckedIOException refused) {
                            return refused.getMessage();
                        }
                    });
                    message = second.get(30, TimeUnit.SECONDS);
                }
                Tests.assertNotNull(message, "the second program refused rather than proceeding");
                assertContains(message, "resolve-refused", "the refusal names the operation");
                assertContains(message, h.store().root().toAbsolutePath().normalize().toString(),
                        "and the home that is contended");
                assertContains(message, "is locked by another",
                        "and says the home is held, not that something crashed");
                assertFalse(Files.exists(h.store().unitDir("gamma", UnitKind.SKILL)),
                        "a refused program committed nothing");
            }
        });

        return suite.runAll();
    }

    // ------------------------------------------------------------- fixtures

    /**
     * The tree that is ALREADY installed — "version A". Built by hand rather
     * than through a commit so the test does not depend on the code path it is
     * about, and given a nested marker file that version B does not have, so
     * "the destination was overwritten" and "the destination was restored" are
     * distinguishable by content and not only by presence.
     */
    private static void installVersionA(Path dst, UnitKind kind) throws IOException {
        Path tmp = Files.createTempDirectory("preimage-a-");
        AgentUnit a = UnitFixtures.buildEquivalent(kind, tmp, "gamma", DepSpec.empty());
        dev.skillmanager.shared.util.Fs.ensureDir(dst.getParent());
        dev.skillmanager.shared.util.Fs.copyRecursive(a.sourcePath(), dst);
        Files.writeString(dst.resolve("SKILL.md"), "# gamma\n\nVERSION-A\n");
        Files.createDirectories(dst.resolve("nested"));
        Files.writeString(dst.resolve("nested").resolve("keepme.txt"), "installed before the resolve\n");
    }

    /** The tree the resolve is bringing in — "version B", deliberately different. */
    private static ResolvedGraph versionB(UnitKind kind) throws IOException {
        Path tmp = Files.createTempDirectory("preimage-b-");
        AgentUnit u = UnitFixtures.buildEquivalent(kind, tmp, "gamma", DepSpec.empty());
        Files.writeString(u.sourcePath().resolve("SKILL.md"), "# gamma\n\nVERSION-B\n");
        ResolvedGraph g = new ResolvedGraph();
        g.add(new ResolvedGraph.Resolved(
                "gamma", "0.2.0", "gamma", ResolvedGraph.SourceKind.LOCAL,
                u.sourcePath(), 0L, null, u, false, List.of()));
        return g;
    }

    /** Relative path → SHA-256 of the file's bytes, for every file under {@code root}. */
    private static Map<String, String> digest(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.toList()) {
                if (!Files.isRegularFile(p)) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest(Files.readAllBytes(p));
                StringBuilder hex = new StringBuilder();
                for (byte b : h) hex.append(String.format("%02x", b));
                // Size alongside the digest so a failure message reports the
                // goal's metric — pre-existing unit BYTES surviving — rather
                // than only "these differ".
                out.put(root.relativize(p).toString(), hex + "/" + Files.size(p) + "B");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out;
    }

    /** Total bytes of every regular file under {@code root}. */
    private static long totalBytes(Path root) throws IOException {
        if (!Files.isDirectory(root)) return 0L;
        try (Stream<Path> walk = Files.walk(root)) {
            long total = 0L;
            for (Path p : walk.toList()) if (Files.isRegularFile(p)) total += Files.size(p);
            return total;
        }
    }

    /** Any escrow holding directory left under {@code <home>/cache/}. */
    private static List<String> escrowDirs(Path homeRoot) throws IOException {
        Path cache = homeRoot.resolve("cache");
        if (!Files.isDirectory(cache)) return List.of();
        try (Stream<Path> kids = Files.list(cache)) {
            return kids.map(p -> p.getFileName().toString())
                    // The constant, not a copy of it: a test that hard-codes
                    // the prefix goes green the day the class changes it.
                    .filter(n -> n.startsWith(MaterializationEscrow.PREFIX))
                    .sorted()
                    .toList();
        }
    }
}
