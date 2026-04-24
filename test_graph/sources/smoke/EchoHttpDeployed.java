///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * The echo server is registered transitively during
 * {@code echo.http.skill.installed}, and — because its {@code default_scope}
 * is {@code global-sticky} and it has no required init — the gateway
 * auto-deploys it on register. This assertion confirms the deployed state
 * from the agent's point of view via {@code describe_mcp_server}.
 */
public class EchoHttpDeployed {
    static final NodeSpec SPEC = NodeSpec.of("echo.http.deployed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("echo.http.skill.installed")
            .tags("mcp", "deploy")
            .timeout("30s")
            .output("toolPath", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String serverId = ctx.get("echo.http.skill.installed", "serverId").orElse(null);
            if (gatewayUrl == null || serverId == null) {
                return NodeResult.fail("echo.http.deployed", "missing upstream context");
            }

            boolean deployedGlobally;
            boolean echoTool;
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-echo-deployed")) {
                Map<String, Object> desc = mcp.call("describe_mcp_server",
                        Map.of("server_id", serverId));
                deployedGlobally = TgMcp.bool(desc, "deployed_globally");

                Map<String, Object> browse = mcp.call("browse_active_tools",
                        Map.of("server_id", serverId));
                Object items = browse.get("items");
                echoTool = items instanceof java.util.List<?> list
                        && list.stream().anyMatch(it -> it instanceof Map<?, ?> m
                                && "echo".equals(m.get("tool_name")));
            }

            return (deployedGlobally && echoTool
                    ? NodeResult.pass("echo.http.deployed")
                    : NodeResult.fail("echo.http.deployed",
                            "deployedGlobally=" + deployedGlobally + " echoTool=" + echoTool))
                    .assertion("server_deployed_globally", deployedGlobally)
                    .assertion("echo_tool_in_active_tools", echoTool)
                    .publish("toolPath", serverId + "/echo");
        });
    }
}
