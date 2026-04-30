///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgFixture.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * End-to-end exercise of {@code skill-manager uninstall}: install a
 * dedicated fixture skill, then uninstall it and assert the three
 * cleanup contracts uninstall claims to provide:
 *
 * <ul>
 *   <li>store entry under {@code $SKILL_MANAGER_HOME/skills/<name>} is gone,</li>
 *   <li>both agent symlinks ({@code .claude/skills/<name>},
 *       {@code .codex/skills/<name>}) are removed,</li>
 *   <li>the orphan MCP server (no surviving skill references it) was
 *       unregistered from the gateway.</li>
 * </ul>
 *
 * <p>Uses a dedicated fixture skill name so this node can run without
 * disturbing the rest of the smoke graph.
 */
public class SkillUninstalled {
    static final NodeSpec SPEC = NodeSpec.of("skill.uninstalled")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("gateway.up", "echo.http.up")
            .tags("cli", "uninstall")
            .timeout("90s");

    private static final String SKILL_NAME = "uninstall-fixture-skill";
    private static final String SERVER_ID = "uninstall-fixture-echo";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || mcpUrl == null || gatewayUrl == null) {
                return NodeResult.fail("skill.uninstalled", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/echo-skill-template");
            Path destRoot = Path.of(home).resolve("fixtures");
            Path skillDir = TgFixture.stampEchoSkill(
                    template, destRoot, SKILL_NAME, SERVER_ID, "global-sticky", mcpUrl);

            // Install.
            int installRc = run(List.of(sm.toString(), "install", "file:" + skillDir),
                    home, claudeHome, codexHome, repoRoot);
            if (installRc != 0) {
                return NodeResult.fail("skill.uninstalled", "fixture install rc=" + installRc);
            }

            Path storeEntry = Path.of(home).resolve("skills").resolve(SKILL_NAME);
            Path claudeLink = Path.of(claudeHome).resolve(".claude").resolve("skills").resolve(SKILL_NAME);
            Path codexLink = Path.of(codexHome).resolve("skills").resolve(SKILL_NAME);
            boolean preStore = Files.isDirectory(storeEntry);
            boolean preClaudeLink = Files.exists(claudeLink, LinkOption.NOFOLLOW_LINKS);
            boolean preCodexLink = Files.exists(codexLink, LinkOption.NOFOLLOW_LINKS);
            if (!(preStore && preClaudeLink && preCodexLink)) {
                return NodeResult.fail("skill.uninstalled",
                        "post-install state missing — store=" + preStore
                                + " claudeLink=" + preClaudeLink + " codexLink=" + preCodexLink);
            }

            // Sanity-check the gateway saw the MCP server before uninstall.
            boolean preMcpListed;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-uninstall-pre-" + ctx.runId())) {
                preMcpListed = serverListed(mcp, SERVER_ID);
            }

            // Uninstall.
            int uninstallRc = run(List.of(sm.toString(), "uninstall", SKILL_NAME),
                    home, claudeHome, codexHome, repoRoot);

            boolean storeGone = !Files.exists(storeEntry, LinkOption.NOFOLLOW_LINKS);
            boolean claudeLinkGone = !Files.exists(claudeLink, LinkOption.NOFOLLOW_LINKS);
            boolean codexLinkGone = !Files.exists(codexLink, LinkOption.NOFOLLOW_LINKS);

            // The gateway-side unregister is best-effort in the CLI; it
            // logs a warning and continues if anything goes wrong, so we
            // poll briefly to absorb async refresh latency.
            boolean mcpUnregistered = false;
            for (int attempt = 0; attempt < 5; attempt++) {
                try (TgMcp mcp = new TgMcp(gatewayUrl, "test-uninstall-post-" + ctx.runId() + "-" + attempt)) {
                    if (!serverListed(mcp, SERVER_ID)) { mcpUnregistered = true; break; }
                }
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            }

            boolean pass = uninstallRc == 0 && storeGone && claudeLinkGone && codexLinkGone
                    && preMcpListed && mcpUnregistered;
            return (pass
                    ? NodeResult.pass("skill.uninstalled")
                    : NodeResult.fail("skill.uninstalled",
                            "rc=" + uninstallRc + " storeGone=" + storeGone
                                    + " claudeLinkGone=" + claudeLinkGone
                                    + " codexLinkGone=" + codexLinkGone
                                    + " preMcpListed=" + preMcpListed
                                    + " mcpUnregistered=" + mcpUnregistered))
                    .assertion("uninstall_exit_zero", uninstallRc == 0)
                    .assertion("store_entry_removed", storeGone)
                    .assertion("claude_symlink_removed", claudeLinkGone)
                    .assertion("codex_symlink_removed", codexLinkGone)
                    .assertion("mcp_server_was_listed_before", preMcpListed)
                    .assertion("orphan_mcp_unregistered", mcpUnregistered);
        });
    }

    private static int run(List<String> argv, String home, String claudeHome,
                           String codexHome, Path repoRoot) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", claudeHome);
        pb.environment().put("CODEX_HOME", codexHome);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
        }
        return p.waitFor();
    }

    private static boolean serverListed(TgMcp mcp, String serverId) {
        Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
        Object items = res.get("items");
        return items instanceof List<?> list
                && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                        && serverId.equals(m.get("server_id")));
    }
}
