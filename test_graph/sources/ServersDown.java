///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Teardown: stop the gateway (via CLI) and the registry server (via PID
 * file that {@code registry.up} left behind). Runs last in the graph so
 * the whole pipeline has a chance to complete before we pull the rug.
 */
public class ServersDown {
    static final NodeSpec SPEC = NodeSpec.of("servers.down")
            .kind(NodeSpec.Kind.EVIDENCE)
            .dependsOn("mcp.tools.visible", "search.finds", "hello.installed")
            .tags("teardown")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("servers.down", "missing env.prepared context");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            // Gateway: graceful shutdown via the CLI (it owns its own pid file).
            boolean gatewayDown = false;
            try {
                ProcessBuilder pb = new ProcessBuilder(sm.toString(), "gateway", "down").inheritIO();
                pb.environment().put("SKILL_MANAGER_HOME", home);
                pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
                gatewayDown = pb.start().waitFor() == 0;
            } catch (Exception ignored) {}

            // Registry: killed directly via the pid file registry.up wrote.
            boolean registryDown = false;
            Path pidFile = Path.of(home).resolve("test-graph/registry.pid");
            try {
                if (Files.isRegularFile(pidFile)) {
                    long pid = Long.parseLong(Files.readString(pidFile).trim());
                    ProcessHandle.of(pid).ifPresent(h -> {
                        h.destroy();
                        try {
                            h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            h.destroyForcibly();
                        }
                    });
                    registryDown = ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true);
                    Files.deleteIfExists(pidFile);
                }
            } catch (Exception ignored) {}

            return NodeResult.pass("servers.down")
                    .assertion("gateway_down", gatewayDown)
                    .assertion("registry_down", registryDown);
        });
    }
}
