///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Confirms that a {@code search hello} hits the published skill. Asserts on
 * stdout — the CLI prints a table, so we look for the skill name in any row
 * of output that wasn't blank or the header.
 */
public class SearchFinds {
    static final NodeSpec SPEC = NodeSpec.of("search.finds")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hello.published")
            .tags("registry", "search")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || registryUrl == null) {
                return NodeResult.fail("search.finds", "missing upstream context");
            }

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "search", "hello",
                    "--registry", registryUrl)
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            StringBuilder out = new StringBuilder();
            int rc;
            try {
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                }
                rc = p.waitFor();
            } catch (Exception e) {
                return NodeResult.error("search.finds", e);
            }

            String output = out.toString();
            boolean found = output.contains("hello-skill");
            return (rc == 0 && found
                    ? NodeResult.pass("search.finds")
                    : NodeResult.fail("search.finds", "rc=" + rc + " found=" + found))
                    .assertion("exit_zero", rc == 0)
                    .assertion("hello_skill_listed", found)
                    .metric("outputBytes", output.length());
        });
    }
}
