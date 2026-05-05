package dev.skillmanager.model;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * When {@code skill-manager-plugin.toml} and {@code plugin.json}
 * disagree on identity (name / version / description), the parser
 * emits a warning, the toml value wins, and the parse succeeds.
 * Never an error — drift is real (e.g. someone updated one file
 * but not the other) and the warning surfaces it for the user
 * without blocking install.
 */
public final class PluginParserDriftWarnsTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("plugin-drift-test-");

        return Tests.suite("PluginParserDriftWarnsTest")
                .test("name drift: toml wins, warning emitted", () -> {
                    PluginUnit unit = scaffoldDrift(tmp, "name-drift",
                            "from-json", "from-toml",
                            "0.1.0", "0.1.0",
                            "same", "same");
                    assertEquals("from-toml", unit.name(), "toml name wins");
                    assertSize(1, unit.warnings(), "one warning");
                    assertContains(unit.warnings().get(0), "name", "warning mentions name");
                    assertContains(unit.warnings().get(0), "from-toml", "warning includes toml value");
                    assertContains(unit.warnings().get(0), "from-json", "warning includes json value");
                })
                .test("version drift: toml wins, warning emitted", () -> {
                    PluginUnit unit = scaffoldDrift(tmp, "version-drift",
                            "same", "same",
                            "1.0.0", "1.1.0",
                            "same", "same");
                    assertEquals("1.1.0", unit.version(), "toml version wins");
                    assertSize(1, unit.warnings(), "one warning");
                    assertContains(unit.warnings().get(0), "version", "warning mentions version");
                })
                .test("multiple drifts: one warning per field", () -> {
                    PluginUnit unit = scaffoldDrift(tmp, "all-drift",
                            "json-name", "toml-name",
                            "1.0.0", "1.1.0",
                            "json-desc", "toml-desc");
                    assertEquals("toml-name", unit.name(), "name from toml");
                    assertEquals("1.1.0", unit.version(), "version from toml");
                    assertEquals("toml-desc", unit.description(), "description from toml");
                    assertSize(3, unit.warnings(), "one warning per drifted field");
                })
                .test("no drift: no warnings", () -> {
                    PluginUnit unit = scaffoldDrift(tmp, "agree",
                            "agree", "agree",
                            "1.0.0", "1.0.0",
                            "same", "same");
                    assertSize(0, unit.warnings(), "no warnings on full agreement");
                })
                .test("toml-only field (json missing): no warning", () -> {
                    Path dir = tmp.resolve("toml-only");
                    Files.createDirectories(dir.resolve(".claude-plugin"));
                    Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                            "{ \"name\": \"only-json-name\" }");
                    Files.writeString(dir.resolve(PluginParser.TOML_FILENAME),
                            """
                            [plugin]
                            name = "only-json-name"
                            version = "9.9.9"
                            """);
                    PluginUnit unit = PluginParser.load(dir);
                    assertEquals("9.9.9", unit.version(), "toml supplies missing version");
                    assertTrue(unit.warnings().isEmpty(),
                            "no drift warning when one side is absent");
                })
                .runAll();
    }

    private static PluginUnit scaffoldDrift(Path tmp, String dirName,
                                            String jsonName, String tomlName,
                                            String jsonVersion, String tomlVersion,
                                            String jsonDesc, String tomlDesc) throws IOException {
        Path dir = tmp.resolve(dirName);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/plugin.json"),
                """
                { "name": "%s", "version": "%s", "description": "%s" }
                """.formatted(jsonName, jsonVersion, jsonDesc));
        Files.writeString(dir.resolve(PluginParser.TOML_FILENAME),
                """
                [plugin]
                name = "%s"
                version = "%s"
                description = "%s"
                """.formatted(tomlName, tomlVersion, tomlDesc));
        return PluginParser.load(dir);
    }
}
