package dev.skillmanager.store;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code home sync --unit <name>} — narrowing the reconcile to one unit.
 *
 * <p>Why this exists, and it is not performance. {@code skt publish <unit>}
 * runs a {@code home sync --merge} one tier up first, because that is what
 * makes a home-edited skill survive teardown. The sync carried EVERY unit, so
 * one unrelated unit in conflict failed the whole command and the publish
 * stopped — measured on this repository's project home, which was holding
 * three units in {@code MERGE_CONFLICT} from an unrelated issue while an agent
 * tried to publish a fourth. The edit that could not be published is the one
 * that gets lost, so the assertions below are about the unrelated unit's bytes
 * being untouched and about the named unit actually moving, not about counts.
 *
 * <p>The second half is the failure mode narrowing INTRODUCES: a filter that
 * matches nothing produces an empty unit list, and an empty unit list is
 * indistinguishable in every report from "the two homes agree". That is the
 * same confusion {@link NotAHomeException} exists to remove one level up, so a
 * name neither home holds is refused rather than reported clean.
 */
public final class HomeSyncUnitFilterTest {

    /** The unit an agent edited and is trying to publish. */
    private static final String EDITED = "edited-skill";

    /** An unrelated unit in conflict — the one that used to block the publish. */
    private static final String BLOCKED = "blocked-skill";

    public static int run() throws Exception {
        return Tests.suite("HomeSyncUnitFilterTest")

                .test("a whole-home sync fails because of an UNRELATED unit — the defect", () -> {
                    Homes homes = conflictedNeighbour("defect");

                    HomeSync.Report report = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, false));

                    assertFalse(report.clean(),
                            "the whole-home pass is not clean, so `home sync` exits 1 and "
                                    + "`skt publish` stops before publishing anything");
                    assertEquals(List.of(BLOCKED),
                            report.conflicted().stream().map(u -> u.unitName()).toList(),
                            "and the only thing wrong with it is a unit nobody asked about");
                })

                .test("--unit reconciles only that unit, and the neighbour is untouched", () -> {
                    Homes homes = conflictedNeighbour("targeted");
                    String neighbourBefore =
                            ChildHomeMaterializer.treeDigest(homes.dest.skillDir(BLOCKED));

                    HomeSync.Report report = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, false, EDITED));

                    assertEquals(List.of(EDITED),
                            report.units().stream().map(u -> u.unitName()).toList(),
                            "exactly one unit was visited");
                    assertEquals(SyncStatus.UPDATED, report.units().get(0).status(),
                            "and it actually moved");
                    assertEquals("PUBLISH ME\n",
                            Files.readString(homes.dest.skillDir(EDITED).resolve("SKILL.md")),
                            "the bytes being published arrived");
                    assertTrue(report.clean(),
                            "the pass is clean, so `home sync` exits 0 and the publish proceeds");
                    assertEquals(neighbourBefore,
                            ChildHomeMaterializer.treeDigest(homes.dest.skillDir(BLOCKED)),
                            "the conflicted neighbour was not visited and not written");
                })

                .test("a targeted report says so, so its zeroes are not read as a clean home", () -> {
                    Homes homes = conflictedNeighbour("labelled");

                    HomeSync.Report targeted = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, false, EDITED));
                    assertTrue(targeted.targeted(), "the report knows it was narrowed");
                    assertEquals(EDITED, targeted.unit(), "and carries the name it was narrowed to");

                    HomeSync.Report whole = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, false));
                    assertFalse(whole.targeted(), "a whole-home pass is not targeted");
                    assertEquals(null, whole.unit(), "and names no unit");
                })

                .test("a --unit no home holds is REFUSED, not reported clean", () -> {
                    Homes homes = conflictedNeighbour("unknown");
                    String before = homeDigest(homes.dest.root());

                    try {
                        HomeSync.run(homes.source, homes.dest,
                                new HomeSync.Options(true, false, "no-such-unit"));
                        throw new AssertionError(
                                "a name neither home holds reported success for work that did "
                                        + "not happen — which is the whole failure mode");
                    } catch (HomeSync.UnknownUnitException expected) {
                        assertEquals("no-such-unit", expected.unit(), "the refusal names the unit");
                        assertContains(expected.getMessage(), "nothing would have been reconciled",
                                "and says what the alternative would have been");
                    }

                    assertEquals(before, homeDigest(homes.dest.root()),
                            "the refusal wrote nothing at all — not the lock file, not a record");
                })

                .test("no --unit is still the whole home, unchanged for every existing caller", () -> {
                    Homes homes = conflictedNeighbour("whole");

                    HomeSync.Report twoArg = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, true));
                    HomeSync.Report nullArg = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, true, null));
                    HomeSync.Report blankArg = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, true, "  "));

                    for (HomeSync.Report report : List.of(twoArg, nullArg, blankArg)) {
                        assertEquals(List.of(BLOCKED, EDITED),
                                report.units().stream().map(u -> u.unitName()).sorted().toList(),
                                "every unit either home holds is still visited");
                        assertFalse(report.targeted(), "and the pass is not marked targeted");
                    }
                })

                .test("a targeted --dry-run reports one unit and writes nothing", () -> {
                    Homes homes = conflictedNeighbour("dry");
                    String before = homeDigest(homes.dest.root());

                    HomeSync.Report report = HomeSync.run(homes.source, homes.dest,
                            new HomeSync.Options(true, true, EDITED));

                    assertEquals(List.of(EDITED),
                            report.units().stream().map(u -> u.unitName()).toList(),
                            "only the named unit is planned");
                    assertEquals(SyncStatus.UPDATED, report.units().get(0).status(),
                            "and the plan says it would move");
                    assertEquals(before, homeDigest(homes.dest.root()),
                            "#42's contract survives narrowing: a dry run puts nothing on disk");
                })

                .test("--unit matches the NAME across kinds, not one kind", () -> {
                    Path root = Files.createTempDirectory("home-sync-kinds-");
                    SkillStore source = store(root.resolve("source"));
                    SkillStore dest = store(root.resolve("dest"));
                    UnitFixtures.scaffoldSkill(source.skillsDir(), "dual", DepSpec.empty());
                    UnitFixtures.scaffoldPlugin(source.pluginsDir(), "dual", DepSpec.empty());
                    UnitFixtures.scaffoldSkill(source.skillsDir(), "other", DepSpec.empty());

                    HomeSync.Report report = HomeSync.run(source, dest,
                            new HomeSync.Options(false, false, "dual"));

                    assertEquals(2, report.units().size(),
                            "both kinds carrying the name are reconciled: " + report.units());
                    assertTrue(report.units().stream().allMatch(u -> u.unitName().equals("dual")),
                            "and nothing else is");
                })

                .runAll();
    }

    // ------------------------------------------------------------- helpers

    private record Homes(SkillStore source, SkillStore dest) {}

    /**
     * A destination holding two units: one an agent edited upstream and wants
     * published, and one in genuine conflict that has nothing to do with it.
     *
     * <p>The conflict is built the documented way — sync once so both sides
     * share a per-file baseline, then move the same file on both sides — so
     * this is a real {@code CONFLICTED}, not a shape that only looks like one.
     */
    private static Homes conflictedNeighbour(String label) throws IOException {
        Path root = Files.createTempDirectory("home-sync-unit-" + label + "-");
        SkillStore source = store(root.resolve("source"));
        SkillStore dest = store(root.resolve("dest"));
        UnitFixtures.scaffoldSkill(source.skillsDir(), EDITED, DepSpec.empty());
        UnitFixtures.scaffoldSkill(source.skillsDir(), BLOCKED, DepSpec.empty());
        write(source.skillDir(BLOCKED).resolve("both.md"), "base\n");
        HomeSync.run(source, dest, new HomeSync.Options(false, false));

        write(dest.skillDir(BLOCKED).resolve("both.md"), "DESTINATION VERSION\n");
        write(source.skillDir(BLOCKED).resolve("both.md"), "SOURCE VERSION\n");
        write(source.skillDir(EDITED).resolve("SKILL.md"), "PUBLISH ME\n");
        return new Homes(source, dest);
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    /** Digest of a whole home directory, lock file and records included. */
    private static String homeDigest(Path root) throws IOException {
        return ChildHomeMaterializer.entryDigests(root, Set.of()).toString();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
