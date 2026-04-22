///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * Publishes the examples/hello-skill bundle to the Java registry server
 * brought up by {@code registry.up}. Uses the CLI (`skill-manager publish`)
 * so the integration test exercises the same code path a user would hit.
 */
public class HelloPublished {
    static final NodeSpec SPEC = NodeSpec.of("hello.published")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("registry.up")
            .tags("registry", "publish")
            .timeout("60s")
            .output("skillName", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("hello.published", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path helloSkill = repoRoot.resolve("examples/hello-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", helloSkill.toString(),
                    "--registry", registryUrl)
                    .inheritIO();
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc;
            try {
                rc = pb.start().waitFor();
            } catch (Exception e) {
                return NodeResult.error("hello.published", e);
            }
            return (rc == 0
                    ? NodeResult.pass("hello.published")
                    : NodeResult.fail("hello.published", "publish exited " + rc))
                    .assertion("published_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("skillName", "hello-skill");
        });
    }
}
