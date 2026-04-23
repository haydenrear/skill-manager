///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Starts the virtual-mcp-gateway echo fixture (a FastMCP server with one
 * tool, {@code echo}) in streamable-HTTP mode on a free port. Downstream
 * gateway nodes register its /mcp endpoint to exercise HTTP transport
 * end-to-end.
 */
public class EchoHttpUp {
    static final NodeSpec SPEC = NodeSpec.of("echo.http.up")
            .kind(NodeSpec.Kind.TESTBED)
            .dependsOn("env.prepared")
            .tags("mcp", "fixture")
            .sideEffects("net:local", "proc:spawn")
            .timeout("60s")
            .output("mcpUrl", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("echo.http.up", "missing env.prepared context");

            int port = freePort();

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path venvPy = repoRoot.resolve("virtual-mcp-gateway/.venv/bin/python");
            Path fixture = repoRoot.resolve("virtual-mcp-gateway/tests/fixtures/downstream_mcp_server.py");
            if (!Files.isExecutable(venvPy) || !Files.isRegularFile(fixture)) {
                return NodeResult.fail("echo.http.up",
                        "missing python or fixture: " + venvPy + " / " + fixture);
            }

            Path tgDir = Path.of(home, "test-graph");
            Path pidFile = tgDir.resolve("echo-http.pid");
            Path logFile = tgDir.resolve("echo-http.log");

            ProcessBuilder pb = new ProcessBuilder(
                    venvPy.toString(), fixture.toString(),
                    "--transport", "streamable-http",
                    "--name", "echo-http",
                    "--port", Integer.toString(port))
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            Process proc = pb.start();
            Files.writeString(pidFile, Long.toString(proc.pid()));

            String baseUrl = "http://127.0.0.1:" + port;
            boolean healthy = waitForHealthy(baseUrl + "/health", Duration.ofSeconds(30));
            if (!healthy) {
                proc.destroy();
                return NodeResult.fail("echo.http.up", "echo fixture not healthy within 30s");
            }
            return NodeResult.pass("echo.http.up")
                    .assertion("fixture_healthy", true)
                    .metric("port", port)
                    .metric("pid", proc.pid())
                    .publish("mcpUrl", baseUrl + "/mcp");
        });
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static boolean waitForHealthy(String url, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create(url);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> resp = http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() / 100 == 2) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
