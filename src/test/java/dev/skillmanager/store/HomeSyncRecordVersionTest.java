package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeMaterializer.SyncStatus;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * HP-4 (#298): a {@code home sync} that moves a unit's bytes restates the
 * destination's {@code installed/<unit>.json} version from the manifest it now
 * holds. Before, the tree reconcile never touched the installed record, so a
 * home synced to 0.2.0 kept reporting 0.1.0.
 */
public final class HomeSyncRecordVersionTest {

    private static final String UNIT = "rv-skill";

    public static int run() throws Exception {
        return Tests.suite("HomeSyncRecordVersionTest")

                .test("an UPDATED unit's installed record follows the manifest", () -> {
                    Path root = Files.createTempDirectory("home-sync-rv-");
                    SkillStore source = store(root.resolve("source"));
                    SkillStore dest = store(root.resolve("dest"));
                    skill(source, "0.1.0");
                    HomeSync.run(source, dest, HomeSync.Options.defaults());
                    new UnitStore(dest).write(record("0.1.0"));

                    skill(source, "0.2.0");
                    HomeSync.Report dry = HomeSync.run(source, dest, new HomeSync.Options(false, true));
                    assertEquals(SyncStatus.UPDATED, dry.units().get(0).status(), "would update");
                    assertEquals("0.1.0", recorded(dest), "a dry run writes no record");

                    HomeSync.Report report = HomeSync.run(source, dest, HomeSync.Options.defaults());
                    assertEquals(SyncStatus.UPDATED, report.units().get(0).status(), "a fast-forward");
                    assertEquals("0.2.0", recorded(dest), "the record now names the synced manifest");
                })

                .test("a held-back unit's record is left alone", () -> {
                    Path root = Files.createTempDirectory("home-sync-rv-held-");
                    SkillStore source = store(root.resolve("source"));
                    SkillStore dest = store(root.resolve("dest"));
                    skill(source, "0.1.0");
                    HomeSync.run(source, dest, HomeSync.Options.defaults());
                    new UnitStore(dest).write(record("0.1.0"));
                    // A local edit in the destination, then an upstream bump: held back.
                    Files.writeString(dest.skillDir(UNIT).resolve("SKILL.md"),
                            "---\nname: " + UNIT + "\ndescription: local edit\n---\nlocal\n");
                    skill(source, "0.2.0");
                    HomeSync.Report report = HomeSync.run(source, dest, HomeSync.Options.defaults());
                    assertEquals(SyncStatus.HELD_BACK, report.units().get(0).status(), "held back");
                    assertEquals("0.1.0", recorded(dest), "nothing moved, so nothing is restated");
                })

                .test("a NEW unit in a home with no record gets no invented record", () -> {
                    Path root = Files.createTempDirectory("home-sync-rv-new-");
                    SkillStore source = store(root.resolve("source"));
                    SkillStore dest = store(root.resolve("dest"));
                    skill(source, "0.1.0");
                    HomeSync.run(source, dest, HomeSync.Options.defaults());
                    assertTrue(new UnitStore(dest).read(UNIT).isEmpty(), "no record created");
                })

                .runAll();
    }

    private static SkillStore store(Path root) throws IOException {
        SkillStore store = new SkillStore(root);
        store.init();
        return store;
    }

    private static void skill(SkillStore home, String version) throws IOException {
        Path dir = home.skillDir(UNIT);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + UNIT + "\ndescription: release " + version + "\n---\nbody\n");
        Files.writeString(dir.resolve("skill-manager.toml"),
                "[skill]\nname = \"" + UNIT + "\"\nversion = \"" + version + "\"\n"
                        + "description = \"fixture\"\n");
    }

    private static InstalledUnit record(String version) {
        return new InstalledUnit(UNIT, version, InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE, "/nowhere/" + UNIT, null, null,
                "2026-01-01T00:00:00Z", List.of(), UnitKind.SKILL);
    }

    private static String recorded(SkillStore home) {
        return new UnitStore(home).read(UNIT).map(InstalledUnit::version).orElse(null);
    }
}
