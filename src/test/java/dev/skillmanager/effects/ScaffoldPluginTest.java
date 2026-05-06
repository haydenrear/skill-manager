package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.PluginParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-13: {@link SkillEffect.ScaffoldPlugin} writes the file map into
 * the supplied dir. Mirrors the existing {@link SkillEffect.ScaffoldSkill}
 * shape, with parent-dir creation for paths like {@code skills/.gitkeep}
 * (the bare skill scaffold never had nested paths to write).
 */
public final class ScaffoldPluginTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ScaffoldPluginTest");

        suite.test("scaffold writes plugin.json + skill-manager-plugin.toml + skills/.gitkeep", () -> {
            TestHarness h = TestHarness.create();
            Path dir = Files.createTempDirectory("scaffold-plugin-").resolve("widget");
            Map<String, String> files = new LinkedHashMap<>();
            files.put(".claude-plugin/plugin.json", "{ \"name\": \"widget\" }\n");
            files.put("skill-manager-plugin.toml", "[plugin]\nname = \"widget\"\n");
            files.put("skills/.gitkeep", "");

            EffectReceipt r = h.run(new SkillEffect.ScaffoldPlugin(dir, "widget", files));
            assertEquals(EffectStatus.OK, r.status(), "OK");

            assertTrue(Files.isRegularFile(dir.resolve(".claude-plugin/plugin.json")),
                    "plugin.json written");
            assertTrue(Files.isRegularFile(dir.resolve("skill-manager-plugin.toml")),
                    "plugin toml written");
            assertTrue(Files.isRegularFile(dir.resolve("skills/.gitkeep")),
                    "skills/.gitkeep written (parent dir created)");
        });

        suite.test("scaffold parent dirs created for nested paths", () -> {
            TestHarness h = TestHarness.create();
            Path dir = Files.createTempDirectory("scaffold-plugin-nested-").resolve("widget");
            Map<String, String> files = new LinkedHashMap<>();
            files.put("a/b/c/d.txt", "deep");

            EffectReceipt r = h.run(new SkillEffect.ScaffoldPlugin(dir, "widget", files));
            assertEquals(EffectStatus.OK, r.status(), "OK");
            assertEquals("deep", Files.readString(dir.resolve("a/b/c/d.txt")),
                    "deep nested file content");
        });

        suite.test("scaffolded plugin parses cleanly via PluginParser", () -> {
            // Round-trip check: the file map produced by the create
            // command (plugin.json + skill-manager-plugin.toml + skills/.gitkeep)
            // should produce a dir PluginParser.load accepts.
            TestHarness h = TestHarness.create();
            Path dir = Files.createTempDirectory("scaffold-plugin-parse-").resolve("widget");
            Map<String, String> files = new LinkedHashMap<>();
            files.put(".claude-plugin/plugin.json", """
                    {
                      "name": "widget",
                      "version": "0.1.0",
                      "description": "TODO"
                    }
                    """);
            files.put("skill-manager-plugin.toml", """
                    [plugin]
                    name = "widget"
                    version = "0.1.0"
                    description = "TODO"
                    """);
            files.put("skills/.gitkeep", "");

            h.run(new SkillEffect.ScaffoldPlugin(dir, "widget", files));
            var p = PluginParser.load(dir);
            assertEquals("widget", p.name(), "parsed name");
            assertEquals("0.1.0", p.version(), "parsed version");
        });

        return suite.runAll();
    }
}
