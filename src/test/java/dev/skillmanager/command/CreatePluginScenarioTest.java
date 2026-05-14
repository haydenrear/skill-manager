package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.CreateCommand;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.PluginParser;
import dev.skillmanager.registry.SkillPackager;

import java.lang.reflect.Method;
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

        suite.test("create skill templates include tool docs and skill-manager reference", () -> {
            Path dir = Files.createTempDirectory("create-skill-template-").resolve("widget");
            scaffoldSkillFromCommandTemplates(dir, "widget", "0.1.0", "Sample skill");

            var skill = SkillParser.load(dir);
            assertEquals("widget", skill.name(), "name round-tripped");
            assertTrue(Files.isRegularFile(dir.resolve("tools/cli.md")), "tools/cli.md present");
            assertTrue(Files.isRegularFile(dir.resolve("tools/mcp.md")), "tools/mcp.md present");
            assertTrue(Files.readString(dir.resolve("skill-manager.toml"))
                    .contains("\"skill:skill-manager\""), "skill-manager reference present");
            assertTrue(Files.readString(dir.resolve("tools/cli.md"))
                    .contains("path: references/cli.md"), "cli import present");
            assertTrue(Files.readString(dir.resolve("tools/mcp.md"))
                    .contains("path: references/mcp.md"), "mcp import present");
        });

        suite.test("create plugin → parses as PluginUnit", () -> {
            Path dir = Files.createTempDirectory("create-plugin-parse-").resolve("widget");
            scaffoldFromCommandTemplates(dir, "widget", "0.1.0", "Sample plugin");
            var unit = PluginParser.load(dir);
            assertEquals("widget", unit.name(), "name round-tripped");
            assertEquals("0.1.0", unit.version(), "version round-tripped");
            assertEquals(1, unit.containedSkills().size(), "one starter contained skill");
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
            assertTrue(entries.contains("README.md"), "starter README in bundle");
            assertTrue(entries.contains("skills/widget-skill/SKILL.md"),
                    "contained starter skill in bundle");
            assertTrue(entries.contains("skills/widget-skill/tools/cli.md"),
                    "contained CLI starter doc in bundle");
            assertTrue(entries.contains("skills/widget-skill/tools/mcp.md"),
                    "contained MCP starter doc in bundle");
        });

        return suite.runAll();
    }

    /**
     * Mirror the file maps {@code CreateCommand} builds, using its
     * private render methods by reflection so template drift is caught
     * at compile/test time without invoking the command against the
     * developer's real skill-manager home.
     */
    private static void scaffoldSkillFromCommandTemplates(Path dir, String name, String version, String description)
            throws java.io.IOException {
        Files.createDirectories(dir.resolve("tools"));
        Files.writeString(dir.resolve("SKILL.md"), render("renderSkillMd", name, description));
        Files.writeString(dir.resolve("skill-manager.toml"), render("renderToml", name, version, description));
        Files.writeString(dir.resolve("tools/cli.md"), render("renderCliToolMarkdown"));
        Files.writeString(dir.resolve("tools/mcp.md"), render("renderMcpToolMarkdown"));
    }

    private static void scaffoldFromCommandTemplates(Path dir, String name, String version, String description)
            throws java.io.IOException {
        String contained = name + "-skill";
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.createDirectories(dir.resolve("skills/" + contained + "/tools"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                render("renderPluginJson", name, version, description));
        Files.writeString(dir.resolve("skill-manager-plugin.toml"),
                render("renderPluginToml", name, version, description));
        Files.writeString(dir.resolve("README.md"), render("renderStarterMarkdown",
                name, "TODO: plugin-level author notes. Describe hooks, commands, agents, and contained skills."));
        Files.writeString(dir.resolve("skills/" + contained + "/SKILL.md"),
                render("renderContainedSkillMd", contained, description, name));
        Files.writeString(dir.resolve("skills/" + contained + "/skill-manager.toml"),
                render("renderToml", contained, version, description));
        Files.writeString(dir.resolve("skills/" + contained + "/tools/cli.md"),
                render("renderCliToolMarkdown"));
        Files.writeString(dir.resolve("skills/" + contained + "/tools/mcp.md"),
                render("renderMcpToolMarkdown"));
    }

    private static String render(String method, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            java.util.Arrays.fill(types, String.class);
            Method m = CreateCommand.class.getDeclaredMethod(method, types);
            m.setAccessible(true);
            return (String) m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("could not invoke CreateCommand." + method, e);
        }
    }
}
