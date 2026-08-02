package dev.skillmanager.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.agent.ClaudeAgent;
import dev.skillmanager.agent.CodexAgent;
import dev.skillmanager.agent.GeminiAgent;
import dev.skillmanager.store.HomeDescriptor;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
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

        // ------------------------------------------------------------- D4

        suite.test("every agent's gateway entry lands in the file that agent reads", () -> {
            // The file `install` writes and the file the launched agent opens
            // have to be the same file, for all three agents. Measured
            // otherwise: `install` printed
            //   ADDED claude (<project>/.claude.json)
            // while the launch env said CLAUDE_CONFIG_DIR=<project>/.claude,
            // and the real claude binary — run by that same install's
            // marketplace-add step — created <project>/.claude/.claude.json
            // itself. Two files; only one of them had mcpServers; the agent
            // read the other one. Codex and gemini were already correct, and
            // are asserted here by the same derived-path rule so the guard
            // covers them too.
            AgentHomes.clearOverrides();
            Path homeRoot = Files.createTempDirectory("mcp-agent-read-").toRealPath();
            try {
                // The launch environment, from the same writer the shims and
                // `exec --print-env` publish. Never hard-coded: the whole
                // defect is a disagreement between what is exported and what
                // is addressed, so a test that spells the paths itself would
                // agree with whichever side it copied.
                HomeDescriptor.Env env = HomeDescriptor.envFor(
                        homeRoot, homeRoot.resolve(".skill-manager"));
                Map<String, String> exported = env.asMap();
                for (String key : List.of(AgentHomes.CLAUDE_CONFIG_DIR, AgentHomes.CLAUDE_HOME,
                        AgentHomes.CODEX_HOME, AgentHomes.GEMINI_HOME)) {
                    String value = exported.get(key);
                    assertTrue(value != null && !value.isBlank(),
                            "precondition: the launch env exports " + key);
                    AgentHomes.setOverride(key, Path.of(value));
                }

                // Key-presence, never file-presence: the file the agent reads
                // exists before the write, so "the entry is not in it" cannot
                // be true because the file is missing. This is the shape the
                // real claude binary leaves behind on first run.
                Path claudeReads = Path.of(exported.get(AgentHomes.CLAUDE_CONFIG_DIR))
                        .resolve(".claude.json");
                Files.createDirectories(claudeReads.getParent());
                Files.writeString(claudeReads, "{\"firstStartTime\":\"2026-01-01T00:00:00Z\"}");
                Path codexReads = Path.of(exported.get(AgentHomes.CODEX_HOME))
                        .resolve("config.toml");
                Path geminiReads = Path.of(exported.get(AgentHomes.GEMINI_HOME))
                        .resolve("settings.json");
                Path strayClaudeJson = homeRoot.resolve(".claude.json");

                McpWriter writer = new McpWriter(
                        GatewayConfig.of(URI.create("http://127.0.0.1:51717")));
                assertEquals(McpWriter.ConfigChange.ADDED,
                        writer.writeAgentEntry(new ClaudeAgent()), "claude entry added");
                assertEquals(McpWriter.ConfigChange.ADDED,
                        writer.writeAgentEntry(new CodexAgent()), "codex entry added");
                assertEquals(McpWriter.ConfigChange.ADDED,
                        writer.writeAgentEntry(new GeminiAgent()), "gemini entry added");

                // Not "a file under the project mentions the gateway" — both
                // candidate files are under the project and the bug is which
                // one. The path is derived, the JSON is parsed, and the URL is
                // required to be the gateway's.
                assertEquals(claudeReads, new ClaudeAgent().mcpConfigPath(),
                        "claude writes where CLAUDE_CONFIG_DIR says it reads");
                assertTrue(Files.isRegularFile(claudeReads), "claude's config file still exists");
                @SuppressWarnings("unchecked")
                Map<String, Object> claudeJson = new ObjectMapper()
                        .readValue(claudeReads.toFile(), Map.class);
                assertTrue(claudeJson.containsKey("firstStartTime"),
                        "the agent's own keys survive the write");
                @SuppressWarnings("unchecked")
                Map<String, Object> servers = (Map<String, Object>) claudeJson.get("mcpServers");
                assertNotNull(servers, "mcpServers block present in the file claude reads");
                @SuppressWarnings("unchecked")
                Map<String, Object> entry =
                        (Map<String, Object>) servers.get(McpWriter.GATEWAY_ENTRY);
                assertNotNull(entry, "the gateway entry is in the file claude reads");
                assertEquals("http://127.0.0.1:51717/mcp", entry.get("url"),
                        "and it carries the gateway URL");

                assertEquals(codexReads, new CodexAgent().mcpConfigPath(),
                        "codex writes where CODEX_HOME says it reads");
                assertContains(Files.readString(codexReads), "http://127.0.0.1:51717/mcp",
                        "codex's own file carries the URL");
                assertEquals(geminiReads, new GeminiAgent().mcpConfigPath(),
                        "gemini writes where GEMINI_HOME says it reads");
                assertContains(Files.readString(geminiReads), "http://127.0.0.1:51717/mcp",
                        "gemini's own file carries the URL");

                // The other half, and the direct cause of the `wt new` refusal:
                // no stray file beside the config dir. `/.claude/` covers
                // <root>/.claude/.claude.json; it does not cover
                // <root>/.claude.json, so creating one leaves the checkout
                // dirty on a repo whose .gitignore carries the four documented
                // rules.
                assertFalse(Files.exists(strayClaudeJson),
                        "no <root>/.claude.json is created beside the config dir");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        suite.test("with CLAUDE_CONFIG_DIR unset the entry stays at <home>/.claude.json", () -> {
            // The can-fail companion for the test above, and the reason the
            // fix is not "always put it inside the config dir": when
            // CLAUDE_CONFIG_DIR is unset the Claude CLI reads
            // <home>/.claude.json — for the global home, ~/.claude.json, where
            // the operator's own entries live. Moving that one would be the
            // same defect pointed at the operator.
            AgentHomes.clearOverrides();
            Path homeRoot = Files.createTempDirectory("mcp-agent-read-default-").toRealPath();
            try {
                AgentHomes.setOverride(AgentHomes.CLAUDE_HOME, homeRoot);
                assertEquals(homeRoot.resolve(".claude.json"), new ClaudeAgent().mcpConfigPath(),
                        "unset CLAUDE_CONFIG_DIR keeps the file beside the config dir");
                assertEquals(homeRoot.resolve(".claude"), AgentHomes.claude().configDir(),
                        "and the config dir is still <home>/.claude");
            } finally {
                AgentHomes.clearOverrides();
            }
        });

        return suite.runAll();
    }
}
