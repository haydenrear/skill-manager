package dev.skillmanager.command;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-14: {@code show <plugin>} lists contained skills (just names —
 * they aren't separately addressable) plus unioned effective deps with
 * per-source attribution. The test pins the underlying data view —
 * what {@link PluginParser#load} surfaces and how the attribution
 * mapping resolves — without exercising stdout.
 */
public final class ShowPluginListsContainedSkillsTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ShowPluginListsContainedSkillsTest");

        suite.test("plugin contained skills surface from PluginParser.load", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("summarize-repo", DepSpec.empty()),
                    new ContainedSkillSpec("diff-narrative", DepSpec.empty()));

            PluginUnit p = PluginParser.load(h.store().pluginsDir().resolve("widget"));
            assertEquals(2, p.containedSkills().size(), "two contained");
            // Order is filesystem-iteration order — sorted alphabetically by the parser.
            assertEquals("diff-narrative", p.containedSkills().get(0).name(), "alphabetical first");
            assertEquals("summarize-repo", p.containedSkills().get(1).name(), "alphabetical second");
        });

        suite.test("contained skill's CLI dep surfaces in plugin's unioned cliDependencies", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("summarize-repo",
                            DepSpec.of().cli("pip:cowsay==6.0").build()));

            PluginUnit p = PluginParser.load(h.store().pluginsDir().resolve("widget"));
            assertEquals(1, p.cliDependencies().size(), "one CLI dep unioned in");
            assertEquals("cowsay", p.cliDependencies().get(0).name(), "name");
        });

        suite.test("plugin-level + contained-skill MCP deps both unioned", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget",
                    DepSpec.of().mcp("plugin-level-srv").build(),
                    new ContainedSkillSpec("summarize-repo",
                            DepSpec.of().mcp("contained-srv").build()));

            PluginUnit p = PluginParser.load(h.store().pluginsDir().resolve("widget"));
            List<String> names = new java.util.ArrayList<>();
            for (var d : p.mcpDependencies()) names.add(d.name());
            assertEquals(2, names.size(), "two MCP deps unioned");
            assertTrue(names.contains("plugin-level-srv"), "plugin-level dep");
            assertTrue(names.contains("contained-srv"), "contained-skill dep");
        });

        suite.test("attribution: plugin-level dep → 'plugin level', contained → 'skills/<name>'", () -> {
            // Spot-check the show-command attribution logic by mirroring it
            // here: walk contained skills first; fall back to plugin level
            // when no contained skill claims the dep.
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget",
                    DepSpec.of().mcp("plugin-level-srv").build(),
                    new ContainedSkillSpec("summarize-repo",
                            DepSpec.of().mcp("contained-srv").build()));

            PluginUnit p = PluginParser.load(h.store().pluginsDir().resolve("widget"));
            // Contained-srv must trace to skills/summarize-repo; plugin-level
            // must trace to "plugin level" (no contained-skill claimant).
            String containedSource = null;
            String pluginLevelSource = null;
            for (var dep : p.mcpDependencies()) {
                String at = "plugin level";
                for (var cs : p.containedSkills()) {
                    for (var d : cs.mcpDependencies()) {
                        if (d.name().equals(dep.name())) at = "skills/" + cs.name();
                    }
                }
                if ("contained-srv".equals(dep.name())) containedSource = at;
                if ("plugin-level-srv".equals(dep.name())) pluginLevelSource = at;
            }
            assertEquals("skills/summarize-repo", containedSource, "contained dep attribution");
            assertEquals("plugin level", pluginLevelSource, "plugin-level dep attribution");
        });

        return suite.runAll();
    }

    private static void installPlugin(TestHarness h, String name, DepSpec pluginLevel,
                                       ContainedSkillSpec... contained) throws Exception {
        Path tmp = Files.createTempDirectory("show-plugin-test-");
        UnitFixtures.scaffoldPlugin(tmp, name, pluginLevel, contained);
        Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(tmp.resolve(name), h.store().pluginsDir().resolve(name));
    }
}
