///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgFixture.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Installs the umbrella-plugin fixture and asserts feature parity with
 * bare skills: the install pipeline must walk the plugin's
 * {@code skill-manager-plugin.toml} AND every contained skill's
 * {@code skill-manager.toml}, registering every CLI / MCP entry it
 * finds. The fixture deliberately puts a CLI dep + MCP dep at each
 * level (plugin and contained) so a single install must produce four
 * distinct registrations.
 *
 * <p>Specifically:
 * <ul>
 *   <li>Plugin-level CLI ({@code pip:pycowsay}) → binary at
 *       {@code bin/cli/pycowsay} + {@code cli-lock.toml} row.</li>
 *   <li>Contained-skill CLI ({@code pip:cowsay==6.0}) → binary at
 *       {@code bin/cli/cowsay} + {@code cli-lock.toml} row.</li>
 *   <li>Plugin-level MCP server ({@code <plugin-server-id>}) →
 *       registered with the gateway.</li>
 *   <li>Contained-skill MCP server ({@code <skill-server-id>}) →
 *       registered with the gateway.</li>
 * </ul>
 *
 * <p>Publishes the fixture's identifiers downstream so {@link
 * PluginSynced}, {@link PartnerSkillInstalled}, and {@link
 * PluginUninstalledMixedOrphans} can target them without re-stamping.
 */
public class UmbrellaPluginInstalled {
    static final NodeSpec SPEC = NodeSpec.of("umbrella.plugin.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hello.plugin.installed", "echo.http.up")
            .tags("plugin", "install", "cli", "mcp")
            .timeout("600s")
            .output("pluginName", "string")
            .output("pluginServerId", "string")
            .output("skillServerId", "string")
            .output("pluginCliBinary", "string")
            .output("skillCliBinary", "string");

    private static final String PLUGIN_NAME = "umbrella-plugin";
    private static final String PLUGIN_SERVER_ID = "umbrella-plugin-server";
    private static final String SKILL_SERVER_ID = "umbrella-plugin-inner-server";
    private static final String PLUGIN_CLI_BIN = "pycowsay";
    private static final String SKILL_CLI_BIN = "cowsay";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null
                    || mcpUrl == null || gatewayUrl == null) {
                return NodeResult.fail("umbrella.plugin.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/umbrella-plugin-template");
            Path destRoot = Path.of(home).resolve("fixtures");

            Path pluginDir = TgFixture.stampUmbrellaPlugin(
                    template, destRoot, PLUGIN_NAME,
                    PLUGIN_SERVER_ID, SKILL_SERVER_ID,
                    "global-sticky", mcpUrl);

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file:" + pluginDir, "--yes");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);
            pb.environment().put("CLAUDE_CONFIG_DIR",
                    Path.of(claudeHome).resolve(".claude").toString());
            // Both deps are CLI; the policy gate would block --yes
            // otherwise. Tests are explicitly permissive here.
            pb.environment().put("SKILL_MANAGER_POLICY_INSTALL_REQUIRE_CONFIRMATION_FOR_CLI_DEPS", "false");

            ProcessRecord proc = Procs.run(ctx, "install", pb);
            int rc = proc.exitCode();

            // Plugin lands under plugins/.
            Path pluginStoreDir = Path.of(home).resolve("plugins").resolve(PLUGIN_NAME);
            boolean pluginInStore = Files.isDirectory(pluginStoreDir);

            // Both CLI binaries should be present in bin/cli/.
            Path cliDir = Path.of(home).resolve("bin").resolve("cli");
            boolean pluginCliPresent = Files.isExecutable(cliDir.resolve(PLUGIN_CLI_BIN));
            boolean skillCliPresent = Files.isExecutable(cliDir.resolve(SKILL_CLI_BIN));

            // cli-lock should record both.
            Path lockPath = Path.of(home).resolve("cli-lock.toml");
            String lockBody = Files.isRegularFile(lockPath) ? Files.readString(lockPath) : "";
            boolean pluginCliLocked = lockBody.contains("[\"pip\".\"" + PLUGIN_CLI_BIN + "\"]");
            boolean skillCliLocked = lockBody.contains("[\"pip\".\"" + SKILL_CLI_BIN + "\"]");

            // Both MCP servers should be visible via the gateway.
            boolean pluginMcpListed;
            boolean skillMcpListed;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "umbrella-plugin-installed-" + ctx.runId())) {
                pluginMcpListed = serverListed(mcp, PLUGIN_SERVER_ID);
                skillMcpListed = serverListed(mcp, SKILL_SERVER_ID);
            }

            boolean pass = rc == 0 && pluginInStore
                    && pluginCliPresent && skillCliPresent
                    && pluginCliLocked && skillCliLocked
                    && pluginMcpListed && skillMcpListed;
            NodeResult result = pass
                    ? NodeResult.pass("umbrella.plugin.installed")
                    : NodeResult.fail("umbrella.plugin.installed",
                            "rc=" + rc + " store=" + pluginInStore
                                    + " pluginCli=" + pluginCliPresent
                                    + " skillCli=" + skillCliPresent
                                    + " pluginCliLocked=" + pluginCliLocked
                                    + " skillCliLocked=" + skillCliLocked
                                    + " pluginMcp=" + pluginMcpListed
                                    + " skillMcp=" + skillMcpListed);
            return result
                    .process(proc)
                    .assertion("install_exit_zero", rc == 0)
                    .assertion("plugin_in_store", pluginInStore)
                    .assertion("plugin_level_cli_binary_installed", pluginCliPresent)
                    .assertion("contained_skill_cli_binary_installed", skillCliPresent)
                    .assertion("plugin_level_cli_in_lock", pluginCliLocked)
                    .assertion("contained_skill_cli_in_lock", skillCliLocked)
                    .assertion("plugin_level_mcp_registered_with_gateway", pluginMcpListed)
                    .assertion("contained_skill_mcp_registered_with_gateway", skillMcpListed)
                    .metric("exitCode", rc)
                    .publish("pluginName", PLUGIN_NAME)
                    .publish("pluginServerId", PLUGIN_SERVER_ID)
                    .publish("skillServerId", SKILL_SERVER_ID)
                    .publish("pluginCliBinary", PLUGIN_CLI_BIN)
                    .publish("skillCliBinary", SKILL_CLI_BIN);
        });
    }

    private static boolean serverListed(TgMcp mcp, String serverId) {
        Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
        Object items = res.get("items");
        return items instanceof List<?> list
                && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                        && serverId.equals(m.get("server_id")));
    }
}
