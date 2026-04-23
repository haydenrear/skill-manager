///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * Registers the HTTP echo fixture with the gateway via REST. Exercises the
 * non-docker transport path: {@code --url} + {@code --transport streamable-http}
 * on a binary load spec (no download, just a proxy target).
 */
public class EchoHttpRegistered {
    static final NodeSpec SPEC = NodeSpec.of("echo.http.registered")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("gateway.up", "echo.http.up")
            .tags("mcp", "http")
            .timeout("30s")
            .output("serverId", "string");

    private static final String SERVER_ID = "echo-http";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            if (home == null || gatewayUrl == null || mcpUrl == null) {
                return NodeResult.fail("echo.http.registered", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "gateway", "register", SERVER_ID,
                    "--display-name", "Echo (HTTP)",
                    "--url", mcpUrl,
                    "--transport", "streamable-http",
                    "--gateway", gatewayUrl)
                    .inheritIO();
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc = pb.start().waitFor();
            return (rc == 0
                    ? NodeResult.pass("echo.http.registered")
                    : NodeResult.fail("echo.http.registered", "register exited " + rc))
                    .assertion("registered_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("serverId", SERVER_ID);
        });
    }
}
