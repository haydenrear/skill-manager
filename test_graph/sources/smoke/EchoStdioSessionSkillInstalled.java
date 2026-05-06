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
 * Installs a fixture skill whose stdio MCP dep declares
 * {@code default_scope = "session"}. Same shape as the global-sticky
 * stdio install, but the gateway will only spawn the subprocess when an
 * agent calls {@code deploy_mcp_server} from a session — so install-time
 * status is "registered", not "deployed".
 *
 * <p>Downstream: the parallel-session node deploys it explicitly and
 * fires concurrent calls within that session.
 */
public class EchoStdioSessionSkillInstalled {
    static final NodeSpec SPEC = NodeSpec.of("echo.stdio.session_skill.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up", "gateway.python.venv.ready")
            .tags("mcp", "install", "stdio", "session")
            .timeout("60s")
            .output("serverId", "string");

    private static final String SKILL_NAME = "echo-stdio-session-skill";
    private static final String SERVER_ID = "echo-stdio-session";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            if (home == null || claudeHome == null || codexHome == null) {
                return NodeResult.fail("echo.stdio.session_skill.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/echo-skill-stdio-template");
            Path destRoot = Path.of(home).resolve("fixtures");

            Path venvPy = repoRoot.resolve("virtual-mcp-gateway/.venv/bin/python");
            Path fixture = repoRoot.resolve("virtual-mcp-gateway/tests/fixtures/downstream_mcp_server.py");
            if (!Files.isExecutable(venvPy) || !Files.isRegularFile(fixture)) {
                return NodeResult.fail("echo.stdio.session_skill.installed",
                        "missing python or fixture: " + venvPy + " / " + fixture);
            }

            Path skillDir = TgFixture.stampEchoSkillStdio(
                    template, destRoot, SKILL_NAME, SERVER_ID, "session",
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
            boolean sawRegistered = body.contains("\"" + SERVER_ID + "\"")
                    && (body.contains("\"status\" : \"registered\"")
                        || body.contains("\"status\": \"registered\"")
                        || body.contains("\"status\":\"registered\""));

            return (rc == 0 && sawRegistered
                    ? NodeResult.pass("echo.stdio.session_skill.installed")
                    : NodeResult.fail("echo.stdio.session_skill.installed",
                            "rc=" + rc + " sawRegistered=" + sawRegistered))
                    .assertion("install_ok", rc == 0)
                    .assertion("status_registered_not_deployed", sawRegistered)
                    .metric("stdoutBytes", body.length())
                    .publish("serverId", SERVER_ID);
        });
    }
}
