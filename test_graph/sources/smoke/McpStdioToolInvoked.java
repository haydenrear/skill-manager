///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * Single round-trip through the stdio worker: describe the {@code echo}
 * tool to satisfy the gateway's disclosure gate, then invoke it. Asserts
 * the gateway successfully spoke MCP over the subprocess's stdin/stdout
 * pipes without tripping any of the cross-task ownership / broken-pipe
 * paths the {@code StdioMCPClient} worker-task model addresses.
 */
public class McpStdioToolInvoked {
    static final NodeSpec SPEC = NodeSpec.of("mcp.stdio.tool.invoked")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.stdio.skill.installed")
            .tags("mcp", "stdio", "invoke")
            .timeout("45s")
            .retries(2);

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.stdio.skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.stdio.tool.invoked", "missing upstream context");
            }
            String toolPath = serverId + "/echo";
            String sessionId = "test-stdio-invoke-" + ctx.runId();

            boolean describeOk;
            boolean invokeOk;
            String marker = "stdio-roundtrip-" + ctx.runId();
            try (TgMcp mcp = new TgMcp(gatewayUrl, sessionId)) {
                Map<String, Object> desc = mcp.call("describe_tool", Map.of("tool_path", toolPath));
                describeOk = "echo".equals(desc.get("tool_name"));

                Map<String, Object> inv = mcp.call("invoke_tool",
                        Map.of("tool_path", toolPath,
                               "arguments", Map.of("message", marker)));
                String dump = inv.toString();
                invokeOk = dump.contains(marker) && dump.contains("\"transport\"")
                        && dump.contains("stdio");
            }

            return (describeOk && invokeOk
                    ? NodeResult.pass("mcp.stdio.tool.invoked")
                    : NodeResult.fail("mcp.stdio.tool.invoked",
                            "describeOk=" + describeOk + " invokeOk=" + invokeOk))
                    .assertion("describe_ok", describeOk)
                    .assertion("invoke_roundtrip_ok", invokeOk);
        });
    }
}
