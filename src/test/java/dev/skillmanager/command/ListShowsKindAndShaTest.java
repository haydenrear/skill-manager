package dev.skillmanager.command;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ConflictPolicy;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.bindings.ProjectionLedger;
import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.ListCommand;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-14 / ticket-47+48 contract: {@link SkillStore#listInstalledUnits}
 * surfaces every installed unit kind, and the data the {@code list}
 * command renders (kind, sha, source, binding count) flows correctly
 * from the installed-record and projection ledger.
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

        suite.test("listInstalledUnits surfaces skills + plugins + docs + harnesses, alphabetically", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha-skill");
            installPlugin(h, "beta-plugin");
            installDocRepo(h, "gamma-docs");
            installHarness(h, "reviewer-harness");
            installSkill(h, "zeta-skill");

            List<AgentUnit> all = h.store().listInstalledUnits().units();
            assertEquals(5, all.size(), "all five units listed");
            assertEquals("alpha-skill", all.get(0).name(), "alpha first");
            assertEquals("beta-plugin", all.get(1).name(), "beta second");
            assertEquals("gamma-docs", all.get(2).name(), "gamma third");
            assertEquals("reviewer-harness", all.get(3).name(), "harness fourth");
            assertEquals("zeta-skill", all.get(4).name(), "zeta last");
            assertEquals(UnitKind.SKILL, all.get(0).kind(), "alpha is skill");
            assertEquals(UnitKind.PLUGIN, all.get(1).kind(), "beta is plugin");
            assertEquals(UnitKind.DOC, all.get(2).kind(), "gamma is doc");
            assertEquals(UnitKind.HARNESS, all.get(3).kind(), "reviewer is harness");
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

        suite.test("list command renders binding counts and non-skill kinds", () -> {
            TestHarness h = TestHarness.create();
            installDocRepo(h, "team-prompts");
            installHarness(h, "reviewer-harness");
            new UnitStore(h.store()).write(new InstalledUnit(
                    "reviewer-harness", "0.1.0", InstalledUnit.Kind.LOCAL_DIR,
                    InstalledUnit.InstallSource.LOCAL_FILE,
                    "fixture", null, null, UnitStore.nowIso(), null, UnitKind.HARNESS));

            BindingStore bs = new BindingStore(h.store());
            bs.write(new ProjectionLedger("team-prompts", List.of(
                    binding("b1", "team-prompts", UnitKind.DOC),
                    binding("b2", "team-prompts", UnitKind.DOC))));

            PrintStream original = System.out;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(buf));
                new ListCommand(h.store()).call();
            } finally {
                System.setOut(original);
            }

            String out = buf.toString();
            assertContains(out, "BINDINGS", "header includes binding count");
            assertContains(out, "team-prompts", "doc row present");
            assertContains(out, "doc", "doc kind present");
            assertContains(out, "reviewer-harness", "harness row present");
            assertContains(out, "harness", "harness kind present");
            assertContains(out, "0.1.0      2", "doc row reports two bindings");
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

    private static void installDocRepo(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("list-test-doc-");
        Path md = tmp.resolve("claude-md");
        Files.createDirectories(md);
        Files.writeString(md.resolve("review-stance.md"), "review body\n");
        Files.writeString(tmp.resolve("skill-manager.toml"),
                "[doc-repo]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "[[sources]]\n"
                        + "file = \"claude-md/review-stance.md\"\n");
        Fs.ensureDir(h.store().docsDir());
        Fs.copyRecursive(tmp, h.store().docsDir().resolve(name));
    }

    private static void installHarness(TestHarness h, String name) throws Exception {
        Path tmp = Files.createTempDirectory("list-test-harness-");
        Files.writeString(tmp.resolve("harness.toml"),
                "[harness]\n"
                        + "name = \"" + name + "\"\n"
                        + "version = \"0.1.0\"\n\n"
                        + "units = [\"skill:alpha-skill\"]\n"
                        + "docs = [\"doc:team-prompts/review-stance\"]\n");
        Fs.ensureDir(h.store().harnessesDir());
        Fs.copyRecursive(tmp, h.store().harnessesDir().resolve(name));
    }

    private static Binding binding(String id, String unitName, UnitKind kind) {
        Path root = Path.of("/tmp/list-command-bindings");
        return new Binding(
                id,
                unitName,
                kind,
                null,
                root,
                ConflictPolicy.ERROR,
                BindingStore.nowIso(),
                BindingSource.EXPLICIT,
                List.of(new Projection(id, root.resolve(unitName), root.resolve("dest-" + id),
                        ProjectionKind.SYMLINK, null)));
    }
}
