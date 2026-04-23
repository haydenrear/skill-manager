///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives the transitive-CLI path: add the umbrella fixture (which
 * {@code file:./pip-dep} and {@code file:./npm-dep} reference) and let
 * skill-manager install every sub-skill plus every backend's CLI. This
 * exercises pip (bundled uv) and npm (bundled node) in one pass.
 */
public class UmbrellaInstalled {
    static final NodeSpec SPEC = NodeSpec.of("umbrella.installed")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("env.prepared")
            .tags("cli", "transitive")
            .timeout("600s")
            .output("umbrellaDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("umbrella.installed", "missing env.prepared context");

            Path repoRoot = Path.of(System.getProperty("user.dir")).resolve("..").normalize();
            Path sm = repoRoot.resolve("skill-manager");
            Path umbrella = repoRoot.resolve("test_graph/fixtures/umbrella-skill");

            ProcessBuilder pb = new ProcessBuilder(
                    sm.toString(), "add", umbrella.toString(),
                    "--yes")
                    .inheritIO();
            pb.environment().put("SKILL_MANAGER_HOME", home);
            pb.environment().put("SKILL_MANAGER_INSTALL_DIR", repoRoot.toString());

            int rc = pb.start().waitFor();

            Path storeDir = Path.of(home, "skills");
            boolean umbrellaIn = Files.isDirectory(storeDir.resolve("umbrella-skill"));
            boolean pipIn = Files.isDirectory(storeDir.resolve("pip-cli-skill"));
            boolean npmIn = Files.isDirectory(storeDir.resolve("npm-cli-skill"));

            return (rc == 0 && umbrellaIn && pipIn && npmIn
                    ? NodeResult.pass("umbrella.installed")
                    : NodeResult.fail("umbrella.installed",
                            "rc=" + rc + " umbrella=" + umbrellaIn + " pip=" + pipIn + " npm=" + npmIn))
                    .assertion("add_ok", rc == 0)
                    .assertion("umbrella_in_store", umbrellaIn)
                    .assertion("pip_transitive_in_store", pipIn)
                    .assertion("npm_transitive_in_store", npmIn)
                    .metric("exitCode", rc)
                    .publish("umbrellaDir", storeDir.resolve("umbrella-skill").toString());
        });
    }
}
