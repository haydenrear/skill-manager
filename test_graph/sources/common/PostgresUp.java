///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TestDb.java
//DEPS org.postgresql:postgresql:42.7.4

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;

/**
 * Brings up the postgres-pgvector container from {@code docker-compose.yml} and
 * resets the {@code skill_registry_test} database so downstream nodes get a
 * clean slate.
 *
 * <p>Publishes the test-DB connection URL / user / password that RegistryUp
 * then threads into the server process as environment variables, so the
 * server points at {@code skill_registry_test} instead of the dev database.
 */
public class PostgresUp {
    static final String DB_URL = "jdbc:postgresql://localhost:5432/skill_registry_test";
    static final String DB_USER = "postgres";
    static final String DB_PASSWORD = "postgres";

    static final NodeSpec SPEC = NodeSpec.of("postgres.up")
            .kind(NodeSpec.Kind.TESTBED)
            .dependsOn("env.prepared")
            .tags("postgres", "infra")
            .sideEffects("docker", "db:truncate")
            .timeout("90s")
            .output("dbUrl", "string")
            .output("dbUser", "string")
            .output("dbPassword", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path compose = repoRoot.resolve("docker-compose.yml");
            if (!java.nio.file.Files.isRegularFile(compose)) {
                return NodeResult.fail("postgres.up", "missing " + compose);
            }

            // If a postgres container from a prior run is already up, cycle
            // it so the test gets a clean start (fresh connections, no
            // stale background work). Otherwise `up -d` creates it.
            if (isPostgresRunning(repoRoot, compose)) {
                int rcRestart = run(ctx, "compose-restart", repoRoot, "docker", "compose", "-f", compose.toString(), "restart", "postgres");
                if (rcRestart != 0) {
                    return Procs.attach(
                            NodeResult.fail("postgres.up", "docker compose restart postgres exited " + rcRestart),
                            ctx, "compose-restart", rcRestart, 200);
                }
            } else {
                int rc = run(ctx, "compose-up", repoRoot, "docker", "compose", "-f", compose.toString(), "up", "-d", "postgres");
                if (rc != 0) return Procs.attach(
                        NodeResult.fail("postgres.up", "docker compose up -d postgres exited " + rc),
                        ctx, "compose-up", rc, 200);
            }

            if (!waitForReady(Duration.ofSeconds(60))) {
                return NodeResult.fail("postgres.up", "postgres not reachable on 5432 within 60s");
            }

            // Wipe residue from any prior run.
            try (TestDb db = TestDb.open()) { db.truncateAll(); }

            return NodeResult.pass("postgres.up")
                    .assertion("reachable", true)
                    .publish("dbUrl", DB_URL)
                    .publish("dbUser", DB_USER)
                    .publish("dbPassword", DB_PASSWORD);
        });
    }

    private static int run(NodeContext ctx, String label, Path cwd, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        return Procs.runLogged(ctx, label, pb);
    }

    private static boolean isPostgresRunning(Path cwd, Path compose) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "compose", "-f", compose.toString(),
                "ps", "--services", "--filter", "status=running")
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        for (String line : out.split("\n")) {
            if ("postgres".equals(line.trim())) return true;
        }
        return false;
    }

    private static boolean waitForReady(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 Statement s = c.createStatement()) {
                s.execute("SELECT 1");
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
