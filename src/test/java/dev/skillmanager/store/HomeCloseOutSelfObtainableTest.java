package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * DEF-101 — <b>"the destination lacks this and cannot get it" is not the same
 * sentence as "the destination lacks this and its own manifest declares it".</b>
 *
 * <h2>The measurement</h2>
 *
 * <p>HIS-20's close-out: {@code exit 1}, {@code safe: false}, two blockers —
 * {@code skill:skill-manager} and {@code plugin:skt}, both {@code new}, both
 * "not in the destination home". Both were unmodified checkouts at published
 * {@code main} SHAs, and the destination's own {@code skill-project.toml}
 * declares both. <b>Nothing would have been destroyed.</b> The gate had no way
 * to say so, so it blocked — and the operator's only route past it was to sync
 * bytes the destination can fetch, out of a home about to be deleted.
 *
 * <h2>Why the guard case matters more than the fix case</h2>
 *
 * <p>The cheapest way to make {@code CASE 1} pass is to stop blocking on
 * {@code NEW}, which would delete the gate's entire reason to exist. So
 * {@code CASE 2} and {@code CASE 3} pin the two halves that must not move: an
 * undeclared new unit still blocks, and a declared unit the worktree has
 * <em>edited</em> still blocks. Between them they say the exemption is about
 * provenance, not about the word {@code NEW}.
 */
public final class HomeCloseOutSelfObtainableTest {

    private static final String DECLARED = "declared-unit";
    private static final String AUTHORED = "authored-unit";

    public static int run() throws Exception {
        return Tests.suite("HomeCloseOutSelfObtainableTest")

                // ------------------------------------------------------- CASE 1: the fix

                .test("a new unit the destination's own manifest declares does not block teardown", () -> {
                    Fixture f = Fixture.create("obtainable");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(DECLARED);

                    // PRECONDITIONS. Both asserted, because either one being
                    // false makes CASE 1 pass for the wrong reason.
                    assertFalse(Files.isDirectory(f.dest.skillDir(DECLARED)),
                            "fixture precondition: the destination really does not hold it");
                    assertTrue(Files.isRegularFile(f.repoRoot.resolve("skill-project.toml")),
                            "fixture precondition: the destination's manifest exists beside its home");

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertTrue(verdict.safe(),
                            "a unit the destination declares at a published source, unmodified in "
                                    + "the worktree, is not work that exists nowhere else");
                    assertEquals(0, verdict.exitCode(), "so the teardown is not refused");
                    assertEquals(0, verdict.blockers().size(),
                            "and it is not on the fix list: " + HomeCloseOut.render(verdict));

                    // NOT SILENT. A fix that simply stopped reporting the unit
                    // would pass every assertion above and be a regression.
                    assertEquals(1, verdict.selfObtainable().size(),
                            "the decision is reported, not swallowed");
                    HomeCloseOut.SelfObtainable obtainable = verdict.selfObtainable().get(0);
                    assertEquals("skill:" + DECLARED, obtainable.label(), "naming the unit");
                    assertEquals("github:example/declared-unit", obtainable.source(),
                            "and where the destination said it comes from");
                    assertContains(obtainable.remedy(), "project resolve",
                            "and the command that obtains it");
                    assertContains(String.join("\n", HomeCloseOut.render(verdict)), "obtainable",
                            "and the human rendering says so too");

                    // And it still WROTE NOTHING: close-out is a read-only gate,
                    // and an exemption must not become a quiet copy.
                    assertFalse(Files.isDirectory(f.dest.skillDir(DECLARED)),
                            "clearing the gate did not materialize anything into the destination");
                })

                // ----------------------------------- CASE 2: guard — undeclared still blocks

                .test("a new unit nothing declares still blocks, with the same remedy", () -> {
                    Fixture f = Fixture.create("undeclared");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(AUTHORED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "the exemption is about the manifest, not about the word NEW — a unit "
                                    + "the agent authored in the worktree exists nowhere else");
                    assertEquals(1, verdict.blockers().size(), "exactly the unit at risk");
                    assertEquals("skill:" + AUTHORED, verdict.blockers().get(0).label(),
                            "naming it");
                    assertContains(verdict.blockers().get(0).remedy(), "home sync",
                            "with the unchanged remedy");
                    assertEquals(0, verdict.selfObtainable().size(),
                            "and nothing was exempted");
                })

                // ------------------------- CASE 3: guard — declared but locally edited blocks

                .test("a declared unit the worktree has edited is not obtainable and still blocks", () -> {
                    Fixture f = Fixture.create("edited");
                    f.declare(DECLARED, "skills", "github:example/declared-unit");
                    f.putInWorktreeOnly(DECLARED);
                    f.markLocallyModified(DECLARED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "'the destination can fetch this' is false the moment the worktree's "
                                    + "copy stops being what the published ref holds");
                    assertEquals(0, verdict.selfObtainable().size(),
                            "so it is not exempted");
                    assertEquals(1, verdict.blockers().size(), "and it blocks");
                    assertEquals("skill:" + DECLARED, verdict.blockers().get(0).label(),
                            "naming the unit whose edit would be lost");
                })

                // ------------------- CASE 4: guard — no manifest beside the destination

                .test("a destination with no manifest of its own exempts nothing", () -> {
                    Fixture f = Fixture.create("nomanifest");
                    Files.deleteIfExists(f.repoRoot.resolve("skill-project.toml"));
                    f.putInWorktreeOnly(DECLARED);

                    HomeCloseOut.Verdict verdict = HomeCloseOut.inspect(f.source, f.dest);

                    assertFalse(verdict.safe(),
                            "with nothing declaring it, a root home destination blocks exactly as "
                                    + "it always did — the claim has to come from the destination");
                    assertEquals(0, verdict.selfObtainable().size(), "and nothing is exempted");
                })

                .runAll();
    }

    // ----------------------------------------------------------------- fixture

    /**
     * A worktree home and a destination "project" home laid out the way the
     * product lays them out: the destination home is {@code <repoRoot>/.skill-manager},
     * beside {@code <repoRoot>/skill-project.toml}. That geometry is the whole
     * mechanism under test, so the fixture builds it rather than stubbing it.
     */
    private static final class Fixture {
        final Path repoRoot;
        final SkillStore source;
        final SkillStore dest;

        private Fixture(Path repoRoot, SkillStore source, SkillStore dest) {
            this.repoRoot = repoRoot;
            this.source = source;
            this.dest = dest;
        }

        static Fixture create(String label) throws IOException {
            Path root = Files.createTempDirectory("close-out-obtainable-" + label + "-");
            Path repoRoot = Files.createDirectories(root.resolve("repo"));
            SkillStore source = init(root.resolve("worktree/.skill-manager"));
            SkillStore dest = init(repoRoot.resolve(".skill-manager"));
            Files.writeString(repoRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "close-out-obtainable"
                    """);
            return new Fixture(repoRoot, source, dest);
        }

        void declare(String unit, String table, String source) throws IOException {
            Files.writeString(repoRoot.resolve("skill-project.toml"), """
                    [project]
                    name = "close-out-obtainable"

                    [%s.%s]
                    source = "%s"
                    """.formatted(table, unit, source));
        }

        /**
         * A unit the worktree home has and the destination does not: status NEW.
         *
         * <p>The clone baseline is recorded, because that is what
         * {@code home clone} does at the moment a ticket worktree's home is
         * created, and it is what the measured HIS-20 case had. Without it every
         * unit reads as locally modified — the record is missing, so provenance
         * cannot be shown — and CASE 2 would then be green for a reason that has
         * nothing to do with the manifest, which is mechanism B.
         */
        void putInWorktreeOnly(String unit) throws IOException {
            UnitFixtures.scaffoldSkill(source.skillsDir(), unit, DepSpec.empty());
            dev.skillmanager.bindings.ChildHomeMaterializer.recordCloneBaselines(source);
            assertFalse(new dev.skillmanager.bindings.ChildHomeMaterializer(dest, source)
                            .isLocallyModified(unit, dev.skillmanager.model.UnitKind.SKILL),
                    "fixture precondition: with a baseline recorded the worktree copy reads as "
                            + "pristine, so the only variable left is what the manifest declares");
        }

        /**
         * Make the worktree's copy carry work of its own.
         *
         * <p>Written through the materialization record rather than by editing
         * bytes at random, because {@code isLocallyModified} is defined against
         * that record: a fixture that edited the tree without a record would
         * report "not modified" and the case would pass vacuously — mechanism B.
         * The record is asserted to take effect below.
         */
        void markLocallyModified(String unit) throws IOException {
            dev.skillmanager.bindings.ChildHomeMaterializer worktreeSide =
                    new dev.skillmanager.bindings.ChildHomeMaterializer(dest, source);
            Path unitDir = source.skillDir(unit);
            Files.writeString(unitDir.resolve("SKILL.md"),
                    Files.readString(unitDir.resolve("SKILL.md")) + "\nAGENT EDIT\n");
            assertTrue(worktreeSide.isLocallyModified(unit, dev.skillmanager.model.UnitKind.SKILL),
                    "fixture precondition: the worktree copy really does read as locally modified");
        }

        private static SkillStore init(Path root) throws IOException {
            SkillStore store = new SkillStore(root);
            store.init();
            return store;
        }
    }
}
