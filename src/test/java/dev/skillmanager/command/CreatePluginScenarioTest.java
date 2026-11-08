package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.CreateCommand;
import dev.skillmanager.model.SkillParser;
import dev.skillmanager.model.PluginParser;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Ticket-13 scenario: {@code skill-manager create plugin <name>} via the
 * underlying pieces (CreateCommand's render templates → PluginParser).
 *
 * <p>Doesn't actually invoke the picocli command class — that needs full
 * stdout capture and a temp working dir setup picocli wires. Instead
 * exercises the same effect data the command builds: write the file
 * map and parse the result. (The registry-publish half of this scenario
 * went with the registry, #427.)
 */
public final class CreatePluginScenarioTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("CreatePluginScenarioTest");

        suite.test("create skill templates include tool docs without default install refs", () -> {
            Path dir = Files.createTempDirectory("create-skill-template-").resolve("widget");
            scaffoldSkillFromCommandTemplates(dir, "widget", "0.1.0", "Sample skill");

            var skill = SkillParser.load(dir);
            assertEquals("widget", skill.name(), "name round-tripped");
            assertTrue(Files.isRegularFile(dir.resolve("tools/cli.md")), "tools/cli.md present");
            assertTrue(Files.isRegularFile(dir.resolve("tools/mcp.md")), "tools/mcp.md present");
            assertTrue(Files.readString(dir.resolve("skill-manager.toml"))
                    .contains("skill_references = ["), "skill references section present");
            assertTrue(!Files.readString(dir.resolve("skill-manager.toml"))
                    .contains("\"skill:skill-manager\""), "no default skill-manager install reference");
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
