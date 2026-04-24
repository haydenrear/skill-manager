///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * Agent-driven redeploy: call {@code deploy_mcp_server} again (no init
 * changes) and confirm the gateway reports a fresh {@code initialized_at}.
 * Proves the redeploy path tears down and rebuilds cleanly.
 */
public class EchoHttpRedeployed {
    static final NodeSpec SPEC = NodeSpec.of("echo.http.redeployed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("mcp.tool.invoked")
            .tags("mcp", "redeploy")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.http.skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("echo.http.redeployed", "missing upstream context");
            }

            double before;
            double after;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-redeploy")) {
                Map<String, Object> d1 = mcp.call("describe_mcp_server", Map.of("server_id", serverId));
                before = readInitializedAt(d1);

                // Redeploy (scope omitted → uses the server's default_scope).
                Map<String, Object> deploy = mcp.call("deploy_mcp_server",
                        Map.of("server_id", serverId));
                boolean deployOk = Boolean.TRUE.equals(deploy.get("deployed"));
                if (!deployOk) {
                    return NodeResult.fail("echo.http.redeployed",
                            "deploy_mcp_server returned deployed=false: " + deploy);
                }

                Map<String, Object> d2 = mcp.call("describe_mcp_server", Map.of("server_id", serverId));
                after = readInitializedAt(d2);
            }

            boolean advanced = after > before;
            return (advanced
                    ? NodeResult.pass("echo.http.redeployed")
                    : NodeResult.fail("echo.http.redeployed",
                            "initialized_at did not advance: " + before + " -> " + after))
                    .assertion("initialized_at_advanced", advanced)
                    .metric("initializedAtBefore", before)
                    .metric("initializedAtAfter", after);
        });
    }

    private static double readInitializedAt(Map<String, Object> desc) {
        Map<String, Object> dep = TgMcp.obj(desc, "deployment");
        Object v = dep.get("initialized_at");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
