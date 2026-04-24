///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Path;

/**
 * Starts the virtual MCP gateway via {@code skill-manager gateway up}.
 *
 * The CLI already tracks pid/log/config under {@code $SKILL_MANAGER_HOME} so
 * we just need to point it at the per-run home from {@code env.prepared} and
 * the port env.prepared allocated. Teardown lives in {@code servers.down}.
 */
public class GatewayUp {
    static final NodeSpec SPEC = NodeSpec.of("gateway.up")
            .kind(NodeSpec.Kind.TESTBED)
            .dependsOn("env.prepared")
            .tags("gateway", "mcp")
            .sideEffects("net:local", "proc:spawn")
            .timeout("60s")
            .output("baseUrl", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String portStr = ctx.get("env.prepared", "gatewayPort").orElse(null);
            if (home == null || portStr == null) {
                return NodeResult.fail("gateway.up", "missing env.prepared context");
            }
            int port = Integer.parseInt(portStr);

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "gateway", "up",
                    "--port", Integer.toString(port),
                    "--wait-seconds", "45");
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc;
            try {
                rc = Procs.runLogged(ctx, "gateway-up", pb);
            } catch (Exception e) {
                return NodeResult.error("gateway.up", e);
            }
            if (rc != 0) {
                return Procs.attach(
                        NodeResult.fail("gateway.up", "skill-manager gateway up exited " + rc),
                        ctx, "gateway-up", rc, 200);
            }
            return Procs.attach(
                    NodeResult.pass("gateway.up")
                            .assertion("gateway_healthy", true)
                            .metric("port", port)
                            .publish("baseUrl", "http://127.0.0.1:" + port),
                    ctx, "gateway-up", rc, 0);
        });
    }
}
