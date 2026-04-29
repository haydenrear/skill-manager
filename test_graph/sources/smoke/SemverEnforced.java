///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * Attempts a publish with a non-semver version string and asserts the CLI
 * exits non-zero (server rejects with 400).
 *
 * <p>Runs after {@code hello.published} so the DB already has a row for
 * {@code hello-skill@0.1.0}; this pass targets version {@code not-semver}
 * which must fail at the semver gate — before the immutability or owner
 * checks get a chance to fire.
 */
public class SemverEnforced {
    static final NodeSpec SPEC = NodeSpec.of("semver.enforced")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.published")
            .tags("registry", "publish", "semver")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("semver.enforced", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path skill = repoRoot.resolve("examples/hello-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", skill.toString(),
                    "--upload-tarball",
                    "--version", "not-semver",
                    "--registry", registryUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            Process proc = pb.start();
            int rc = proc.waitFor();
            boolean rejected = rc != 0;
            return (rejected
                    ? NodeResult.pass("semver.enforced")
                    : NodeResult.fail("semver.enforced", "publish with non-semver version unexpectedly succeeded"))
                    .assertion("non_semver_publish_rejected", rejected)
                    .metric("exitCode", rc);
        });
    }
}
