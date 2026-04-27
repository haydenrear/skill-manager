///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.List;
import java.util.Map;

/**
 * Asserts the runpod MCP server (declared by {@code hyper-experiments}'s
 * {@code [[mcp_dependencies]]}) is registered with the gateway after install,
 * reached via a real MCP client (no CLI passthrough). Mirrors the pattern
 * established by {@code McpToolsVisible}.
 *
 * <p>Registration alone, not deployment — the runpod docker container is
 * not actually started here. Deploying would require docker-in-docker plus
 * a real {@code RUNPOD_API_KEY}; see
 * {@code skill-publisher-skill/references/runpod-mcp-onboarding.md} for the
 * delta needed to extend this graph to full deployment.
 */
public class HyperRunpodRegistered {
    static final String SERVER_ID = "runpod";

    static final NodeSpec SPEC = NodeSpec.of("hyper.runpod.registered")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.installed", "gateway.up")
            .tags("hyper", "mcp", "runpod")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (gatewayUrl == null) {
                return NodeResult.fail("hyper.runpod.registered",
                        "missing gateway.up baseUrl");
            }

            boolean found;
            String defaultScope = "";
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-hyper-runpod-registered")) {
                Map<String, Object> res = mcp.call("browse_mcp_servers", Map.of());
                Object items = res.get("items");
                Map<?, ?> match = null;
                if (items instanceof List<?> list) {
                    for (Object it : list) {
                        if (it instanceof Map<?, ?> m
                                && SERVER_ID.equals(m.get("server_id"))) {
                            match = m;
                            break;
                        }
                    }
                }
                found = match != null;
                if (match != null) {
                    Object scope = match.get("default_scope");
                    if (scope != null) defaultScope = scope.toString();
                }
            }

            // Default scope declared in skill-manager.toml is global-sticky;
            // a mismatch here means the manifest didn't round-trip through
            // publish/install correctly.
            boolean scopeOk = "global-sticky".equals(defaultScope);

            String reason = found && scopeOk
                    ? ""
                    : "browse_mcp_servers result missing or wrong scope ("
                            + "found=" + found + " scope=" + defaultScope + ")";
            NodeResult result = (found && scopeOk)
                    ? NodeResult.pass("hyper.runpod.registered")
                    : NodeResult.fail("hyper.runpod.registered", reason);

            return result
                    .assertion("runpod_listed_via_mcp", found)
                    .assertion("default_scope_global_sticky", scopeOk);
        });
    }
}
