///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgFixture.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Installs a fixture skill whose MCP dep declares
 * {@code default_scope = "global"}. No required init → should auto-deploy
 * on register. The assertion downstream reads the gateway through MCP to
 * confirm it's visible to every session.
 */
public class EchoGlobalSkillInstalled {
    static final NodeSpec SPEC = NodeSpec.of("echo.global_skill.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up", "echo.http.up")
            .tags("mcp", "scope", "global", "install")
            .timeout("45s")
            .output("serverId", "string");

    private static final String SKILL_NAME = "echo-global-skill";
    private static final String SERVER_ID = "echo-http-global";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || mcpUrl == null) {
                return NodeResult.fail("echo.global_skill.installed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path template = repoRoot.resolve("test_graph/fixtures/echo-skill-template");
            Path destRoot = Path.of(home).resolve("fixtures");

            Path skillDir = TgFixture.stampEchoSkill(
                    template, destRoot, SKILL_NAME, SERVER_ID, "global", mcpUrl);

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "install", "file:" + skillDir)
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
                    ? NodeResult.pass("echo.global_skill.installed")
                    : NodeResult.fail("echo.global_skill.installed",
                            "rc=" + rc + " sawDeployed=" + sawDeployed))
                    .assertion("install_ok", rc == 0)
                    .assertion("status_deployed_at_install", sawDeployed)
                    .metric("stdoutBytes", body.length())
                    .publish("serverId", SERVER_ID);
        });
    }
}
