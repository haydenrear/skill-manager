///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * Publishes the cloned hyper-experiments skill to the local registry.
 *
 * <p>Mirrors {@code HelloPublished} but points at the directory produced by
 * {@code hyper.checkout} instead of the bundled examples. The fact that the
 * tarball comes from a freshly-cloned tree (no embedded {@code libs/}) is
 * what keeps it under the registry's payload cap.
 */
public class HyperPublished {
    static final NodeSpec SPEC = NodeSpec.of("hyper.published")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("hyper.checkout", "registry.up", "ci.logged.in", "jwt.valid")
            .tags("hyper", "registry", "publish")
            .timeout("90s")
            .output("skillName", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            String skillDir = ctx.get("hyper.checkout", "skillDir").orElse(null);
            if (home == null || registryUrl == null || skillDir == null) {
                return NodeResult.fail("hyper.published", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", skillDir,
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "publish", pb);
            int rc = proc.exitCode();
            NodeResult result = rc == 0
                    ? NodeResult.pass("hyper.published")
                    : NodeResult.fail("hyper.published", "publish exited " + rc);
            return result
                    .process(proc)
                    .assertion("published_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("skillName", "hyper-experiments");
        });
    }
}
