///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/TgMcp.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.util.Map;

/**
 * Asserts the runpod MCP server auto-deployed at install time.
 *
 * <p>This is the install-time "init from environment" path: when
 * {@code hyper.installed} ran with {@code RUNPOD_API_KEY} in its
 * environment, {@code McpWriter} folded that into
 * {@code initialization_params}, the gateway saw all required init
 * fields satisfied, and registered-with-deploy fired in one trip.
 *
 * <p>Failure mode if {@code X_RUNPOD_KEY} isn't in the test runner's
 * env: registration succeeds but deploy is skipped, so this assertion
 * fails with {@code deployed=false}. That's the right signal —
 * downstream nodes ({@code hyper.runpod.tools},
 * {@code hyper.runpod.tool.invoked}) cannot run without a live
 * deployment.
 */
public class HyperRunpodDeployed {
    static final String SERVER_ID = "runpod";

    static final NodeSpec SPEC = NodeSpec.of("hyper.runpod.deployed")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.runpod.registered", "gateway.up")
            .tags("hyper", "mcp", "runpod", "deploy")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            if (gatewayUrl == null) {
                return NodeResult.fail("hyper.runpod.deployed",
                        "missing gateway.up baseUrl");
            }

            boolean deployed;
            String defaultScope = "";
            try (TgMcp mcp = new TgMcp(gatewayUrl, "test-hyper-runpod-deployed")) {
                Map<String, Object> desc = mcp.call("describe_mcp_server",
                        Map.of("server_id", SERVER_ID));
                deployed = Boolean.TRUE.equals(desc.get("deployed"))
                        || Boolean.TRUE.equals(desc.get("deployed_globally"));
                Object scope = desc.get("default_scope");
                if (scope != null) defaultScope = scope.toString();
            }

            String reason = deployed ? "" : (
                    "runpod is registered but not deployed — likely cause: "
                            + "X_RUNPOD_KEY not set in the test runner's env, so "
                            + "RUNPOD_API_KEY didn't reach skill-manager install");
            NodeResult result = deployed
                    ? NodeResult.pass("hyper.runpod.deployed")
                    : NodeResult.fail("hyper.runpod.deployed", reason);

            return result
                    .assertion("runpod_deployed_via_env_init", deployed)
                    .assertion("scope_global_sticky",
                            "global-sticky".equals(defaultScope));
        });
    }
}
