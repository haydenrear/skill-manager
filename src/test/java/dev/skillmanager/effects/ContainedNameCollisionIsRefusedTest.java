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
 * OUN-13: a contained skill is addressed {@code plugin:skill}, so sharing a
 * word with a standalone unit is not a collision.
 *
 * <p>OUN-2 built a gate that REFUSED a plugin whose contained skill name was
 * already claimed, and this file asserted the refusal. The premise was that
 * installing it would give one name two answers. Qualification removed the
 * premise: the standalone is {@code x} and the contained one is
 * {@code p:x}, two names, neither shadowing the other.
 *
 * <p>What the gate still refuses is the thing that would make the rule false
 * — a nested git repository inside a plugin, i.e. a contained skill trying to
 * be independently updatable. That is the invariant "update the plugin, as a
 * whole" rests on, and the reason two plugins may carry a skill of the same
 * name without a duplication problem.
 */
public final class ContainedNameCollisionIsRefusedTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ContainedNameCollisionIsRefusedTest");

        suite.test("a plugin sharing a name with a standalone unit INSTALLS", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-tool");

            InstallUseCase.Report report = install(h.store(), plugin, false);

            assertEquals(0, report.exitCode(), "no refusal: the two have different names");
            assertTrue(Files.isDirectory(h.store().pluginsDir().resolve("acme-plugin")),
                    "the plugin is committed");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "and the standalone that shares the word is untouched");
        });

        suite.test("both are addressable, separately — the whole point", () -> {
            TestHarness h = TestHarness.create();
            h.scaffoldUnitDir("acme-tool", UnitKind.SKILL);
            install(h.store(), pluginCarrying(h, "acme-plugin", "acme-tool"), false);

            assertTrue(h.store().qualifiedSkillDir("acme-plugin:acme-tool").isPresent(),
                    "`acme-plugin:acme-tool` names the contained copy");
            assertContains(
                    h.store().qualifiedSkillDir("acme-plugin:acme-tool").get().toString(),
                    "plugins/acme-plugin/skills/acme-tool",
                    "and it resolves INSIDE the plugin, not to the standalone");
            assertTrue(Files.isDirectory(h.store().skillDir("acme-tool")),
                    "while the bare name still names the standalone");
        });

        suite.test("it SAYS so, because one case hiding in this is wrong", () -> {
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

            assertContains(out, "acme-plugin:acme-tool",
                    "the notice shows the qualified name, which is the answer to the "
                            + "question an operator is about to ask");
            assertContains(out, "skill-manager remove acme-tool",
                    "and the remedy for the case that IS wrong — the same unit twice");
        });

        // TWO PLUGINS, ONE SKILL NAME. Under OUN-2 the second was refused; the
        // resolver's own comment called the fallback "the first in plugin-name
        // order ... still an ambiguity". There is no tiebreak to make now.
        suite.test("two plugins may carry the same skill name", () -> {
            TestHarness h = TestHarness.create();

            assertEquals(0, install(h.store(), pluginCarrying(h, "alpha-plugin", "shared"), false)
                    .exitCode(), "the first lands");
            assertEquals(0, install(h.store(), pluginCarrying(h, "beta-plugin", "shared"), false)
                    .exitCode(), "and so does the second — neither is updatable on its own");

            assertTrue(h.store().qualifiedSkillDir("alpha-plugin:shared").isPresent(),
                    "alpha-plugin:shared resolves");
            assertTrue(h.store().qualifiedSkillDir("beta-plugin:shared").isPresent(),
                    "beta-plugin:shared resolves");
            assertEquals(2, h.store().containedSkillDirs("shared").size(),
                    "the bare name is the ambiguous one, and it is nobody's answer now");
        });

        // THE REFUSAL THIS GATE KEEPS, and it is what makes everything above
        // sound. A nested repo is a contained skill claiming its own upstream.
        suite.test("a nested git repository inside a plugin IS refused", () -> {
            TestHarness h = TestHarness.create();
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-inner");
            Files.createDirectories(plugin.resolve("skills/acme-inner/.git"));
            Files.writeString(plugin.resolve("skills/acme-inner/.git/HEAD"),
                    "ref: refs/heads/main\n");

            InstallUseCase.Report report = install(h.store(), plugin, false);

            assertTrue(report.exitCode() != 0, "refused: a plugin is the unit of change");
            assertFalse(Files.exists(h.store().pluginsDir().resolve("acme-plugin")),
                    "and nothing was committed");
        });

        suite.test("the plugin's OWN .git is not nested — that is just a repository", () -> {
            TestHarness h = TestHarness.create();
            Path plugin = pluginCarrying(h, "acme-plugin", "acme-inner");
            Files.createDirectories(plugin.resolve(".git"));
            Files.writeString(plugin.resolve(".git/HEAD"), "ref: refs/heads/main\n");

            assertEquals(0, install(h.store(), plugin, false).exitCode(),
                    "a plugin that IS a git repository is the normal case");
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
