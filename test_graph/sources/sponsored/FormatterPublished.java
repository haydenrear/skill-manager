///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/** Publishes the formatter-skill fixture so another campaign has a real target. */
public class FormatterPublished {
    static final NodeSpec SPEC = NodeSpec.of("formatter.published")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("registry.up", "ci.logged.in", "jwt.valid")
            .tags("registry", "ads", "publish")
            .timeout("60s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("formatter.published", "missing upstream context");
            }
            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path skill = repoRoot.resolve("test_graph/fixtures/formatter-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", skill.toString(),
                    "--upload-tarball",
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());
            ProcessRecord proc = Procs.run(ctx, "publish", pb);
            int rc = proc.exitCode();
            NodeResult result = rc == 0
                    ? NodeResult.pass("formatter.published")
                    : NodeResult.fail("formatter.published", "publish exited " + rc);
            return result
                    .process(proc)
                    .assertion("published_ok", rc == 0)
                    .metric("exitCode", rc);
        });
    }
}
