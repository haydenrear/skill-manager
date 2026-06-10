///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../../lib/TgFixture.java
//SOURCES ../../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Plugin-level force-sync coverage for a plugin that combines pip CLI, MCP,
 * and a skill-script CLI dependency. The plugin script writes a run counter so
 * the graph can distinguish noop sync from --force-scripts sync.
 */
public class PluginSkillScriptForceSync {
    static final String PLUGIN = "force-sync-plugin";
    static final String PLUGIN_SERVER_ID = "force-sync-plugin-server";
    static final String SKILL_SERVER_ID = "force-sync-plugin-inner-server";
    static final String TOOL = "plugin-force-sync-marker";
    static final String COUNT = "plugin-force-sync-marker.count";

    static final NodeSpec SPEC = NodeSpec.of("plugin.skill_script.force.sync")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("plugin.uninstalled.mixed.orphans", "gateway.up", "echo.http.up")
            .tags("plugin", "sync", "skill-script", "cli", "mcp", "force")
            .timeout("600s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null
                    || mcpUrl == null || gatewayUrl == null) {
                return NodeResult.fail("plugin.skill_script.force.sync",
                        "missing env.prepared, gateway.up, or echo.http.up context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/umbrella-plugin-template");
            Path destRoot = Path.of(home).resolve("fixtures");
            Path pluginDir;
            try {
                pluginDir = TgFixture.stampUmbrellaPlugin(
                        template, destRoot, PLUGIN,
                        PLUGIN_SERVER_ID, SKILL_SERVER_ID,
                        "global-sticky", mcpUrl);
                addPluginLevelScriptDep(pluginDir);
            } catch (IOException e) {
                return NodeResult.fail("plugin.skill_script.force.sync",
                        "plugin fixture setup failed: " + e.getMessage());
            }

            ProcessRecord install = Procs.run(ctx, "install",
                    smProc(sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                            "install", "file:" + pluginDir, "--yes"));
            int countAfterInstall = readCount(home);

            ProcessRecord syncNoop = Procs.run(ctx, "sync_noop",
                    smProc(sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                            "sync", "--from", pluginDir.toString(), PLUGIN, "--yes"));
            String noopLog = readLog(ctx.reportDir(), syncNoop);
            int countAfterNoop = readCount(home);

            ProcessRecord syncForce = Procs.run(ctx, "sync_force",
                    smProc(sm, repoRoot, home, claudeHome, codexHome, geminiHome,
                            "sync", "--from", pluginDir.toString(), PLUGIN,
                            "--yes", "--force-scripts"));
            String forceLog = readLog(ctx.reportDir(), syncForce);
            int countAfterForce = readCount(home);

            Path cliDir = Path.of(home).resolve("bin").resolve("cli");
            boolean scriptBinaryPresent = Files.isExecutable(cliDir.resolve(TOOL));
            boolean pipCliPresent = Files.isExecutable(cliDir.resolve("pycowsay"))
                    && Files.isExecutable(cliDir.resolve("cowsay"));
            String lockBody = readFile(Path.of(home).resolve("cli-lock.toml"));
            boolean scriptLockPresent = lockBody.contains("[\"skill-script\".\"" + TOOL + "\"]")
                    && lockBody.contains(PLUGIN);
            boolean noopSkipped = noopLog.contains("scripts unchanged since last install");
            boolean noopDidNotRerun = countAfterNoop == countAfterInstall;
            boolean forceReran = countAfterForce == countAfterInstall + 1
                    && forceLog.contains("force rerun requested");
            boolean noopMcpRegistered = hasMcpResults(noopLog);
            boolean forceMcpRegistered = hasMcpResults(forceLog);
            boolean pluginServerListed;
            boolean containedServerListed;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "plugin-force-sync-" + ctx.runId())) {
                pluginServerListed = serverListed(mcp, PLUGIN_SERVER_ID);
                containedServerListed = serverListed(mcp, SKILL_SERVER_ID);
            }

            boolean pass = install.exitCode() == 0
                    && syncNoop.exitCode() == 0
                    && syncForce.exitCode() == 0
                    && countAfterInstall == 1
                    && scriptBinaryPresent
                    && pipCliPresent
                    && scriptLockPresent
                    && noopSkipped
                    && noopDidNotRerun
                    && forceReran
                    && noopMcpRegistered
                    && forceMcpRegistered
                    && pluginServerListed
                    && containedServerListed;
            NodeResult result = pass
                    ? NodeResult.pass("plugin.skill_script.force.sync")
                    : NodeResult.fail("plugin.skill_script.force.sync",
                            "install=" + install.exitCode()
                                    + " syncNoop=" + syncNoop.exitCode()
                                    + " syncForce=" + syncForce.exitCode()
                                    + " countInstall=" + countAfterInstall
                                    + " countNoop=" + countAfterNoop
                                    + " countForce=" + countAfterForce
                                    + " scriptBinary=" + scriptBinaryPresent
                                    + " pipCli=" + pipCliPresent
                                    + " scriptLock=" + scriptLockPresent
                                    + " noopSkipped=" + noopSkipped
                                    + " forceReran=" + forceReran
                                    + " noopMcp=" + noopMcpRegistered
                                    + " forceMcp=" + forceMcpRegistered
                                    + " pluginServer=" + pluginServerListed
                                    + " containedServer=" + containedServerListed);
            return result
                    .process(install).process(syncNoop).process(syncForce)
                    .assertion("install_exit_zero", install.exitCode() == 0)
                    .assertion("sync_noop_exit_zero", syncNoop.exitCode() == 0)
                    .assertion("sync_force_exit_zero", syncForce.exitCode() == 0)
                    .assertion("initial_plugin_script_run_count_one", countAfterInstall == 1)
                    .assertion("plugin_skill_script_binary_present", scriptBinaryPresent)
                    .assertion("plugin_pip_cli_binaries_present", pipCliPresent)
                    .assertion("plugin_skill_script_cli_lock_present", scriptLockPresent)
                    .assertion("noop_plugin_sync_skipped_script", noopSkipped)
                    .assertion("noop_plugin_sync_did_not_increment_counter", noopDidNotRerun)
                    .assertion("force_plugin_sync_incremented_counter", forceReran)
                    .assertion("noop_plugin_sync_emitted_mcp_results", noopMcpRegistered)
                    .assertion("force_plugin_sync_emitted_mcp_results", forceMcpRegistered)
                    .assertion("plugin_level_mcp_registered", pluginServerListed)
                    .assertion("contained_skill_mcp_registered", containedServerListed)
                    .metric("scriptRunCountAfterInstall", countAfterInstall)
                    .metric("scriptRunCountAfterNoopSync", countAfterNoop)
                    .metric("scriptRunCountAfterForceSync", countAfterForce);
        });
    }

    private static ProcessBuilder smProc(Path sm, Path repoRoot, String home,
                                         String claudeHome, String codexHome,
                                         String geminiHome,
                                         String... cliArgs) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(sm.toString());
        for (String arg : cliArgs) argv.add(arg);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("SKILL_MANAGER_HOME", home);
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", claudeHome);
        pb.environment().put("CODEX_HOME", codexHome);
        pb.environment().put("GEMINI_HOME", geminiHome);
        pb.environment().put("CLAUDE_CONFIG_DIR",
                Path.of(claudeHome).resolve(".claude").toString());
        pb.environment().put("SKILL_MANAGER_POLICY_INSTALL_REQUIRE_CONFIRMATION_FOR_CLI_DEPS",
                "false");
        return pb;
    }

    private static void addPluginLevelScriptDep(Path pluginDir) throws IOException {
        Path scriptsDir = pluginDir.resolve("skill-scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("force-sync.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail

                : "${SKILL_MANAGER_BIN_DIR:?SKILL_MANAGER_BIN_DIR is required}"
                mkdir -p "$SKILL_MANAGER_BIN_DIR"
                marker="$SKILL_MANAGER_BIN_DIR/%s"
                count_file="$SKILL_MANAGER_BIN_DIR/%s"
                n=0
                if [[ -f "$count_file" ]]; then
                  n="$(cat "$count_file")"
                fi
                n=$((n + 1))
                touch "$marker"
                chmod +x "$marker"
                printf '%%s\\n' "$n" > "$count_file"
                echo "plugin-skill-script-force-sync: run $n"
                """.formatted(TOOL, COUNT));

        Files.writeString(pluginDir.resolve("skill-manager-plugin.toml"), """

                [[cli_dependencies]]
                spec = "skill-script:%s"
                on_path = "__zzz_nope_%s"

                [cli_dependencies.install.any]
                script = "force-sync.sh"
                binary = "%s"
                """.formatted(TOOL, TOOL, TOOL), StandardOpenOption.APPEND);
    }

    private static int readCount(String home) {
        try {
            Path count = Path.of(home).resolve("bin/cli").resolve(COUNT);
            if (!Files.isRegularFile(count)) return 0;
            return Integer.parseInt(Files.readString(count).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean hasMcpResults(String log) {
        return log.contains("---MCP-INSTALL-RESULTS-BEGIN---")
                && log.contains("---MCP-INSTALL-RESULTS-END---");
    }

    private static String readLog(Path reportDir, ProcessRecord proc) {
        try {
            String log = proc.logPath();
            if (log == null || log.isBlank()) return "";
            Path p = Path.of(log);
            if (!p.isAbsolute() && reportDir != null) p = reportDir.resolve(p);
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean serverListed(TgMcp mcp, String serverId) {
        Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
        Object items = res.get("items");
        return items instanceof List<?> list
                && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                        && serverId.equals(m.get("server_id")));
    }
}
