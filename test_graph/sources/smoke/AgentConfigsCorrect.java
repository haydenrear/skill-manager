///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgFixture.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Proves that {@code skill-manager install} idempotently writes the single
 * {@code virtual-mcp-gateway} entry into the agent MCP configs — no
 * {@code --sync} flag, no per-MCP-dep writes, just one gateway entry per
 * agent.
 *
 * <p>Runs install with {@code -Duser.home=<fakeHome>}, a fresh
 * {@code SKILL_MANAGER_HOME}, and explicit Claude, Codex, and Gemini homes
 * inside that fake home so Java and agent CLI writes land in the sandbox,
 * not the developer's real {@code ~/}.
 */
public class AgentConfigsCorrect {
    static final NodeSpec SPEC = NodeSpec.of("agent.configs.correct")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "gateway.up", "echo.http.up")
            .tags("agents", "config")
            .timeout("90s")
            .retries(2);
    private static final String SKILL_NAME = "agent-config-probe-skill";
    private static final String SERVER_ID = "echo-http-agent-probe";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            if (home == null || gatewayUrl == null || mcpUrl == null) {
                return NodeResult.fail("agent.configs.correct", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path fakeHome = Path.of(home, "fake-home-agent-configs");
            Files.createDirectories(fakeHome);
            Path fakeSm = fakeHome.resolve(".skill-manager");
            Files.createDirectories(fakeSm);
            Path fakeCodex = fakeHome.resolve(".codex");
            Files.createDirectories(fakeCodex);
            Path fakeClaude = fakeHome.resolve(".claude");
            Files.createDirectories(fakeClaude);

            Path template = repoRoot.resolve("test_graph/fixtures/echo-skill-template");
            Path skillDir = TgFixture.stampEchoSkill(
                    template, fakeHome.resolve("fixtures"),
                    SKILL_NAME, SERVER_ID, "global-sticky", mcpUrl);

            // Invoke jbang directly so we can pass -Duser.home.
            ProcessBuilder pb = new ProcessBuilder(
                    "jbang", "run",
                    "-Duser.home=" + fakeHome,
                    repoRoot.resolve("SkillManager.java").toString(),
                    "install", "file:" + skillDir, "--yes")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", fakeSm.toString());
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", fakeHome.toString());
            pb.environment().put("CLAUDE_CONFIG_DIR", fakeClaude.toString());
            pb.environment().put("CODEX_HOME", fakeCodex.toString());
            pb.environment().put("GEMINI_HOME", fakeHome.resolve(".gemini").toString());
            // Without this the install in the fake home falls back to
            // http://127.0.0.1:51717 instead of the test gateway's ephemeral port.
            pb.environment().put("SKILL_MANAGER_GATEWAY_URL", gatewayUrl);
            StringBuilder out = new StringBuilder();
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out.append(line).append('\n');
                }
            }
            int rc = p.waitFor();
            boolean codexCliAvailable = isExecutableOnPath("codex");
            boolean codexMarketplaceOpsSucceeded =
                    !codexCliAvailable || noCodexMarketplaceFailure(out.toString());

            Path claudeJson = fakeHome.resolve(".claude.json");
            Path codexToml = fakeHome.resolve(".codex").resolve("config.toml");
            Path geminiSettings = fakeHome.resolve(".gemini").resolve("settings.json");
            String claudeText = Files.isRegularFile(claudeJson) ? Files.readString(claudeJson) : "";
            String codexText = Files.isRegularFile(codexToml) ? Files.readString(codexToml) : "";
            String geminiText = Files.isRegularFile(geminiSettings) ? Files.readString(geminiSettings) : "";
            Path marketplaceRoot = fakeSm.resolve("plugin-marketplace");
            String expectedMarketplaceSource = Files.isDirectory(marketplaceRoot)
                    ? marketplaceRoot.toRealPath().toString()
                    : "";

            boolean claudeHasEntry = claudeText.contains("\"virtual-mcp-gateway\"");
            boolean claudeHasUrl = claudeText.contains(gatewayUrl);
            boolean codexHasEntry = codexText.contains("[mcp_servers.virtual_mcp_gateway]");
            boolean codexHasUrl = codexText.contains(gatewayUrl);
            boolean geminiHasEntry = geminiText.contains("\"virtual-mcp-gateway\"");
            boolean geminiHasUrl = geminiText.contains(gatewayUrl);
            boolean geminiUsesHttpUrl = geminiText.contains("\"httpUrl\"");
            boolean codexMarketplaceConfigured =
                    !codexCliAvailable || hasLocalMarketplace(codexText, expectedMarketplaceSource);

            // Idempotency: a second install (of a different skill) must NOT
            // add a duplicate entry. Install a second fixture and re-check.
            Path skillDir2 = TgFixture.stampEchoSkill(
                    template, fakeHome.resolve("fixtures"),
                    SKILL_NAME + "-2", SERVER_ID + "-2", "global-sticky", mcpUrl);
            ProcessBuilder pb2 = new ProcessBuilder(
                    "jbang", "run",
                    "-Duser.home=" + fakeHome,
                    repoRoot.resolve("SkillManager.java").toString(),
                    "install", "file:" + skillDir2, "--yes")
                    .redirectErrorStream(true);
            pb2.environment().put("SKILL_MANAGER_HOME", fakeSm.toString());
            pb2.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb2.environment().put("CLAUDE_HOME", fakeHome.toString());
            pb2.environment().put("CLAUDE_CONFIG_DIR", fakeClaude.toString());
            pb2.environment().put("CODEX_HOME", fakeCodex.toString());
            pb2.environment().put("GEMINI_HOME", fakeHome.resolve(".gemini").toString());
            pb2.environment().put("SKILL_MANAGER_GATEWAY_URL", gatewayUrl);
            StringBuilder out2 = new StringBuilder();
            Process p2 = pb2.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p2.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println(line);
                    out2.append(line).append('\n');
                }
            }
            int rc2 = p2.waitFor();
            boolean codexMarketplaceOpsStillSucceeded =
                    !codexCliAvailable || noCodexMarketplaceFailure(out2.toString());

            String claudeText2 = Files.isRegularFile(claudeJson) ? Files.readString(claudeJson) : "";
            String codexText2 = Files.isRegularFile(codexToml) ? Files.readString(codexToml) : "";
            boolean codexMarketplaceStillConfigured =
                    !codexCliAvailable || hasLocalMarketplace(codexText2, expectedMarketplaceSource);
            int gatewayMentionsClaude = countOccurrences(claudeText2, "\"virtual-mcp-gateway\"");
            int gatewayMentionsCodex = countOccurrences(
                    codexText2,
                    "[mcp_servers.virtual_mcp_gateway]");
            int gatewayMentionsGemini = countOccurrences(
                    Files.isRegularFile(geminiSettings) ? Files.readString(geminiSettings) : "",
                    "\"virtual-mcp-gateway\"");

            boolean idempotentClaude = gatewayMentionsClaude == 1;
            boolean idempotentCodex = gatewayMentionsCodex == 1;
            boolean idempotentGemini = gatewayMentionsGemini == 1;

            boolean ok = rc == 0 && rc2 == 0
                    && codexMarketplaceOpsSucceeded && codexMarketplaceOpsStillSucceeded
                    && codexMarketplaceConfigured && codexMarketplaceStillConfigured
                    && claudeHasEntry && claudeHasUrl
                    && codexHasEntry && codexHasUrl
                    && geminiHasEntry && geminiHasUrl && geminiUsesHttpUrl
                    && idempotentClaude && idempotentCodex && idempotentGemini;
            return (ok
                    ? NodeResult.pass("agent.configs.correct")
                    : NodeResult.fail("agent.configs.correct",
                            "rc=" + rc + " rc2=" + rc2
                                    + " codexCliAvailable=" + codexCliAvailable
                                    + " codexMarketplaceOpsSucceeded=" + codexMarketplaceOpsSucceeded
                                    + " codexMarketplaceOpsStillSucceeded=" + codexMarketplaceOpsStillSucceeded
                                    + " codexMarketplaceConfigured=" + codexMarketplaceConfigured
                                    + " codexMarketplaceStillConfigured=" + codexMarketplaceStillConfigured
                                    + " claudeEntry=" + claudeHasEntry + " claudeUrl=" + claudeHasUrl
                                    + " codexEntry=" + codexHasEntry + " codexUrl=" + codexHasUrl
                                    + " geminiEntry=" + geminiHasEntry + " geminiUrl=" + geminiHasUrl
                                    + " geminiHttpUrl=" + geminiUsesHttpUrl
                                    + " idempotentClaude=" + idempotentClaude
                                    + " idempotentCodex=" + idempotentCodex
                                    + " idempotentGemini=" + idempotentGemini))
                    .assertion("install_ok", rc == 0)
                    .assertion("second_install_ok", rc2 == 0)
                    .assertion("codex_marketplace_ops_succeeded_when_available", codexMarketplaceOpsSucceeded)
                    .assertion("codex_marketplace_ops_still_succeed_when_available", codexMarketplaceOpsStillSucceeded)
                    .assertion("codex_marketplace_isolated_when_available", codexMarketplaceConfigured)
                    .assertion("codex_marketplace_remains_isolated_when_available", codexMarketplaceStillConfigured)
                    .assertion("claude_json_has_gateway_entry", claudeHasEntry)
                    .assertion("claude_json_points_at_gateway_url", claudeHasUrl)
                    .assertion("codex_toml_has_gateway_table", codexHasEntry)
                    .assertion("codex_toml_points_at_gateway_url", codexHasUrl)
                    .assertion("gemini_settings_has_gateway_entry", geminiHasEntry)
                    .assertion("gemini_settings_points_at_gateway_url", geminiHasUrl)
                    .assertion("gemini_settings_uses_httpUrl", geminiUsesHttpUrl)
                    .assertion("claude_entry_not_duplicated", idempotentClaude)
                    .assertion("codex_entry_not_duplicated", idempotentCodex)
                    .assertion("gemini_entry_not_duplicated", idempotentGemini)
                    .metric("codexCliAvailable", codexCliAvailable ? 1 : 0)
                    .metric("claudeGatewayMentions", gatewayMentionsClaude)
                    .metric("codexGatewayMentions", gatewayMentionsCodex)
                    .metric("geminiGatewayMentions", gatewayMentionsGemini);
        });
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }

    private static boolean hasLocalMarketplace(String config, String expectedSource) {
        return !expectedSource.isBlank()
                && config.contains("[marketplaces.skill-manager]")
                && config.contains("source_type = \"local\"")
                && config.contains("source = \"" + expectedSource + "\"");
    }

    private static boolean noCodexMarketplaceFailure(String output) {
        return output.lines().noneMatch(line ->
                line.contains("codex: marketplace-") && line.contains(" failed"));
    }

    private static boolean isExecutableOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (!directory.isBlank() && Files.isExecutable(Path.of(directory, executable))) return true;
        }
        return false;
    }
}
