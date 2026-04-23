///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Re-register the echo server with a changed display_name, then confirm
 * the gateway's live state reflects the update (not just the on-disk
 * dynamic-servers.json). Proves the re-register path properly tears down
 * the existing deployment and rebuilds the ClientConfig.
 */
public class EchoHttpRedeployed {
    static final NodeSpec SPEC = NodeSpec.of("echo.http.redeployed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("mcp.tool.invoked")
            .tags("mcp", "reregister")
            .timeout("30s");

    private static final String NEW_DISPLAY = "Echo (HTTP, updated)";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String gatewayUrl = ctx.get("gateway.up", "baseUrl").orElse(null);
            String mcpUrl = ctx.get("echo.http.up", "mcpUrl").orElse(null);
            String serverId = ctx.get("echo.http.registered", "serverId").orElse(null);
            if (home == null || gatewayUrl == null || mcpUrl == null || serverId == null) {
                return NodeResult.fail("echo.http.redeployed", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder registerPb = new ProcessBuilder(
                    sm.toString(), "gateway", "register", serverId,
                    "--display-name", NEW_DISPLAY,
                    "--description", "Re-registered by test_graph at " + java.time.Instant.now(),
                    "--url", mcpUrl,
                    "--transport", "streamable-http",
                    "--gateway", gatewayUrl)
                    .inheritIO();
            registerPb.environment().put("SKILL_MANAGER_HOME", home);
            registerPb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            int rc = registerPb.start().waitFor();

            // Confirm the live registry reflects the new display_name.
            ProcessBuilder describePb = new ProcessBuilder(
                    sm.toString(), "gateway", "describe-server", serverId,
                    "--gateway", gatewayUrl)
                    .redirectErrorStream(true);
            describePb.environment().put("SKILL_MANAGER_HOME", home);
            describePb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            StringBuilder out = new StringBuilder();
            Process p = describePb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            boolean liveUpdated = out.toString().contains(NEW_DISPLAY);

            return (rc == 0 && liveUpdated
                    ? NodeResult.pass("echo.http.redeployed")
                    : NodeResult.fail("echo.http.redeployed",
                            "register_rc=" + rc + " liveUpdated=" + liveUpdated))
                    .assertion("reregister_ok", rc == 0)
                    .assertion("live_display_name_updated", liveUpdated)
                    .metric("exitCode", rc);
        });
    }
}
