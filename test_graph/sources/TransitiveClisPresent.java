///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * After umbrella.installed has run both transitive CLI installs, the
 * bundled uv and node should each have landed a binary in bin/cli — one
 * for the pip-declared tool (pycowsay) and one for the npm-declared tool
 * (cowsay). Also confirms the CLI lock recorded both entries.
 */
public class TransitiveClisPresent {
    static final NodeSpec SPEC = NodeSpec.of("transitive.clis.present")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("umbrella.installed")
            .tags("cli", "transitive")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) return NodeResult.fail("transitive.clis.present", "missing env.prepared context");

            Path cli = Path.of(home, "bin", "cli");
            Path pycow = cli.resolve("pycowsay");
            Path cowsay = cli.resolve("cowsay");

            boolean pipOk = Files.isExecutable(pycow);
            boolean npmOk = Files.isExecutable(cowsay);

            Path lock = Path.of(home, "cli-lock.toml");
            String lockText = Files.isRegularFile(lock) ? Files.readString(lock) : "";
            boolean lockedPip = lockText.contains("[\"pip\".\"pycowsay\"]");
            boolean lockedNpm = lockText.contains("[\"npm\".\"cowsay\"]");

            return (pipOk && npmOk && lockedPip && lockedNpm
                    ? NodeResult.pass("transitive.clis.present")
                    : NodeResult.fail("transitive.clis.present",
                            "pip_bin=" + pipOk + " npm_bin=" + npmOk
                                    + " pip_locked=" + lockedPip + " npm_locked=" + lockedNpm))
                    .assertion("pip_binary_in_bin_cli", pipOk)
                    .assertion("npm_binary_in_bin_cli", npmOk)
                    .assertion("pip_entry_in_cli_lock", lockedPip)
                    .assertion("npm_entry_in_cli_lock", lockedNpm);
        });
    }
}
