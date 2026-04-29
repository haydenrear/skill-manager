///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * After the echo-http skill is installed, assert the gateway's
 * {@code browse_mcp_servers} virtual tool lists the server — reached via a
 * real MCP client, no CLI passthrough.
 */
public class McpToolsVisible {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tools.visible")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.http.skill.installed")
            .tags("mcp")
            .timeout("30s")
            .retries(2);
    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.http.skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.tools.visible", "missing upstream context");
            }

            boolean found;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-mcp-tools-visible")) {
                Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
                Object items = res.get("items");
                found = items instanceof java.util.List<?> list
                        && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                                && serverId.equals(m.get("server_id")));
            }

            return (found
                    ? NodeResult.pass("mcp.tools.visible")
                    : NodeResult.fail("mcp.tools.visible", "browse_mcp_servers did not include " + serverId))
                    .assertion("server_listed_via_mcp", found);
        });
    }
}
