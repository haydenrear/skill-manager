///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.Procs;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

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

            // The hyper-experiments graph forces the bundle install path
            // by scrubbing the host's tb-query directory from the install
            // subprocess's PATH (see HyperInstalled). So tb-query MUST land
            // under $home/bin/cli/ — anything else means the bundle path
            // didn't actually run, and we'd be silently testing nothing.
            Path bundled = Path.of(home).resolve("bin/cli/tb-query");
            boolean bundledOk = Files.isRegularFile(bundled) || Files.isSymbolicLink(bundled);
            int rc = -1;
            ProcessRecord proc = null;

            if (bundledOk) {
                ProcessBuilder pb = new ProcessBuilder(bundled.toString(), "--help");
                proc = Procs.run(ctx, "tb-query-help", pb);
                rc = proc.exitCode();
            }

            String binCliListing = "";
            if (!bundledOk || rc != 0) {
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

            boolean pass = bundledOk && rc == 0;
            String runtimeError = proc != null && proc.error() != null ? proc.error() : "";
            String reason = pass ? ""
                    : "tb-query bundle path failed (path=" + bundled
                            + " bundled=" + bundledOk
                            + " rc=" + rc
                            + (runtimeError.isEmpty() ? "" : " err=" + runtimeError)
                            + " bin/cli=[" + binCliListing + "])";

            NodeResult result = pass
                    ? NodeResult.pass("hyper.cli.tbquery")
                    : NodeResult.fail("hyper.cli.tbquery", reason);
            if (proc != null) {
                result = result.process(proc);
            }
            return result
                    .assertion("tb_query_bundled_under_home", bundledOk)
                    .assertion("tb_query_runs_help", rc == 0)
                    .metric("helpExitCode", rc);
        });
    }
}
