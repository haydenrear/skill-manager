///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * Publishes the examples/hello-plugin bundle to the Java registry server.
 * Parallel to {@code HelloPublished} for skills — ticket 15 contract:
 * publish flow detects {@code .claude-plugin/plugin.json} via
 * {@code SkillPackager.detectKind} and bundles the plugin layout
 * (manifest dir + skill-manager-plugin.toml + skills/&lt;contained&gt;).
 */
public class HelloPluginPublished {
    static final NodeSpec SPEC = NodeSpec.of("hello.plugin.published")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("registry.up", "ci.logged.in", "jwt.valid")
            .tags("registry", "publish", "plugin")
            .timeout("60s")
            .output("pluginName", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("hello.plugin.published", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path helloPlugin = repoRoot.resolve("examples/hello-plugin");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "publish", helloPlugin.toString(),
                    "--upload-tarball",
                    "--registry", registryUrl);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            ProcessRecord proc = Procs.run(ctx, "publish", pb);
            int rc = proc.exitCode();
            NodeResult result = rc == 0
                    ? NodeResult.pass("hello.plugin.published")
                    : NodeResult.fail("hello.plugin.published", "publish exited " + rc);
            return result
                    .process(proc)
                    .assertion("published_ok", rc == 0)
                    .metric("exitCode", rc)
                    .publish("pluginName", "hello-plugin");
        });
    }
}
