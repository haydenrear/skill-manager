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
 * <p>Runs install with {@code -Duser.home=<fakeHome>} and a fresh
 * {@code SKILL_MANAGER_HOME} inside that fake home so agent config writes
 * land in the sandbox, not the developer's real {@code ~/}.
 */
public class AgentConfigsCorrect {
    static final NodeSpec SPEC = NodeSpec.of("agent.configs.correct")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "gateway.up", "echo.http.up")
            .tags("agents", "config")
            .timeout("90s");

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

            Path template = repoRoot.resolve("test_graph/fixtures/echo-skill-template");
            Path skillDir = TgFixture.stampEchoSkill(
                    template, fakeHome.resolve("fixtures"),
                    SKILL_NAME, SERVER_ID, "global-sticky", mcpUrl);

            // Invoke jbang directly so we can pass -Duser.home.
            ProcessBuilder pb = new ProcessBuilder(
                    "jbang", "run",
                    "-Duser.home=" + fakeHome,
                    repoRoot.resolve("SkillManager.java").toString(),
                    "install", "file:" + skillDir)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", fakeSm.toString());
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
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

            Path claudeJson = fakeHome.resolve(".claude.json");
            Path codexToml = fakeHome.resolve(".codex").resolve("config.toml");
            String claudeText = Files.isRegularFile(claudeJson) ? Files.readString(claudeJson) : "";
            String codexText = Files.isRegularFile(codexToml) ? Files.readString(codexToml) : "";

            boolean claudeHasEntry = claudeText.contains("\"virtual-mcp-gateway\"");
            boolean claudeHasUrl = claudeText.contains(gatewayUrl);
            boolean codexHasEntry = codexText.contains("[mcp_servers.virtual_mcp_gateway]");
            boolean codexHasUrl = codexText.contains(gatewayUrl);

            // Idempotency: a second install (of a different skill) must NOT
            // add a duplicate entry. Install a second fixture and re-check.
            Path skillDir2 = TgFixture.stampEchoSkill(
                    template, fakeHome.resolve("fixtures"),
                    SKILL_NAME + "-2", SERVER_ID + "-2", "global-sticky", mcpUrl);
            ProcessBuilder pb2 = new ProcessBuilder(
                    "jbang", "run",
                    "-Duser.home=" + fakeHome,
                    repoRoot.resolve("SkillManager.java").toString(),
                    "install", "file:" + skillDir2)
                    .redirectErrorStream(true);
            pb2.environment().put("SKILL_MANAGER_HOME", fakeSm.toString());
            pb2.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb2.environment().put("SKILL_MANAGER_GATEWAY_URL", gatewayUrl);
            pb2.start().waitFor();

            String claudeText2 = Files.isRegularFile(claudeJson) ? Files.readString(claudeJson) : "";
            int gatewayMentionsClaude = countOccurrences(claudeText2, "\"virtual-mcp-gateway\"");
            int gatewayMentionsCodex = countOccurrences(
                    Files.isRegularFile(codexToml) ? Files.readString(codexToml) : "",
                    "[mcp_servers.virtual_mcp_gateway]");

            boolean idempotentClaude = gatewayMentionsClaude == 1;
            boolean idempotentCodex = gatewayMentionsCodex == 1;

            boolean ok = rc == 0 && claudeHasEntry && claudeHasUrl
                    && codexHasEntry && codexHasUrl
                    && idempotentClaude && idempotentCodex;
            return (ok
                    ? NodeResult.pass("agent.configs.correct")
                    : NodeResult.fail("agent.configs.correct",
                            "rc=" + rc + " claudeEntry=" + claudeHasEntry + " claudeUrl=" + claudeHasUrl
                                    + " codexEntry=" + codexHasEntry + " codexUrl=" + codexHasUrl
                                    + " idempotentClaude=" + idempotentClaude
                                    + " idempotentCodex=" + idempotentCodex))
                    .assertion("install_ok", rc == 0)
                    .assertion("claude_json_has_gateway_entry", claudeHasEntry)
                    .assertion("claude_json_points_at_gateway_url", claudeHasUrl)
                    .assertion("codex_toml_has_gateway_table", codexHasEntry)
                    .assertion("codex_toml_points_at_gateway_url", codexHasUrl)
                    .assertion("claude_entry_not_duplicated", idempotentClaude)
                    .assertion("codex_entry_not_duplicated", idempotentCodex)
                    .metric("claudeGatewayMentions", gatewayMentionsClaude)
                    .metric("codexGatewayMentions", gatewayMentionsCodex);
        });
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) { count++; idx += needle.length(); }
        return count;
    }
}
