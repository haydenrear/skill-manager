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
 * Installs a fixture skill whose one MCP dep is a stdio server backed by
 * the {@code virtual-mcp-gateway/tests/fixtures/downstream_mcp_server.py}
 * script invoked via the gateway's venv python. Scope is global-sticky so
 * the gateway keeps one subprocess alive for the rest of the smoke run —
 * downstream parallel-invoke nodes hammer it to validate the worker-task
 * model in {@code StdioMCPClient}.
 */
public class EchoStdioSkillInstalled {
    static final NodeSpec SPEC = NodeSpec.of("echo.stdio.skill.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up", "gateway.python.venv.ready")
            .tags("mcp", "install", "stdio")
            .timeout("60s")
            .output("serverId", "string");

    private static final String SKILL_NAME = "echo-stdio-skill";
    private static final String SERVER_ID = "echo-stdio";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("echo.stdio.skill.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/echo-skill-stdio-template");
            Path destRoot = Path.of(home).resolve("fixtures");

            Path venvPy = repoRoot.resolve("virtual-mcp-gateway/.venv/bin/python");
            Path fixture = repoRoot.resolve("virtual-mcp-gateway/tests/fixtures/downstream_mcp_server.py");
            if (!Files.isExecutable(venvPy) || !Files.isRegularFile(fixture)) {
                return NodeResult.fail("echo.stdio.skill.installed",
                        "missing python or fixture: " + venvPy + " / " + fixture);
            }

            Path skillDir = TgFixture.stampEchoSkillStdio(
                    template, destRoot, SKILL_NAME, SERVER_ID, "global-sticky",
                    venvPy.toString(), fixture.toString());

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file:" + skillDir, "--yes")
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            pb.environment().put("CLAUDE_HOME", claudeHome);
            pb.environment().put("CODEX_HOME", codexHome);

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
            String body = out.toString();
            boolean sawDeployed = body.contains("\"" + SERVER_ID + "\"")
                    && (body.contains("\"status\" : \"deployed\"")
                        || body.contains("\"status\": \"deployed\"")
                        || body.contains("\"status\":\"deployed\""));

            return (rc == 0 && sawDeployed
                    ? NodeResult.pass("echo.stdio.skill.installed")
                    : NodeResult.fail("echo.stdio.skill.installed",
                            "rc=" + rc + " sawDeployed=" + sawDeployed))
                    .assertion("install_ok", rc == 0)
                    .assertion("stdio_mcp_deployed_transitively", sawDeployed)
                    .metric("stdoutBytes", body.length())
                    .publish("serverId", SERVER_ID);
        });
    }
}
