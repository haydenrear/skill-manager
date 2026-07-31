package dev.skillmanager.plan;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.SyncUseCase;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.HomeCloner;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code <home>/audit.log}: what a home can still say about itself after the
 * fact.
 *
 * <h2>Issue #45</h2>
 *
 * <p>{@code install} appended to it and {@code sync} did not. Measured on the
 * operator's real home, the last entry was 8 days old while a {@code sync}
 * rewrote 101 files in it — no trail at all for the command that touches the
 * most files, discovered precisely when a trail was needed.
 *
 * <p>The reason the obvious fix is not enough on its own, and why these
 * assertions are about the file's CONTENT rather than about an effect being
 * present in a program: sync's install plan is built from the units whose
 * references turned out to be unmet, so an ordinary steady-state sync plans zero
 * actions. Emitting {@code RecordAuditPlan("sync")} and stopping there would
 * have produced a green "sync is audited now" against a log that still gained
 * nothing on the pass that re-merged every unit. So the assertion below reads
 * the bytes back and requires the unit's name to be in them.
 *
 * <h2>The other half</h2>
 *
 * <p>{@code home clone} must NOT start carrying the file. It is the source
 * home's own history — a clone that inherited it would claim actions it never
 * took, which is the same class of lie the materialization records exist to
 * avoid. That is asserted here beside the write, because "sync writes it" and "a
 * clone does not copy it" are the two facts that can regress into each other.
 */
public final class AuditTrailTest {

    private static final String UNIT = "audited-skill";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("AuditTrailTest");

        suite.test("sync appends a line naming the verb and the unit it touched", () -> {
            try (TestHarness h = TestHarness.create()) {
                Path work = Files.createTempDirectory("audit-sync-");
                Path unitDir = UnitFixtures.scaffoldSkill(work.resolve("v1"), UNIT,
                        DepSpec.empty()).sourcePath();
                h.scaffoldUnitDir(UNIT, UnitKind.SKILL);
                h.seedUnit(UNIT, UnitKind.SKILL);

                // Half one: the file says nothing about a sync yet. Without this
                // the assertion below could be reading a line some other command
                // wrote and calling it a pass.
                assertEquals(List.of(), syncLines(h.store()),
                        "the log has no sync entry before the sync runs");

                Path updated = UnitFixtures.scaffoldSkill(work.resolve("v2"), UNIT,
                        DepSpec.empty()).sourcePath();
                Files.createDirectories(updated.resolve("references"));
                Files.writeString(updated.resolve("references/added.md"), "new upstream file\n");

                Executor.Outcome<SyncUseCase.Report> outcome =
                        new Executor(h.store(), null).runStaged(SyncUseCase.buildProgram(
                                h.store(), null,
                                new SyncUseCase.Options(null, false, false, false, false, true),
                                List.of(new SyncUseCase.Target.FromDir(UNIT, updated)),
                                List.of()));

                assertFalse(outcome.rolledBack(), "the sync itself succeeded");
                List<String> lines = syncLines(h.store());
                assertFalse(lines.isEmpty(),
                        "the sync appended at least one audit entry; the log holds:\n"
                                + dump(auditFile(h.store())));
                assertTrue(lines.stream().anyMatch(l -> l.contains(UNIT)),
                        "and an entry names the unit whose bytes moved: " + lines);
                assertTrue(lines.stream().anyMatch(l -> l.contains("from-dir")),
                        "and which arm moved them — a unit re-merged from its trunk and one "
                                + "overwritten from a directory are different sets of bytes: "
                                + lines);
                for (String line : lines) {
                    assertEquals(4, line.split("\t", -1).length,
                            "every entry keeps the log's timestamp/verb/severity/detail shape, "
                                    + "however it got there: <" + line + ">");
                }
                // Append-only: a second sync adds to the trail rather than
                // replacing it. An audit log that rewrote itself would be
                // indistinguishable from one that worked, until it mattered.
                int before = lines.size();
                new Executor(h.store(), null).runStaged(SyncUseCase.buildProgram(
                        h.store(), null,
                        new SyncUseCase.Options(null, false, false, false, false, true),
                        List.of(new SyncUseCase.Target.FromDir(UNIT, unitDir)),
                        List.of()));
                assertTrue(syncLines(h.store()).size() > before,
                        "a second sync appends rather than replacing");
            }
        });

        suite.test("a clone does not carry the source home's audit log", () -> {
            Path source = Files.createTempDirectory("audit-clone-source-");
            SkillStore store = new SkillStore(source);
            store.init();
            Files.createDirectories(source.resolve("skills/alpha"));
            Files.writeString(source.resolve("skills/alpha/SKILL.md"),
                    "---\nname: alpha\ndescription: fixture\n---\nbody\n");
            new AuditLog(store).record("sync", "INFO\tsync from-dir alpha");
            assertTrue(Files.isRegularFile(auditFile(store)), "the source home has a log");

            Path dest = Files.createTempDirectory("audit-clone-dest-").resolve("home");
            HomeCloner.Report report = HomeCloner.cloneHome(source, dest);

            assertTrue(report.clean(), "the clone verified clean: " + report.leaks());
            assertTrue(Files.isRegularFile(dest.resolve("skills/alpha/SKILL.md")),
                    "the clone really did copy the home — otherwise the absence below proves "
                            + "nothing");
            assertFalse(Files.exists(dest.resolve("audit.log")),
                    "and it did not copy the source home's history into it: a clone that claimed "
                            + "actions it never took would be worse than no log");
            assertTrue(HomeCloner.SKIPPED_ROOT_FILES.contains("audit.log"),
                    "the exclusion is stated once, where both halves read it");
        });

        return suite.runAll();
    }

    /** The log's content, or why there isn't any — a failure has to be readable. */
    private static String dump(Path file) throws IOException {
        return Files.isRegularFile(file)
                ? Files.readString(file)
                : "<no audit.log at " + file + " at all>";
    }

    private static Path auditFile(SkillStore store) {
        return store.root().resolve("audit.log");
    }

    /** Every audit entry whose verb field is {@code sync}. */
    private static List<String> syncLines(SkillStore store) throws IOException {
        Path file = auditFile(store);
        if (!Files.isRegularFile(file)) return List.of();
        return Files.readAllLines(file).stream()
                .filter(line -> {
                    String[] parts = line.split("\t", -1);
                    return parts.length > 1 && parts[1].equals("sync");
                })
                .toList();
    }
}
