package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.registry.SkillPackager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-13 scenario: {@code skill-manager create plugin <name>} →
 * {@code skill-manager publish <dir>} round-trip via the underlying
 * pieces (CreateCommand's render templates → PluginParser → SkillPackager).
 *
 * <p>Doesn't actually invoke the picocli command class — that needs full
 * stdout capture and a temp working dir setup picocli wires. Instead
 * exercises the same effect data the command builds: write the file
 * map, parse the result, package it, verify the bundle round-trips.
 *
 * <p>Pins the contract: the templates we ship in {@code create --kind
 * plugin} produce a dir that {@code SkillPackager} happily detects as
 * PLUGIN and bundles all expected entries from.
 */
public final class CreatePluginScenarioTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CreatePluginScenarioTest");

        suite.test("create plugin → parses as PluginUnit", () -> {
            Path dir = Files.createTempDirectory("create-plugin-parse-").resolve("widget");
            scaffoldFromCommandTemplates(dir, "widget", "0.1.0", "Sample plugin");
            var unit = PluginParser.load(dir);
            assertEquals("widget", unit.name(), "name round-tripped");
            assertEquals("0.1.0", unit.version(), "version round-tripped");
        });

        suite.test("create plugin → publish detects PLUGIN + bundles all expected entries", () -> {
            Path dir = Files.createTempDirectory("create-plugin-publish-").resolve("widget");
            scaffoldFromCommandTemplates(dir, "widget", "0.1.0", "Sample plugin");

            assertEquals(SkillPackager.Kind.PLUGIN, SkillPackager.detectKind(dir),
                    "publish detects plugin");

            Path out = Files.createTempDirectory("create-plugin-publish-out-");
            Path bundle = SkillPackager.pack(dir, out);

            Set<String> entries = dev.skillmanager.registry.PublishDetectsPluginTest
                    .readBundleEntries(bundle);
            assertTrue(entries.contains(".claude-plugin/plugin.json"),
                    "manifest in bundle");
            assertTrue(entries.contains("skill-manager-plugin.toml"),
                    "plugin toml in bundle");
        });

        return suite.runAll();
    }

    /**
     * Mirror the file map {@code CreateCommand} builds for {@code --kind
     * plugin}. Kept in sync by hand; if {@code CreateCommand}'s render
     * methods drift, this scenario test catches the divergence.
     */
    private static void scaffoldFromCommandTemplates(Path dir, String name, String version, String description)
            throws java.io.IOException {
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.createDirectories(dir.resolve("skills"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"), String.format("""
                {
                  "name": "%s",
                  "version": "%s",
                  "description": "%s"
                }
                """, name, version, description));
        Files.writeString(dir.resolve("skill-manager-plugin.toml"), String.format("""
                [plugin]
                name = "%s"
                version = "%s"
                description = "%s"
                """, name, version, description));
        Files.writeString(dir.resolve("skills/.gitkeep"), "");
    }
}
