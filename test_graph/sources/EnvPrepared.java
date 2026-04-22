///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the shared per-run state for the integration graph:
 *
 *   - A fresh {@code SKILL_MANAGER_HOME} so no pre-existing local state
 *     (skills/, bin/, gateway pid files) interferes with the run.
 *   - Two free TCP ports: one for the Java skill registry server, one
 *     for the virtual MCP gateway.
 *
 * Downstream nodes pick these up via ctx.get("env.prepared", ...).
 */
public class EnvPrepared {
    static final NodeSpec SPEC = NodeSpec.of("env.prepared")
            .kind(NodeSpec.Kind.FIXTURE)
            .tags("env")
            .output("home", "string")
            .output("registryPort", "integer")
            .output("gatewayPort", "integer");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path home = Files.createTempDirectory("sm-testgraph-");
            Files.createDirectories(home.resolve("test-graph"));

            int registryPort = freePort();
            int gatewayPort = freePort();

            return NodeResult.pass("env.prepared")
                    .assertion("home_created", Files.isDirectory(home))
                    .assertion("ports_allocated", registryPort > 0 && gatewayPort > 0)
                    .metric("registryPort", registryPort)
                    .metric("gatewayPort", gatewayPort)
                    .publish("home", home.toString())
                    .publish("registryPort", Integer.toString(registryPort))
                    .publish("gatewayPort", Integer.toString(gatewayPort));
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
