///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Starts the Java Spring Boot skill registry server as a background process
 * on the port picked by env.prepared, points the registry root at a subdir
 * of {@code home}, and waits for /health. Writes the PID to a file so the
 * downstream {@code servers.down} node can stop it even though this JVM
 * exits once the node returns.
 */
public class RegistryUp {
    static final NodeSpec SPEC = NodeSpec.of("registry.up")
            .kind(NodeSpec.Kind.TESTBED)
            .dependsOn("env.prepared")
            .tags("registry", "server")
            .sideEffects("net:local", "proc:spawn")
            .timeout("60s")
            .output("baseUrl", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String portStr = ctx.get("env.prepared", "registryPort").orElse(null);
            if (home == null || portStr == null) {
                return NodeResult.fail("registry.up", "missing env.prepared context");
            }
            int port = Integer.parseInt(portStr);

            Path homeDir = Path.of(home);
            Path tgDir = homeDir.resolve("test-graph");
            Path logFile = tgDir.resolve("registry.log");
            Path pidFile = tgDir.resolve("registry.pid");
            Path registryRoot = homeDir.resolve("registry-data");
            Files.createDirectories(registryRoot);

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path serverScript = repoRoot.resolve("SkillManagerServer.java");
            if (!Files.isRegularFile(serverScript)) {
                return NodeResult.fail("registry.up", "missing " + serverScript);
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "jbang", serverScript.toString(),
                    "--server.port=" + port)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile());
            pb.environment().put("SKILL_REGISTRY_ROOT", registryRoot.toString());
            Process proc = pb.start();
            Files.writeString(pidFile, Long.toString(proc.pid()));

            String baseUrl = "http://127.0.0.1:" + port;
            boolean healthy = waitForHealthy(baseUrl, Duration.ofSeconds(60));
            if (!healthy) {
                proc.destroy();
                return NodeResult.fail("registry.up",
                        "registry not healthy within 60s; see " + logFile);
            }
            return NodeResult.pass("registry.up")
                    .assertion("health_ok", true)
                    .metric("pid", proc.pid())
                    .metric("port", port)
                    .publish("baseUrl", baseUrl);
        });
    }

    private static boolean waitForHealthy(String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI url = URI.create(baseUrl + "/health");
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> resp = http.send(
                        HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() / 100 == 2) return true;
            } catch (IOException | InterruptedException ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
