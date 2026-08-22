package dev.skillmanager.effects;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.resolve.ResolvedGraph;
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
 * <p>Vacuity checked by hand: with
 * {@code case SkillEffect.CommitUnitsToStore e -> List.of();} put back in
 * {@code preStateCompensations}, case 1 fails with
 * {@code the pre-installed skill survives byte-for-byte: expected [SKILL.md,
 * nested/keepme.txt, skill-manager.toml] got []} — the unit directory is gone
 * entirely. Recorded in
 * {@code results/epic-home-integrity-sync/probes/his-11/}.
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
                out.put(root.relativize(p).toString(), hex.toString());
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out;
    }

    /** Any escrow holding directory left under {@code <home>/cache/}. */
    private static List<String> escrowDirs(Path homeRoot) throws IOException {
        Path cache = homeRoot.resolve("cache");
        if (!Files.isDirectory(cache)) return List.of();
        try (Stream<Path> kids = Files.list(cache)) {
            return kids.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".materialization-escrow-"))
                    .sorted()
                    .toList();
        }
    }
}
