///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.List;
import java.util.Map;

/**
 * Hits the gateway's semantic {@code search_tools} virtual tool and asserts
 * the echo tool surfaces. Talks MCP directly.
 */
public class McpToolSearchFinds {
    static final NodeSpec SPEC = NodeSpec.of("mcp.tool.search.finds")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.http.deployed")
            .tags("mcp", "search", "spacy")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (gatewayUrl == null) {
                return NodeResult.fail("mcp.tool.search.finds", "missing upstream context");
            }

            Map<String, Object> res;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-mcp-search")) {
                res = mcp.call("search_tools", Map.of("query", "echo"));
            }

            Object matches = res.get("matches");
            boolean hasMatches = matches instanceof List;
            boolean echoHit = matches instanceof List<?> list
                    && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                            && "echo".equals(m.get("tool_name")));

            return (hasMatches && echoHit
                    ? NodeResult.pass("mcp.tool.search.finds")
                    : NodeResult.fail("mcp.tool.search.finds",
                            "hasMatches=" + hasMatches + " echoHit=" + echoHit))
                    .assertion("matches_array_present", hasMatches)
                    .assertion("echo_in_results", echoHit);
        });
    }
}
