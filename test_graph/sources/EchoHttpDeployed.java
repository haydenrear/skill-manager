///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Deploys the registered echo server and asserts the gateway-side tool cache
 * picked up the echo tool. Uses the MCP SDK-backed {@code gateway tools}
 * command to confirm the tool surfaces through the real pass-through path.
 */
public class EchoHttpDeployed {
    static final NodeSpec SPEC = NodeSpec.of("echo.http.deployed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("echo.http.registered")
            .tags("mcp", "http", "deploy")
            .timeout("45s")
            .output("toolPath", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.http.registered", "serverId").orElse(null);
            if (home == null || gatewayUrl == null || serverId == null) {
                return NodeResult.fail("echo.http.deployed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // 1. Deploy (no init params needed for the echo fixture).
            ProcessBuilder deployPb = new ProcessBuilder(
                    sm.toString(), "gateway", "deploy", serverId,
                    "--gateway", gatewayUrl)
                    .inheritIO();
            deployPb.environment().put("SKILL_MANAGER_HOME", home);
            deployPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            int deployRc = deployPb.start().waitFor();
            if (deployRc != 0) {
                return NodeResult.fail("echo.http.deployed", "deploy exited " + deployRc);
            }

            // 2. Force a registry refresh so the downstream tool list is current.
            ProcessBuilder refreshPb = new ProcessBuilder(
                    sm.toString(), "gateway", "refresh",
                    "--gateway", gatewayUrl)
                    .redirectErrorStream(true);
            refreshPb.environment().put("SKILL_MANAGER_HOME", home);
            refreshPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            refreshPb.start().waitFor();

            // 3. List active tools; assert echo surfaced.
            ProcessBuilder toolsPb = new ProcessBuilder(
                    sm.toString(), "gateway", "tools",
                    "--gateway", gatewayUrl)
                    .redirectErrorStream(true);
            toolsPb.environment().put("SKILL_MANAGER_HOME", home);
            toolsPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            StringBuilder out = new StringBuilder();
            Process toolsProc = toolsPb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(toolsProc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            int toolsRc = toolsProc.waitFor();
            String body = out.toString();
            boolean echoPresent = body.contains("\"echo\"") && body.contains("\"" + serverId + "\"");

            return (toolsRc == 0 && echoPresent
                    ? NodeResult.pass("echo.http.deployed")
                    : NodeResult.fail("echo.http.deployed", "toolsRc=" + toolsRc + " echo=" + echoPresent))
                    .assertion("deploy_ok", deployRc == 0)
                    .assertion("tools_listed_ok", toolsRc == 0)
                    .assertion("echo_tool_in_active_tools", echoPresent)
                    .metric("toolsOutputBytes", body.length())
                    .publish("toolPath", "echo");
        });
    }
}
