package dev.skillmanager.mcp;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.agent.GeminiAgent;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class McpWriterTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("McpWriterTest");

        suite.test("Gemini settings.json gets idempotent virtual gateway entry", () -> {
            AgentHomes.clearOverrides();
            Path geminiHome = Files.createTempDirectory("mcp-writer-gemini-");
            AgentHomes.setOverride(AgentHomes.GEMINI_HOME, geminiHome);
            try {
                Path settings = geminiHome.resolve("settings.json");
                Files.writeString(settings, """
                        {
                          "theme": "Default",
                          "mcpServers": {
                            "other": { "httpUrl": "http://127.0.0.1:9/mcp" }
                          }
                        }
                        """);
                McpWriter writer = new McpWriter(GatewayConfig.of(URI.create("http://127.0.0.1:7777")));
                McpWriter.ConfigChange first = writer.writeAgentEntry(new GeminiAgent());
                McpWriter.ConfigChange second = writer.writeAgentEntry(new GeminiAgent());

                String text = Files.readString(settings);
                assertEquals(McpWriter.ConfigChange.ADDED, first, "first write adds entry");
                assertEquals(McpWriter.ConfigChange.UNCHANGED, second, "second write unchanged");
                assertTrue(text.contains("\"theme\""), "preserves unrelated settings");
                assertTrue(text.contains("\"other\""), "preserves unrelated MCP servers");
                assertTrue(text.contains("\"virtual-mcp-gateway\""), "gateway entry present");
                assertTrue(text.contains("\"httpUrl\""), "Gemini HTTP URL key used");
                assertTrue(text.contains("http://127.0.0.1:7777/mcp"), "gateway URL present");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("Gemini removeAgentEntry removes only virtual gateway", () -> {
            AgentHomes.clearOverrides();
            Path geminiHome = Files.createTempDirectory("mcp-writer-gemini-remove-");
            AgentHomes.setOverride(AgentHomes.GEMINI_HOME, geminiHome);
            try {
                McpWriter writer = new McpWriter(GatewayConfig.of(URI.create("http://127.0.0.1:7777")));
                writer.writeAgentEntry(new GeminiAgent());
                writer.removeAgentEntry(new GeminiAgent());

                String text = Files.readString(geminiHome.resolve("settings.json"));
                assertFalse(text.contains("\"virtual-mcp-gateway\""), "gateway entry removed");
                assertTrue(text.contains("\"mcpServers\""), "settings shape remains valid");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        return suite.runAll();
    }
}
