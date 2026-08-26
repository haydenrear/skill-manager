///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES ArtifactDagSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ARTI-07's second half, and the node that protects the whole laziness bet:
 * invoking a COLD artifact must refuse in a sentence that names the build.
 *
 * <p>Skipping {@code cache/} in a clone is only defensible if the operator who
 * walks into the gap is handed the way out. Otherwise the trade is: homes get
 * cheap, and every ticket agent that runs a tool from one gets an
 * unattributable {@code No such file or directory} from a shell it never saw,
 * naming a path inside a directory it does not know is skipped by design.
 *
 * <h2>Measured on this branch: there is no refusal</h2>
 *
 * <p>The two id assertions below are <b>RED because the behaviour does not
 * exist yet</b>, not because the test is wrong. Invoking a cold shim in a
 * fresh lazy clone produces, verbatim:
 *
 * <pre>
 *   &lt;clone&gt;/bin/cli/&lt;tool&gt;: line 2: &lt;clone&gt;/cache/skill-script-…/bin/&lt;tool&gt;:
 *       No such file or directory
 *   &lt;clone&gt;/bin/cli/&lt;tool&gt;: line 2: exec: …: cannot execute: No such file or directory
 * </pre>
 *
 * <p>exit 126, with no artifact id and no {@code build} command in it. Going
 * through the home-bound front door does not help either: {@code skill-manager
 * exec --home &lt;clone&gt; -- &lt;tool&gt;} proxies the same two lines and the same
 * 126. And it cannot be produced by a better fixture — {@code CliArtifact}'s
 * own javadoc says so ("that wrapper checks itself and says so at exec time;
 * this one does not"), and no backend in {@code dev.skillmanager.cli.installer}
 * generates a self-checking wrapper. What is missing is the wrapper shape, in
 * production.
 *
 * <p>So this node asserts the outcome ARTI-07 (#108) promises and reports it
 * failing, which is the point of writing it before the fix. The first
 * assertion — that a cold shim at least does not silently succeed — passes,
 * and is what stops the gap from getting worse.
 */
public class ColdArtifactRefusalNamesBuild {

    static final NodeSpec SPEC = NodeSpec.of("cold.artifact.refusal.names.build")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "cold", "refusal")
            .timeout("180s")
            .output("home", "string")
            .output("cloneHome", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "cold-source");
            Path store = ArtifactDagSupport.storeOf(ws);
            Path cloneWs = ArtifactDagSupport.workspace(ctx, "cold-clone");
            Path clone = ArtifactDagSupport.storeOf(cloneWs);
            ArtifactDagSupport.deleteTree(clone);

            Path units = ws.resolve("units");
            Path alpha = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A);
            ProcessRecord install = ArtifactDagSupport.sm(ctx, "install-alpha", store,
                    "install", alpha.toString(), "--yes");
            boolean the_fixture_installed = install.exitCode() == 0;

            ProcessRecord cloned = ArtifactDagSupport.sm(ctx, "home-clone", store,
                    "home", "clone", "--to", clone.toString());
            boolean the_clone_succeeds = cloned.exitCode() == 0;

            Path coldShim = clone.resolve("bin/cli").resolve(ArtifactDagSupport.TOOL_A);
            Path coldTree = clone.resolve("cache")
                    .resolve(ArtifactDagSupport.treeDirName(
                            ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A));
            // The precondition, asserted rather than assumed: this really is a
            // cold artifact — the shim travelled, the tree it runs out of did
            // not. Without it a clone that carried everything would make the
            // refusal assertions vacuous.
            boolean the_shim_travelled_and_its_tree_did_not =
                    Files.isRegularFile(coldShim) && !Files.exists(coldTree);

            ProcessRecord invoke = ArtifactDagSupport.exec(ctx, "invoke-cold-shim", clone,
                    List.of(coldShim.toString()));
            String refusal = ArtifactDagSupport.log(ctx, invoke);

            boolean an_unbuilt_shim_refuses_rather_than_running = invoke.exitCode() != 0;
            boolean the_refusal_names_the_cold_artifact =
                    refusal.contains(ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_A));
            boolean the_refusal_names_the_build_command_that_warms_it =
                    refusal.contains("skill-manager build");

            boolean pass = the_fixture_installed
                    && the_clone_succeeds
                    && the_shim_travelled_and_its_tree_did_not
                    && an_unbuilt_shim_refuses_rather_than_running
                    && the_refusal_names_the_cold_artifact
                    && the_refusal_names_the_build_command_that_warms_it;

            NodeResult result = pass
                    ? NodeResult.pass("cold.artifact.refusal.names.build")
                    : NodeResult.fail("cold.artifact.refusal.names.build",
                            "the_fixture_installed=" + the_fixture_installed
                                    + " the_clone_succeeds=" + the_clone_succeeds
                                    + " the_shim_travelled_and_its_tree_did_not="
                                    + the_shim_travelled_and_its_tree_did_not
                                    + " an_unbuilt_shim_refuses_rather_than_running="
                                    + an_unbuilt_shim_refuses_rather_than_running
                                    + " the_refusal_names_the_cold_artifact="
                                    + the_refusal_names_the_cold_artifact
                                    + " the_refusal_names_the_build_command_that_warms_it="
                                    + the_refusal_names_the_build_command_that_warms_it
                                    + " | invokeExit=" + invoke.exitCode()
                                    + " refusal=" + oneLine(refusal));
            return result
                    .process(install).process(cloned).process(invoke)
                    .assertion("the_fixture_installed", the_fixture_installed)
                    .assertion("the_clone_succeeds", the_clone_succeeds)
                    .assertion("the_shim_travelled_and_its_tree_did_not",
                            the_shim_travelled_and_its_tree_did_not)
                    .assertion("an_unbuilt_shim_refuses_rather_than_running",
                            an_unbuilt_shim_refuses_rather_than_running)
                    .assertion("the_refusal_names_the_cold_artifact",
                            the_refusal_names_the_cold_artifact)
                    .assertion("the_refusal_names_the_build_command_that_warms_it",
                            the_refusal_names_the_build_command_that_warms_it)
                    .metric("coldShimExit", invoke.exitCode())
                    .log("what invoking the cold shim actually printed:\n" + refusal)
                    .publish("home", store.toString())
                    .publish("cloneHome", clone.toString());
        });
    }

    private static String oneLine(String text) {
        String s = text == null ? "" : text.replace('\n', ' ').strip();
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
