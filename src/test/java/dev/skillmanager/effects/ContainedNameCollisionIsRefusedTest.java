package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OUN-2: one name, one copy — a plugin whose contained skill name is already
 * claimed is refused.
 *
 * <p>This gate could not exist before OUN-1. The old refusal only saw names in
 * the four standalone branches; a plugin's contained skills were in no
 * inventory at all, so a plugin carrying a skill named {@code skill-manager}
 * installed cleanly beside the standalone {@code skill-manager} and the name
 * quietly had two answers.
 */
public final class ContainedNameCollisionIsRefusedTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ContainedNameCollisionIsRefusedTest");

        suite.test("a plugin carrying an already-claimed skill name is refused", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-tool");

            InstallUseCase.Report report = install(h.store(), plugin, false);

            assertFalse(Files.exists(h.store().pluginsDir().resolve("acme-plugin")),
                    "the plugin is not committed");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "and the unit that already held the name is untouched");
            assertTrue(report.exitCode() != 0,
                    "the run reports a failure exit code, got " + report.exitCode());
        });

        suite.test("the refusal names both claimants and both ways out", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-tool");

            String out = capture(() -> {
                try {
                    install(h.store(), plugin, false);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });

            assertContains(out, "acme-plugin", "the refusal names the plugin being installed");
            assertContains(out, h.store().skillDir("acme-tool").toString(),
                    "and the path of the unit already holding the name — an operator has to "
                            + "choose between two things, so both have to be on screen");
            assertContains(out, "skill-manager remove acme-tool",
                    "one way out, spelled as a command");
            assertContains(out, "rename the skill inside the plugin", "the other way out");
        });

        // THE CONSTRAINT THIS TICKET WAS GIVEN. Two copies of one name is not
        // a version conflict to be reconciled by preference; it is an
        // ambiguity the search would resolve by directory order. So there is
        // no flag, and `--yes` is not one either: --yes answers policy
        // prompts, and this is not a prompt.
        suite.test("--yes does not get past it", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-tool");

            InstallUseCase.Report report = install(h.store(), plugin, true);

            assertFalse(Files.exists(h.store().pluginsDir().resolve("acme-plugin")),
                    "still refused with yes=true");
            assertTrue(report.exitCode() != 0, "and still a failure exit code");
        });

        // THE skt SHAPE, and it is the reason this test file exists rather
        // than one assertion. 21 homes on the machine this was written on
        // carry a plugin whose entry skill has the plugin's own name; a gate
        // without this exception refuses skt everywhere it is installed.
        suite.test("a plugin's entry skill of the plugin's own name is NOT a collision", () -> {
            TestHarness h = TestHarness.create();
            Path plugin = pluginCarrying(h, "twin-plugin", "twin-plugin");

            InstallUseCase.Report report = install(h.store(), plugin, false);

            assertTrue(Files.isDirectory(h.store().pluginsDir().resolve("twin-plugin")),
                    "one unit under one name installs: " + report.exitCode());
        });

        // Upgrading a plugin means its own contained skills are already on
        // disk under it. Counting those would make the SECOND install of any
        // plugin impossible — a gate that blocks upgrades is worse than the
        // ambiguity it prevents.
        suite.test("a plugin does not collide with its own previous installation", () -> {
            TestHarness h = TestHarness.create();
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-inner");

            assertTrue(install(h.store(), plugin, false).exitCode() == 0,
                    "first install lands");
            assertTrue(Files.isDirectory(
                            h.store().pluginsDir().resolve("acme-plugin/skills/acme-inner")),
                    "and its contained skill is on disk");

            // The top-level "already installed" guard fires first for a
            // re-install, which is a DIFFERENT refusal and the pre-existing
            // one. What must not happen is this gate firing on the plugin's
            // own copy — assert that directly rather than through the CLI.
            assertEquals(1, h.store().containedSkillDirs("acme-inner").size(),
                    "exactly one root holds the contained name, and it is this plugin's");
            assertContains(h.store().containedSkillDirs("acme-inner").get(0).toString(),
                    "plugins/acme-plugin/skills/acme-inner",
                    "so the claimant lookup must skip it when acme-plugin is the installer");
        });

        suite.test("CONTROL: a plugin whose contained name is free installs", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("something-else", UnitKind.SKILL);
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-inner");

            InstallUseCase.Report report = install(h.store(), plugin, false);

            assertEquals(0, report.exitCode(), "no collision, no refusal");
            assertTrue(Files.isDirectory(h.store().pluginsDir().resolve("acme-plugin")),
                    "the plugin is committed");
        });

        return suite.runAll();
    }

    /** A plugin directory carrying exactly one contained skill. */
    private static Path pluginCarrying(TestHarness h, String pluginName, String skillName)
            throws Exception {
        Path root = Files.createTempDirectory("collision-src-").resolve(pluginName);
        Files.createDirectories(root.resolve(".claude-plugin"));
        Files.writeString(root.resolve(".claude-plugin/plugin.json"),
                "{\"name\":\"" + pluginName + "\",\"version\":\"0.1.0\","
                        + "\"description\":\"collision fixture\"}\n");
        Files.writeString(root.resolve("skill-manager-plugin.toml"), """
                [plugin]
                name = "%s"
                version = "0.1.0"
                description = "collision fixture"
                """.formatted(pluginName));
        Path contained = root.resolve("skills").resolve(skillName);
        Files.createDirectories(contained);
        Files.writeString(contained.resolve("SKILL.md"),
                "---\nname: " + skillName + "\ndescription: contained fixture\n---\n\nbody\n");
        Files.writeString(contained.resolve("skill-manager.toml"), """
                [skill]
                name = "%s"
                version = "0.1.0"
                description = "contained fixture"
                """.formatted(skillName));
        return root;
    }

    private static InstallUseCase.Report install(SkillStore store, Path unitDir, boolean yes) {
        var program = InstallUseCase.buildProgram(
                store, null, null, unitDir.toString(), null, yes, false, false, true);
        return new Executor(store, null).runStaged(program).result();
    }

    private static String capture(Runnable body) {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previousOut = System.out;
        java.io.PrintStream previousErr = System.err;
        try (java.io.PrintStream capture = new java.io.PrintStream(buf, true)) {
            System.setOut(capture);
            System.setErr(capture);
            body.run();
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        return buf.toString();
    }
}
