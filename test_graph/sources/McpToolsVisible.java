///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Talks MCP to the gateway via the real Java SDK path: invokes
 * {@code skill-manager gateway servers}, asserts the server we just
 * registered is reported. This exercises protocol negotiation, session
 * headers, and JSON deserialization end-to-end — not just the REST API.
 */
public class McpToolsVisible {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tools.visible")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("mcp.registered")
            .tags("gateway", "mcp", "sdk")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("mcp.registered", "serverId").orElse(null);
            if (home == null || gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.tools.visible", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "gateway", "servers",
                    "--gateway", gatewayUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            StringBuilder out = new StringBuilder();
            int rc;
            try {
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                }
                rc = p.waitFor();
            } catch (Exception e) {
                return NodeResult.error("mcp.tools.visible", e);
            }

            String output = out.toString();
            boolean found = output.contains("\"" + serverId + "\"");
            return (rc == 0 && found
                    ? NodeResult.pass("mcp.tools.visible")
                    : NodeResult.fail("mcp.tools.visible", "rc=" + rc + " found=" + found))
                    .assertion("mcp_call_ok", rc == 0)
                    .assertion("server_id_listed", found)
                    .metric("outputBytes", output.length());
        });
    }
}
