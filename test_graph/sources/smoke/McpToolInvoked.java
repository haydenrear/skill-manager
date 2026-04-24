///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * End-to-end MCP invocation: describe the tool (to satisfy the gateway's
 * disclosure gate) and then invoke it, both through one {@link TgMcp}
 * client so the disclosure record sticks within the session.
 */
public class McpToolInvoked {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tool.invoked")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.http.deployed")
            .tags("mcp", "invoke")
            .timeout("45s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.http.skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.tool.invoked", "missing upstream context");
            }
            String toolPath = serverId + "/echo";
            String sessionId = "test-invoke-" + ctx.runId();

            boolean describeOk;
            boolean invokeOk;
            try (TgMcp mcp = new TgMcp(gatewayUrl, sessionId)) {
                Map<String, Object> desc = mcp.call("describe_tool", Map.of("tool_path", toolPath));
                describeOk = "echo".equals(desc.get("tool_name"));

                Map<String, Object> inv = mcp.call("invoke_tool",
                        Map.of("tool_path", toolPath,
                               "arguments", Map.of("message", "hello-from-testgraph")));
                String dump = inv.toString();
                invokeOk = dump.contains("hello-from-testgraph");
            }

            return (describeOk && invokeOk
                    ? NodeResult.pass("mcp.tool.invoked")
                    : NodeResult.fail("mcp.tool.invoked",
                            "describeOk=" + describeOk + " invokeOk=" + invokeOk))
                    .assertion("describe_ok", describeOk)
                    .assertion("invoke_roundtrip_ok", invokeOk);
        });
    }
}
