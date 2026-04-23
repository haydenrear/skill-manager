///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * Registers a dummy docker MCP server with the gateway via REST. Uses
 * {@code --pull=false} so we don't fetch an image during the test; what
 * we're validating here is the registration path (POST /servers, payload
 * persisted to {@code dynamic-servers.json}, server_id visible via the
 * MCP browse tool).
 */
public class McpRegistered {
    static final NodeSpec SPEC = NodeSpec.of("mcp.registered")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up")
            .tags("gateway", "mcp")
            .timeout("45s")
            .output("serverId", "string");

    private static final String SERVER_ID = "test-graph-probe";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (home == null || gatewayUrl == null) {
                return NodeResult.fail("mcp.registered", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "gateway", "register", SERVER_ID,
                    "--display-name", "TestGraph Probe",
                    "--docker", "alpine:3", "--pull=false",
                    "--gateway", gatewayUrl)
                    .inheritIO();
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc;
            try {
                rc = pb.start().waitFor();
            } catch (Exception e) {
                return NodeResult.error("mcp.registered", e);
            }
            return (rc == 0
                    ? NodeResult.pass("mcp.registered")
                    : NodeResult.fail("mcp.registered", "gateway register exited " + rc))
                    .assertion("registered_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("serverId", SERVER_ID);
        });
    }
}
