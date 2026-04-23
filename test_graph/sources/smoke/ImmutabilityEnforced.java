///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * Attempts to re-publish {@code hello-skill@0.1.0} — which
 * {@code hello.published} already inserted — and asserts the CLI exits
 * non-zero (server rejects with 409).
 *
 * <p>Guards the version-immutability contract: a published version is a
 * permanent artifact. Silently overwriting it would break every client
 * that pinned the hash.
 */
public class ImmutabilityEnforced {
    static final NodeSpec SPEC = NodeSpec.of("immutability.enforced")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.published")
            .tags("registry", "publish", "immutability")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("immutability.enforced", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path skill = repoRoot.resolve("examples/hello-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", skill.toString(),
                    "--registry", registryUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            Process proc = pb.start();
            int rc = proc.waitFor();
            boolean rejected = rc != 0;
            return (rejected
                    ? NodeResult.pass("immutability.enforced")
                    : NodeResult.fail("immutability.enforced", "republish of hello-skill@0.1.0 unexpectedly succeeded"))
                    .assertion("republish_rejected", rejected)
                    .metric("exitCode", rc);
        });
    }
}
