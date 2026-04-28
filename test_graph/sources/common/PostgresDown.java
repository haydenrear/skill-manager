///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * Tears down the postgres container that {@code postgres.up} brought up.
 * Runs last in every graph — after the registry process and any other
 * servers are stopped — so the DB is still reachable while assertions /
 * teardown nodes query it.
 */
public class PostgresDown {
    static final NodeSpec SPEC = NodeSpec.of("postgres.down")
            .kind(NodeSpec.Kind.EVIDENCE)
            .tags("postgres", "teardown")
            .sideEffects("docker")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path compose = repoRoot.resolve("docker-compose.yml");
            if (!java.nio.file.Files.isRegularFile(compose)) {
                return NodeResult.fail("postgres.down", "missing " + compose);
            }
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "compose", "-f", compose.toString(), "stop", "postgres")
                    .directory(repoRoot.toFile());
            ProcessRecord proc = Procs.run(ctx, "compose-stop", pb);
            int rc = proc.exitCode();
            NodeResult result = rc == 0
                    ? NodeResult.pass("postgres.down")
                    : NodeResult.fail("postgres.down", "docker compose stop exited " + rc);
            return result
                    .process(proc)
                    .assertion("stopped", rc == 0);
        });
    }
}
