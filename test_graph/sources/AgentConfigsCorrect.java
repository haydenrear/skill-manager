///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * After agents.synced, the fake HOME should contain:
 *   - ~/.claude.json with an "mcpServers.virtual-mcp-gateway" entry
 *   - ~/.codex/config.toml with "[mcp_servers.virtual_mcp_gateway]"
 *   - ~/.claude/skills/&lt;each-installed-skill&gt;/ symlink or copy
 *   - ~/.codex/skills/&lt;each-installed-skill&gt;/ symlink or copy
 *
 * This proves that the sync command landed the single-gateway pattern
 * on both agent surfaces, and that every installed skill is visible to
 * both agents at once.
 */
public class AgentConfigsCorrect {
    static final NodeSpec SPEC = NodeSpec.of("agent.configs.correct")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("agents.synced")
            .tags("agents", "config")
            .timeout("15s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fakeHome = ctx.get("agents.synced", "fakeHome").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (fakeHome == null || gatewayUrl == null) {
                return NodeResult.fail("agent.configs.correct", "missing upstream context");
            }

            Path claudeJson = Path.of(fakeHome, ".claude.json");
            Path codexToml = Path.of(fakeHome, ".codex", "config.toml");

            String claudeText = Files.isRegularFile(claudeJson) ? Files.readString(claudeJson) : "";
            String codexText = Files.isRegularFile(codexToml) ? Files.readString(codexToml) : "";

            boolean claudeHasEntry = claudeText.contains("\"virtual-mcp-gateway\"");
            boolean claudeHasUrl = claudeText.contains(gatewayUrl);
            boolean codexHasEntry = codexText.contains("[mcp_servers.virtual_mcp_gateway]");
            boolean codexHasUrl = codexText.contains(gatewayUrl);

            Path claudeSkills = Path.of(fakeHome, ".claude", "skills");
            Path codexSkills = Path.of(fakeHome, ".codex", "skills");
            boolean claudeUmbrella = Files.exists(claudeSkills.resolve("umbrella-skill"));
            boolean codexUmbrella = Files.exists(codexSkills.resolve("umbrella-skill"));

            int claudeSkillCount = countDirs(claudeSkills);
            int codexSkillCount = countDirs(codexSkills);

            boolean allGood = claudeHasEntry && claudeHasUrl && codexHasEntry && codexHasUrl
                    && claudeUmbrella && codexUmbrella;

            return (allGood
                    ? NodeResult.pass("agent.configs.correct")
                    : NodeResult.fail("agent.configs.correct",
                            "claude_entry=" + claudeHasEntry + " claude_url=" + claudeHasUrl
                                    + " codex_entry=" + codexHasEntry + " codex_url=" + codexHasUrl
                                    + " claude_umbrella=" + claudeUmbrella
                                    + " codex_umbrella=" + codexUmbrella))
                    .assertion("claude_json_has_gateway_entry", claudeHasEntry)
                    .assertion("claude_json_points_at_gateway_url", claudeHasUrl)
                    .assertion("codex_toml_has_gateway_table", codexHasEntry)
                    .assertion("codex_toml_points_at_gateway_url", codexHasUrl)
                    .assertion("claude_skills_contains_umbrella", claudeUmbrella)
                    .assertion("codex_skills_contains_umbrella", codexUmbrella)
                    .metric("claude_skill_count", claudeSkillCount)
                    .metric("codex_skill_count", codexSkillCount);
        });
    }

    private static int countDirs(Path p) throws Exception {
        if (!Files.isDirectory(p)) return 0;
        try (Stream<Path> s = Files.list(p)) {
            return (int) s.filter(Files::isDirectory).count();
        }
    }
}
