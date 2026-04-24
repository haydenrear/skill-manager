///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * Asserts a global-scoped deployment is visible from arbitrary agent
 * sessions. Describes the server from two unrelated session ids — both
 * must see {@code deployed_globally=true} and {@code deployed_in_session=false}.
 */
public class McpGlobalScopeVisible {
    static final NodeSpec SPEC = NodeSpec.of("mcp.global_scope.visible")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.global_skill.installed")
            .tags("mcp", "scope", "global")
            .timeout("30s");

    private static final String SESSION_X = "test-session-X";
    private static final String SESSION_Y = "test-session-Y";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.global_skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.global_scope.visible", "missing upstream context");
            }

            boolean xGlobal;
            boolean yGlobal;
            boolean xNotSession;
            boolean yNotSession;
            try (TgMcp x = new TgMcp(gatewayUrl, SESSION_X)) {
                Map<String, Object> d = x.call("describe_mcp_server", Map.of("server_id", serverId));
                xGlobal = TgMcp.bool(d, "deployed_globally");
                xNotSession = !TgMcp.bool(d, "deployed_in_session");
            }
            try (TgMcp y = new TgMcp(gatewayUrl, SESSION_Y)) {
                Map<String, Object> d = y.call("describe_mcp_server", Map.of("server_id", serverId));
                yGlobal = TgMcp.bool(d, "deployed_globally");
                yNotSession = !TgMcp.bool(d, "deployed_in_session");
            }

            boolean ok = xGlobal && yGlobal && xNotSession && yNotSession;
            return (ok
                    ? NodeResult.pass("mcp.global_scope.visible")
                    : NodeResult.fail("mcp.global_scope.visible",
                            "xGlob=" + xGlobal + " yGlob=" + yGlobal
                                    + " xNotSess=" + xNotSession + " yNotSess=" + yNotSession))
                    .assertion("session_X_sees_global", xGlobal)
                    .assertion("session_Y_sees_global", yGlobal)
                    .assertion("session_X_not_session_scoped", xNotSession)
                    .assertion("session_Y_not_session_scoped", yNotSession);
        });
    }
}
