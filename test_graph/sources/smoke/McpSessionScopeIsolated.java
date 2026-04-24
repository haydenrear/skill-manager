///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * Deploys a session-scoped MCP server in agent session A via
 * {@code deploy_mcp_server}, then asserts:
 *
 * <ul>
 *   <li>session A: {@code deployed_in_session=true},
 *       {@code deployed_globally=false}</li>
 *   <li>session B: {@code deployed_in_session=false},
 *       {@code deployed_globally=false}</li>
 * </ul>
 */
public class McpSessionScopeIsolated {
    static final NodeSpec SPEC = NodeSpec.of("mcp.session_scope.isolated")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.session_skill.installed")
            .tags("mcp", "scope", "session")
            .timeout("60s");

    private static final String SESSION_A = "test-session-A";
    private static final String SESSION_B = "test-session-B";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.session_skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("mcp.session_scope.isolated", "missing upstream context");
            }

            boolean deployOk;
            boolean aInSession;
            boolean aGlobal;
            boolean bInSession;
            boolean bGlobal;

            // Deploy in session A.
            try (TgMcp a = new TgMcp(gatewayUrl, SESSION_A)) {
                Map<String, Object> deploy = a.call("deploy_mcp_server",
                        Map.of("server_id", serverId, "scope", "session"));
                deployOk = Boolean.TRUE.equals(deploy.get("deployed"))
                        && "session".equals(deploy.get("scope"));

                Map<String, Object> descA = a.call("describe_mcp_server",
                        Map.of("server_id", serverId));
                aInSession = TgMcp.bool(descA, "deployed_in_session");
                aGlobal = TgMcp.bool(descA, "deployed_globally");
            }

            // Fresh session B — must not see session A's deployment.
            try (TgMcp b = new TgMcp(gatewayUrl, SESSION_B)) {
                Map<String, Object> descB = b.call("describe_mcp_server",
                        Map.of("server_id", serverId));
                bInSession = TgMcp.bool(descB, "deployed_in_session");
                bGlobal = TgMcp.bool(descB, "deployed_globally");
            }

            boolean ok = deployOk && aInSession && !aGlobal && !bInSession && !bGlobal;
            return (ok
                    ? NodeResult.pass("mcp.session_scope.isolated")
                    : NodeResult.fail("mcp.session_scope.isolated",
                            "deployOk=" + deployOk + " aSess=" + aInSession + " aGlob=" + aGlobal
                                    + " bSess=" + bInSession + " bGlob=" + bGlobal))
                    .assertion("deploy_returned_session_scope", deployOk)
                    .assertion("session_A_sees_deployed", aInSession)
                    .assertion("not_deployed_globally", !aGlobal)
                    .assertion("session_B_does_not_see_deployed", !bInSession)
                    .assertion("session_B_does_not_see_global", !bGlobal);
        });
    }
}
