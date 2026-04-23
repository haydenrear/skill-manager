///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Teardown: stop the gateway (via CLI) and the registry + echo-http fixture
 * (via the PID files they wrote). Runs after every assertion + the report,
 * so smoke-report.md gets a clean run before we pull the rug.
 */
public class ServersDown {
    static final NodeSpec SPEC = NodeSpec.of("servers.down")
            .kind(NodeSpec.Kind.EVIDENCE)
            .dependsOn("smoke.report")
            .tags("teardown")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("servers.down", "missing env.prepared context");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            boolean gatewayDown = false;
            try {
                ProcessBuilder pb = new ProcessBuilder(sm.toString(), "gateway", "down").inheritIO();
                pb.environment().put("SKILL_MANAGER_HOME", home);
                pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
                gatewayDown = pb.start().waitFor() == 0;
            } catch (Exception ignored) {}

            boolean registryDown = killByPidFile(Path.of(home, "test-graph", "registry.pid"));
            boolean echoDown = killByPidFile(Path.of(home, "test-graph", "echo-http.pid"));

            return NodeResult.pass("servers.down")
                    .assertion("gateway_down", gatewayDown)
                    .assertion("registry_down", registryDown)
                    .assertion("echo_fixture_down", echoDown);
        });
    }

    private static boolean killByPidFile(Path pidFile) {
        try {
            if (!Files.isRegularFile(pidFile)) return true;
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            boolean stopped = ProcessHandle.of(pid).map(h -> {
                h.destroy();
                try {
                    h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    h.destroyForcibly();
                }
                return !h.isAlive();
            }).orElse(true);
            Files.deleteIfExists(pidFile);
            return stopped;
        } catch (Exception e) {
            return false;
        }
    }
}
