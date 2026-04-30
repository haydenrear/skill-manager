///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/StaleProcCleanup.java

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
            .dependsOn("postgres.up")
            .tags("registry", "server")
            .sideEffects("net:local", "proc:spawn")
            .timeout("120s")
            .output("baseUrl", "string")
            .retries(2);

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String portStr = ctx.get("env.prepared", "registryPort").orElse(null);
            if (home == null || portStr == null) {
                return NodeResult.fail("registry.up", "missing env.prepared context");
            }
            int port = Integer.parseInt(portStr);
            String dbUrl = ctx.get("postgres.up", "dbUrl").orElse(null);
            String dbUser = ctx.get("postgres.up", "dbUser").orElse(null);
            String dbPassword = ctx.get("postgres.up", "dbPassword").orElse(null);
            if (dbUrl == null) {
                return NodeResult.fail("registry.up", "missing postgres.up context");
            }

            Path homeDir = Path.of(home);
            Path tgDir = homeDir.resolve("test-graph");
            Path logFile = tgDir.resolve("registry.log");
            Path pidFile = tgDir.resolve("registry.pid");
            Path registryRoot = homeDir.resolve("registry-data");
            Files.createDirectories(registryRoot);

            // Kill any registry processes left over from a previous run that
            // crashed before teardown. Random per-run ports + pidfiles under
            // the previous home mean those processes can't be reaped via the
            // current run's pidfile alone — match by command line instead.
            // SkillManagerServer.java is the JBang entry; SkillRegistryApp
            // is the Spring Boot @SpringBootApplication once jbang re-execs
            // into java.
            StaleProcCleanup.killByCommandLineMatch(
                    ctx, "registry-stale-cleanup",
                    "SkillManagerServer.java",
                    "dev.skillmanager.server.SkillRegistryApp");

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
            pb.environment().put("SKILL_REGISTRY_DB_URL", dbUrl);
            if (dbUser != null) pb.environment().put("SKILL_REGISTRY_DB_USER", dbUser);
            if (dbPassword != null) pb.environment().put("SKILL_REGISTRY_DB_PASSWORD", dbPassword);
            // Publish backend toggle. Production servers default false
            // (github-pointer publishes only); smoke / sponsored / onboard /
            // refresh-flow still drive the legacy multipart path via
            // `skill-manager publish --upload-tarball`, so we default to
            // true here. The hyper-experiments graph adds an
            // env.hyper.prepared node that overrides this back to false to
            // exercise POST /skills/register end-to-end.
            String allowUpload = ctx.get("env.hyper.prepared", "allowFileUpload").orElse("true");
            pb.environment().put("SKILL_REGISTRY_ALLOW_FILE_UPLOAD", allowUpload);
            // Optional short-TTL override from an upstream fixture (refresh-flow
            // graph sets this to exercise real expiry; other graphs leave it
            // unpublished and the server keeps its 1h default).
            ctx.get("short.access.token.ttl", "seconds").ifPresent(s ->
                    pb.environment().put("SKILL_REGISTRY_ACCESS_TOKEN_TTL_SECONDS", s));
            ctx.get("short.access.token.ttl", "clockSkewSeconds").ifPresent(s ->
                    pb.environment().put("SKILL_REGISTRY_JWT_CLOCK_SKEW_SECONDS", s));
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
