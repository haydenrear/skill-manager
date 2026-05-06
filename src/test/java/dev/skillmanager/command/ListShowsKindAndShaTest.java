package dev.skillmanager.command;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-14 contract: {@link SkillStore#listInstalledUnits} surfaces
 * both skills and plugins, and the data the {@code list} command renders
 * (kind, sha, source) flows correctly from the installed-record.
 *
 * <p>Doesn't shell out to picocli — exercises the data plumbing the
 * command relies on, which is the part most likely to break across
 * future refactors. Output formatting is shape-preserving (column
 * widths) and visual; column tests are pinned in
 * {@code ListCommand.printf} review.
 */
public final class ListShowsKindAndShaTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ListShowsKindAndShaTest");

        suite.test("listInstalledUnits surfaces skills + plugins, alphabetically", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha-skill");
            installPlugin(h, "beta-plugin");
            installSkill(h, "zeta-skill");

            List<AgentUnit> all = h.store().listInstalledUnits();
            assertEquals(3, all.size(), "all three units listed");
            assertEquals("alpha-skill", all.get(0).name(), "alpha first");
            assertEquals("beta-plugin", all.get(1).name(), "beta second");
            assertEquals("zeta-skill", all.get(2).name(), "zeta last");
            assertEquals(UnitKind.SKILL, all.get(0).kind(), "alpha is skill");
            assertEquals(UnitKind.PLUGIN, all.get(1).kind(), "beta is plugin");
        });

        suite.test("installed-record sha + source flow into list-render data", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "widget");
            // Manually seed the installed-record with a recognizable sha.
            InstalledUnit rec = new InstalledUnit(
                    "widget", "1.2.3", InstalledUnit.Kind.GIT,
                    InstalledUnit.InstallSource.REGISTRY,
                    "https://github.com/foo/widget", "deadbeefcafe", "main",
                    UnitStore.nowIso(), null, UnitKind.SKILL);
            new UnitStore(h.store()).write(rec);

            UnitStore sources = new UnitStore(h.store());
            InstalledUnit read = sources.read("widget").orElseThrow();
            // Truncated sha matches what list renders.
            assertEquals("deadbee", read.gitHash().substring(0, 7), "truncated sha = first 7");
            assertEquals(InstalledUnit.InstallSource.REGISTRY, read.installSource(),
                    "source survives write+read");
        });

        suite.test("missing installed-record renders as '-' (handled by command, not store)", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "widget");
            // No seedUnit / installed-record write — the unit is on disk
            // but not tracked.
            UnitStore sources = new UnitStore(h.store());
            assertTrue(sources.read("widget").isEmpty(),
                    "no installed-record present");
            // The command falls back to "-" for sha/source — exercised in
            // the command code (ListCommand.call), not the data layer.
        });

        suite.test("plugin sha read from installed-record (kind-aware)", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget");
            InstalledUnit rec = new InstalledUnit(
                    "widget", "0.4.2", InstalledUnit.Kind.GIT,
                    InstalledUnit.InstallSource.GIT,
                    "git@github.com:foo/widget.git", "facef00d1234", "v0.4.2",
                    UnitStore.nowIso(), null, UnitKind.PLUGIN);
            new UnitStore(h.store()).write(rec);

            InstalledUnit read = new UnitStore(h.store()).read("widget").orElseThrow();
            assertEquals(UnitKind.PLUGIN, read.unitKind(), "plugin record");
            assertEquals("facef00", read.gitHash().substring(0, 7), "plugin sha truncated");
        });

        return suite.runAll();
    }

    private static void installSkill(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("list-test-skill-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, DepSpec.empty());
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(u.sourcePath(), h.store().skillDir(name));
    }

    private static void installPlugin(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("list-test-plugin-");
        UnitFixtures.scaffoldPlugin(tmp, name, DepSpec.empty(),
                new ContainedSkillSpec(name + "-impl", DepSpec.empty()));
        Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(tmp.resolve(name), h.store().pluginsDir().resolve(name));
    }
}
