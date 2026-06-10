///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * End-to-end force-sync coverage for a skill that has both a skill-script CLI
 * dependency and an MCP dependency. The script writes a run counter so the
 * graph proves noop sync skips and --force-scripts sync reruns.
 */
public class SkillScriptForceSyncWithMcp {
    static final String SKILL = "skill-script-skill";
    static final String SERVER_ID = "skill-script-force-sync-mcp";
    static final String TOOL = "skill-script-touched";
    static final String COUNT = "skill-script-force-sync-mcp.count";

    static final NodeSpec SPEC = NodeSpec.of("skill.script.force.sync.with.mcp")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared", "gateway.up", "echo.http.up")
            .tags("cli", "skill-script", "sync", "mcp", "force")
            .timeout("240s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String envHome = ctx.get("env.prepared", "home").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (envHome == null || mcpUrl == null || gatewayUrl == null) {
                return NodeResult.fail("skill.script.force.sync.with.mcp",
                        "missing env.prepared, gateway.up, or echo.http.up context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path srcFixture = repoRoot.resolve("test_graph/fixtures/skill-script-skill");
            Path privateRoot = Path.of(envHome).resolve("skill-script-force-sync-mcp");
            Path storeHome = privateRoot.resolve("home");
            Path privateClaude = privateRoot.resolve("claude");
            Path privateCodex = privateRoot.resolve("codex");
            Path privateGemini = privateRoot.resolve("gemini");
            Path fixtureCopy = privateRoot.resolve("fixture");
            try {
                Files.createDirectories(storeHome);
                Files.createDirectories(privateClaude);
                Files.createDirectories(privateCodex);
                Files.createDirectories(privateGemini);
                copyDir(srcFixture, fixtureCopy);
                writeCountingScript(fixtureCopy.resolve("skill-scripts/install.sh"));
                appendMcpDependency(fixtureCopy.resolve("skill-manager.toml"), mcpUrl);
            } catch (IOException e) {
                return NodeResult.fail("skill.script.force.sync.with.mcp",
                        "fixture setup failed: " + e.getMessage());
            }

            ProcessRecord install = Procs.run(ctx, "install",
                    smProc(sm, repoRoot, storeHome, privateClaude, privateCodex, privateGemini,
                            "install", fixtureCopy.toString(), "--yes"));
            int countAfterInstall = readCount(storeHome);

            ProcessRecord syncNoop = Procs.run(ctx, "sync_noop",
                    smProc(sm, repoRoot, storeHome, privateClaude, privateCodex, privateGemini,
                            "sync", "--from", fixtureCopy.toString(), SKILL, "--yes"));
            String noopLog = readLog(ctx.reportDir(), syncNoop);
            int countAfterNoop = readCount(storeHome);

            ProcessRecord syncForce = Procs.run(ctx, "sync_force",
                    smProc(sm, repoRoot, storeHome, privateClaude, privateCodex, privateGemini,
                            "sync", "--from", fixtureCopy.toString(), SKILL,
                            "--yes", "--force-scripts"));
            String forceLog = readLog(ctx.reportDir(), syncForce);
            int countAfterForce = readCount(storeHome);

            boolean binaryExecutable = Files.isExecutable(storeHome.resolve("bin/cli").resolve(TOOL));
            boolean noopSkipped = noopLog.contains("scripts unchanged since last install");
            boolean noopDidNotRerun = countAfterNoop == countAfterInstall;
            boolean forceReran = countAfterForce == countAfterInstall + 1
                    && forceLog.contains("force rerun requested");
            boolean noopMcpRegistered = hasMcpResults(noopLog);
            boolean forceMcpRegistered = hasMcpResults(forceLog);
            boolean forceMcpDeployed = forceLog.contains("\"serverId\" : \"" + SERVER_ID + "\"")
                    && forceLog.contains("\"status\" : \"deployed\"");

            boolean pass = install.exitCode() == 0
                    && syncNoop.exitCode() == 0
                    && syncForce.exitCode() == 0
                    && countAfterInstall == 1
                    && binaryExecutable
                    && noopSkipped
                    && noopDidNotRerun
                    && forceReran
                    && noopMcpRegistered
                    && forceMcpRegistered
                    && forceMcpDeployed;
            NodeResult result = pass
                    ? NodeResult.pass("skill.script.force.sync.with.mcp")
                    : NodeResult.fail("skill.script.force.sync.with.mcp",
                            "install=" + install.exitCode()
                                    + " syncNoop=" + syncNoop.exitCode()
                                    + " syncForce=" + syncForce.exitCode()
                                    + " countInstall=" + countAfterInstall
                                    + " countNoop=" + countAfterNoop
                                    + " countForce=" + countAfterForce
                                    + " binary=" + binaryExecutable
                                    + " noopSkipped=" + noopSkipped
                                    + " forceReran=" + forceReran
                                    + " noopMcp=" + noopMcpRegistered
                                    + " forceMcp=" + forceMcpRegistered
                                    + " forceMcpDeployed=" + forceMcpDeployed);
            return result
                    .process(install).process(syncNoop).process(syncForce)
                    .assertion("install_exit_zero", install.exitCode() == 0)
                    .assertion("sync_noop_exit_zero", syncNoop.exitCode() == 0)
                    .assertion("sync_force_exit_zero", syncForce.exitCode() == 0)
                    .assertion("initial_script_run_count_one", countAfterInstall == 1)
                    .assertion("declared_binary_executable", binaryExecutable)
                    .assertion("noop_sync_skipped_script", noopSkipped)
                    .assertion("noop_sync_did_not_increment_counter", noopDidNotRerun)
                    .assertion("force_sync_incremented_counter", forceReran)
                    .assertion("noop_sync_emitted_mcp_results", noopMcpRegistered)
                    .assertion("force_sync_emitted_mcp_results", forceMcpRegistered)
                    .assertion("force_sync_mcp_result_deployed", forceMcpDeployed)
                    .metric("scriptRunCountAfterInstall", countAfterInstall)
                    .metric("scriptRunCountAfterNoopSync", countAfterNoop)
                    .metric("scriptRunCountAfterForceSync", countAfterForce);
        });
    }

    private static ProcessBuilder smProc(Path sm, Path repoRoot, Path storeHome,
                                         Path privateClaude, Path privateCodex,
                                         Path privateGemini,
                                         String... cliArgs) {
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(sm.toString());
        for (String arg : cliArgs) argv.add(arg);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().put("SKILL_MANAGER_HOME", storeHome.toString());
        pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
        pb.environment().put("CLAUDE_HOME", privateClaude.toString());
        pb.environment().put("CODEX_HOME", privateCodex.toString());
        pb.environment().put("GEMINI_HOME", privateGemini.toString());
        return pb;
    }

    private static void appendMcpDependency(Path toml, String mcpUrl) throws IOException {
        Files.writeString(toml, """

                [[mcp_dependencies]]
                name = "%s"
                display_name = "skill script force sync MCP"
                description = "MCP dep used by skill-script force-sync smoke coverage."
                default_scope = "global-sticky"
                load = { type = "binary", transport = "streamable-http", url = "%s", install = {} }
                """.formatted(SERVER_ID, tomlEscape(mcpUrl)), java.nio.file.StandardOpenOption.APPEND);
    }

    private static void writeCountingScript(Path script) throws IOException {
        Files.writeString(script, """
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
                echo "skill-script-force-sync-mcp: run $n"
                """.formatted(TOOL, COUNT));
    }

    private static int readCount(Path storeHome) {
        try {
            Path count = storeHome.resolve("bin/cli").resolve(COUNT);
            if (!Files.isRegularFile(count)) return 0;
            return Integer.parseInt(Files.readString(count).trim());
        } catch (Exception e) {
            return -1;
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

    private static String tomlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        if (Files.exists(dst)) {
            try (var s = Files.walk(dst)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a)
                    throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                    throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
