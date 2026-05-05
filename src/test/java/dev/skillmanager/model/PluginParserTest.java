package dev.skillmanager.model;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Parses minimal plugin layouts and asserts on the resulting
 * {@link PluginUnit}. Covers:
 *
 * <ul>
 *   <li>plugin with only {@code plugin.json} (no toml, no contained skills)</li>
 *   <li>plugin with {@code skill-manager-plugin.toml} declaring plugin-level deps</li>
 *   <li>plugin with one contained skill</li>
 *   <li>kind detection via {@link PluginParser#looksLikePlugin(Path)}</li>
 * </ul>
 *
 * <p>Effective-dep-union behavior is tested in
 * {@link EffectiveDepUnionTest}. Drift warnings are tested in
 * {@link PluginParserDriftWarnsTest}.
 */
public final class PluginParserTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("plugin-parser-test-");

        return Tests.suite("PluginParserTest")
                .test("loads plugin with only plugin.json", () -> {
                    Path dir = tmp.resolve("minimal-plugin");
                    Files.createDirectories(dir.resolve(".claude-plugin"));
                    Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                            """
                            { "name": "minimal", "version": "0.1.0", "description": "minimal plugin" }
                            """);
                    PluginUnit unit = PluginParser.load(dir);
                    assertEquals("minimal", unit.name(), "name from plugin.json");
                    assertEquals("0.1.0", unit.version(), "version from plugin.json");
                    assertEquals("minimal plugin", unit.description(), "description from plugin.json");
                    assertEquals(UnitKind.PLUGIN, unit.kind(), "kind");
                    assertSize(0, unit.cliDependencies(), "no plugin-level CLI deps");
                    assertSize(0, unit.mcpDependencies(), "no plugin-level MCP deps");
                    assertSize(0, unit.containedSkills(), "no contained skills");
                    assertSize(0, unit.warnings(), "no warnings");
                })
                .test("loads plugin with sidecar toml plugin-level deps", () -> {
                    DepSpec pluginDeps = DepSpec.of()
                            .cli("pip:cowsay==6.0")
                            .mcp("plugin-mcp")
                            .ref("skill:hello@1.0.0")
                            .build();
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "with-toml", pluginDeps);
                    assertEquals("with-toml", unit.name(), "name from toml");
                    assertSize(1, unit.cliDependencies(), "one plugin-level CLI dep");
                    assertEquals("cowsay", unit.cliDependencies().get(0).name(), "CLI dep name");
                    assertSize(1, unit.mcpDependencies(), "one plugin-level MCP dep");
                    assertEquals("plugin-mcp", unit.mcpDependencies().get(0).name(), "MCP dep name");
                    assertSize(1, unit.references(), "one reference");
                    assertEquals("hello", unit.references().get(0).name(), "ref name");
                })
                .test("loads plugin with one contained skill", () -> {
                    ContainedSkillSpec inner = new ContainedSkillSpec(
                            "echo",
                            DepSpec.of().cli("pip:requests==2.31").build()
                    );
                    PluginUnit unit = UnitFixtures.scaffoldPlugin(tmp, "with-contained", DepSpec.empty(), inner);
                    assertSize(1, unit.containedSkills(), "one contained skill");
                    assertEquals("echo", unit.containedSkills().get(0).name(), "contained skill name");
                    // Contained skill's deps are unioned up to plugin level.
                    assertSize(1, unit.cliDependencies(), "contained CLI dep visible at plugin level");
                    assertEquals("requests", unit.cliDependencies().get(0).name(), "unioned CLI dep");
                })
                .test("looksLikePlugin detects layout", () -> {
                    Path plugin = tmp.resolve("layout-plugin");
                    Files.createDirectories(plugin.resolve(".claude-plugin"));
                    Files.writeString(plugin.resolve(".claude-plugin/plugin.json"),
                            "{\"name\": \"x\"}");
                    Path notPlugin = tmp.resolve("not-plugin");
                    Files.createDirectories(notPlugin);
                    Files.writeString(notPlugin.resolve("SKILL.md"), "---\nname: x\n---\nbody");
                    assertTrue(PluginParser.looksLikePlugin(plugin), "plugin dir detected");
                    assertEquals(false, PluginParser.looksLikePlugin(notPlugin), "skill dir not detected as plugin");
                })
                .test("missing plugin.json errors with a clear message", () -> {
                    Path dir = tmp.resolve("no-json");
                    Files.createDirectories(dir);
                    try {
                        PluginParser.load(dir);
                        throw new AssertionError("expected IOException");
                    } catch (IOException e) {
                        assertNotNull(e.getMessage(), "exception has a message");
                        assertContains(e.getMessage(), ".claude-plugin/plugin.json", "names the missing file");
                    }
                })
                .runAll();
    }
}
