package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code home close-out} announced a write it did not perform.
 *
 * <h2>The behaviour was right and the sentence was false</h2>
 *
 * <p>The gate is documented "Writes nothing; safe to run repeatedly", and it
 * genuinely writes nothing — the project home was snapshotted immediately after
 * a run and was byte-clean, with the instrument separately proven able to
 * detect a content change, a new file and a bare {@code touch}. What it printed
 * was {@code updated skill:<unit> — refreshed from the source}: the past tense,
 * from a command that had not touched the unit. An operator reading that
 * concludes the blocker beside it has already been remediated, and removes the
 * worktree — which is the exact loss the gate exists to prevent, reached
 * through the gate rather than around it.
 *
 * <p>{@link SyncStatus} is shared between the run that writes and the dry run
 * that plans, so the tense cannot come from the status. It comes from the
 * caller, which is the only thing that knows. Issue #133.
 *
 * <p>Every assertion here is paired with a measurement of the destination
 * bytes, because "the message is wrong" and "the write is missing" print the
 * same and are opposite defects.
 */
public final class HomeCloseOutTenseTest {

    private static final String UNIT = "tense-skill";

    public static int run() throws Exception {
        return Tests.suite("HomeCloseOutTenseTest")

                .test("close-out never claims a unit was updated, and never updates one", () -> {
                    Homes homes = Homes.create("updated");
                    Files.writeString(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    // Land V1 in the destination so the next pass is a
                    // fast-forward — the status that produced the false claim.
                    HomeSync.run(homes.source, homes.dest, new HomeSync.Options(false, false));
                    Files.writeString(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");

                    String before = ChildHomeMaterializer.treeDigest(homes.destUnit());
                    HomeCloseOut.Verdict verdict =
                            HomeCloseOut.inspect(homes.source, homes.dest);
                    List<String> lines = HomeCloseOut.render(verdict);
                    String text = String.join("\n", lines);

                    assertEquals(ChildHomeMaterializer.SyncStatus.UPDATED,
                            verdict.units().get(0).status(),
                            "the verdict is still the fast-forward one: " + text);
                    assertTrue(!text.contains("  updated "),
                            "the past tense is gone from a command that writes nothing:\n" + text);
                    assertEquals("would-update", verdict.units().get(0).statusLabel(false),
                            "the gate labels the outcome with what it can actually say");
                    assertContains(text, "would-update",
                            "and prints that label rather than the bare status name");
                    assertTrue(!text.contains("refreshed from the source"),
                            "the detail states a condition, not a completed act:\n" + text);
                    assertEquals(before, ChildHomeMaterializer.treeDigest(homes.destUnit()),
                            "and the destination really was untouched, so the message was the "
                                    + "only thing wrong");
                })

                .test("a status that is true of both runs keeps its plain word", () -> {
                    // The companion that shows the prefix is not blanket
                    // decoration: `conflicted` describes the two homes right
                    // now and is equally true whether or not anything wrote.
                    Homes homes = Homes.create("held");
                    Files.writeString(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    HomeSync.run(homes.source, homes.dest, new HomeSync.Options(false, false));
                    Files.writeString(homes.destUnit().resolve("SKILL.md"), "AGENT WORK\n");
                    Files.writeString(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");

                    String text = String.join("\n",
                            HomeCloseOut.render(HomeCloseOut.inspect(homes.source, homes.dest)));

                    assertContains(text, "conflicted", "an unchanged-by-tense status is unprefixed");
                    assertTrue(!text.contains("would-conflicted"),
                            "and is not decorated for the sake of it:\n" + text);
                })

                .test("a real `home sync` still reports in the past tense", () -> {
                    // Without this the fix could be "always say would-", which
                    // would make the applying command lie in the other
                    // direction.
                    Homes homes = Homes.create("applied");
                    Files.writeString(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V1\n");
                    HomeSync.run(homes.source, homes.dest, new HomeSync.Options(false, false));
                    Files.writeString(homes.sourceUnit().resolve("SKILL.md"), "SOURCE V2\n");

                    HomeSync.Report applied =
                            HomeSync.run(homes.source, homes.dest, new HomeSync.Options(false, false));

                    assertEquals("updated", applied.units().get(0).statusLabel(true),
                            "a run that wrote says so");
                    assertEquals("SOURCE V2\n", Files.readString(homes.destUnit().resolve("SKILL.md")),
                            "and it did write");
                })

                .runAll();
    }

    private record Homes(SkillStore source, SkillStore dest) {

        static Homes create(String label) throws IOException {
            Path root = Files.createTempDirectory("close-out-tense-" + label + "-");
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
}
