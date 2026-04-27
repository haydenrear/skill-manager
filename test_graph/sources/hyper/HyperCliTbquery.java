///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Asserts the {@code tb-query} CLI dependency landed on disk after install.
 *
 * <p>Two paths the binary may take depending on which uv backend ran:
 * <ol>
 *   <li>{@code $SKILL_MANAGER_HOME/bin/cli/tb-query} — the canonical
 *       location skill-manager symlinks into.</li>
 *   <li>A bundled-uv tool venv under {@code $SKILL_MANAGER_HOME/pm/uv/...} —
 *       reachable via {@code skill-manager-skill/scripts/env.sh}.</li>
 * </ol>
 *
 * <p>The node tries the canonical path first and runs {@code tb-query --help}
 * to confirm the binary is functional. If the canonical path is missing,
 * the assertion fails with a directory listing of {@code bin/cli/} so a
 * future debugger can see what landed instead.
 */
public class HyperCliTbquery {
    static final NodeSpec SPEC = NodeSpec.of("hyper.cli.tbquery")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("hyper.installed")
            .tags("hyper", "cli", "tb-query")
            .timeout("30s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            if (home == null) {
                return NodeResult.fail("hyper.cli.tbquery", "missing env.prepared context");
            }

            Path tbq = Path.of(home).resolve("bin/cli/tb-query");
            boolean exists = Files.isRegularFile(tbq) || Files.isSymbolicLink(tbq);
            int rc = -1;
            String runtimeError = "";

            if (exists) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(tbq.toString(), "--help");
                    rc = Procs.runLogged(ctx, "tb-query-help", pb);
                } catch (Exception e) {
                    runtimeError = e.getMessage();
                }
            }

            String binCliListing = "";
            if (!exists || rc != 0) {
                Path binCli = Path.of(home).resolve("bin/cli");
                if (Files.isDirectory(binCli)) {
                    try {
                        binCliListing = String.join(", ",
                                Files.list(binCli)
                                        .map(p -> p.getFileName().toString())
                                        .toList());
                    } catch (Exception ignored) {}
                }
            }

            boolean pass = exists && rc == 0;
            String reason = pass ? ""
                    : "tb-query missing or non-functional (path=" + tbq
                            + " exists=" + exists + " rc=" + rc
                            + (runtimeError.isEmpty() ? "" : " err=" + runtimeError)
                            + " bin/cli=[" + binCliListing + "])";

            NodeResult result = pass
                    ? NodeResult.pass("hyper.cli.tbquery")
                    : NodeResult.fail("hyper.cli.tbquery", reason);
            if (exists) {
                result = Procs.attach(result, ctx, "tb-query-help", rc, 200);
            }
            return result
                    .assertion("tb_query_on_disk", exists)
                    .assertion("tb_query_runs_help", rc == 0)
                    .metric("helpExitCode", rc);
        });
    }
}
