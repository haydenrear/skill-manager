///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES ArtifactDagSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * ARTI-05: one moved input, two stale artifacts, and no install to find out.
 *
 * <p>Editing a unit's {@code skill-scripts/} tree moves the declared inputs of
 * the install that produced BOTH the {@code bin/cli/} shim and the
 * {@code cache/} tree that shim execs into. They are two artifacts, not one
 * inferred from the other: {@code cli-lock.toml} holds a single fingerprint row
 * per dep, and {@code ArtifactBackfill.provisionedTrees} credits the tree to
 * the shim by reading the shim's body — the edge is derived from the disk, not
 * from a naming convention.
 *
 * <h2>Why "without an install" is the assertion and not the setup</h2>
 *
 * <p>The pre-epic way to learn a shim was stale was to run the install and see
 * whether it did anything. That is the eager front door: the question costs as
 * much as the answer. So this node measures the home before and after asking,
 * over the derived surfaces and over the skill-script run log, and requires
 * both to be unchanged. An {@code artifacts stale} that quietly reinstalled
 * would answer correctly and still be the defect.
 *
 * <h2>And the sibling stays out of it</h2>
 *
 * <p>The home holds a second unit with its own shim and its own tree, whose
 * inputs did not move. If staleness were computed per home, per backend or per
 * lock file rather than per artifact, the sibling would be dragged in — so it
 * is named explicitly rather than left to a count.
 */
public class EditedInputMarksDependentsStale {

    static final NodeSpec SPEC = NodeSpec.of("edited.input.marks.dependents.stale")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "artifacts", "stale", "edges")
            .timeout("180s")
            .output("home", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "stale");
            Path store = ArtifactDagSupport.storeOf(ws);
            Path units = ws.resolve("units");
            Path alpha = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A);
            Path beta = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_B, ArtifactDagSupport.TOOL_B);

            ProcessRecord installA = ArtifactDagSupport.sm(ctx, "install-alpha", store,
                    "install", alpha.toString(), "--yes");
            ProcessRecord installB = ArtifactDagSupport.sm(ctx, "install-beta", store,
                    "install", beta.toString(), "--yes");
            boolean both_fixtures_installed =
                    installA.exitCode() == 0 && installB.exitCode() == 0;

            // Clean slate: with nothing edited, nothing may be stale. Asserted
            // rather than assumed — a home that reports everything stale from
            // birth would make the interesting assertion below vacuous.
            ProcessRecord before = ArtifactDagSupport.sm(ctx, "stale-before", store,
                    "artifacts", "stale", "--json");
            String beforeJson = ArtifactDagSupport.log(ctx, before);
            boolean nothing_is_stale_before_the_edit =
                    before.exitCode() == 0 && ArtifactDagSupport.jsonInt(beforeJson, "stale") == 0;

            boolean the_units_skill_script_was_edited = ArtifactDagSupport
                    .editInstalledScript(store, ArtifactDagSupport.UNIT_A,
                            ArtifactDagSupport.TOOL_A);

            String digestBefore = ArtifactDagSupport.derivedDigest(store);
            int runsBefore = ArtifactDagSupport.skillScriptRuns(store);

            ProcessRecord after = ArtifactDagSupport.sm(ctx, "stale-after", store,
                    "artifacts", "stale", "--json");
            String afterJson = ArtifactDagSupport.log(ctx, after);
            List<String> stale = ArtifactDagSupport.ids(
                    section(afterJson, "\"stale\" : [", "\"unverifiable\" : ["));

            String digestAfter = ArtifactDagSupport.derivedDigest(store);
            int runsAfter = ArtifactDagSupport.skillScriptRuns(store);

            String shimA = ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_A);
            String treeA = ArtifactDagSupport.treeId(
                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A);
            String shimB = ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_B);
            String treeB = ArtifactDagSupport.treeId(
                    ArtifactDagSupport.UNIT_B, ArtifactDagSupport.TOOL_B);

            boolean stale_can_be_asked = after.exitCode() == 0;
            boolean stale_names_the_edited_units_shim = stale.contains(shimA);
            boolean stale_names_the_cache_tree_that_shim_runs_out_of = stale.contains(treeA);
            boolean stale_leaves_the_untouched_sibling_alone =
                    !stale.contains(shimB) && !stale.contains(treeB);
            // The reason quotes the basis the backend recorded, so an operator
            // reading it learns WHICH bytes moved rather than only that some did.
            boolean stale_says_which_inputs_moved =
                    afterJson.contains("its declared inputs moved")
                            && afterJson.contains("skill-scripts/ tree bytes");
            boolean asking_what_is_stale_rebuilt_no_bytes = digestBefore.equals(digestAfter);
            boolean asking_what_is_stale_ran_no_installer = runsBefore == runsAfter;

            boolean pass = both_fixtures_installed
                    && nothing_is_stale_before_the_edit
                    && the_units_skill_script_was_edited
                    && stale_can_be_asked
                    && stale_names_the_edited_units_shim
                    && stale_names_the_cache_tree_that_shim_runs_out_of
                    && stale_leaves_the_untouched_sibling_alone
                    && stale_says_which_inputs_moved
                    && asking_what_is_stale_rebuilt_no_bytes
                    && asking_what_is_stale_ran_no_installer;

            NodeResult result = pass
                    ? NodeResult.pass("edited.input.marks.dependents.stale")
                    : NodeResult.fail("edited.input.marks.dependents.stale",
                            "both_fixtures_installed=" + both_fixtures_installed
                                    + " nothing_is_stale_before_the_edit="
                                    + nothing_is_stale_before_the_edit
                                    + " the_units_skill_script_was_edited="
                                    + the_units_skill_script_was_edited
                                    + " stale_can_be_asked=" + stale_can_be_asked
                                    + " stale_names_the_edited_units_shim="
                                    + stale_names_the_edited_units_shim
                                    + " stale_names_the_cache_tree_that_shim_runs_out_of="
                                    + stale_names_the_cache_tree_that_shim_runs_out_of
                                    + " stale_leaves_the_untouched_sibling_alone="
                                    + stale_leaves_the_untouched_sibling_alone
                                    + " stale_says_which_inputs_moved="
                                    + stale_says_which_inputs_moved
                                    + " asking_what_is_stale_rebuilt_no_bytes="
                                    + asking_what_is_stale_rebuilt_no_bytes
                                    + " asking_what_is_stale_ran_no_installer="
                                    + asking_what_is_stale_ran_no_installer
                                    + " | staleIds=" + stale
                                    + " staleBeforeExit=" + before.exitCode()
                                    + " staleAfterExit=" + after.exitCode()
                                    + " skillScriptRuns=" + runsBefore + "->" + runsAfter);
            return result
                    .process(installA).process(installB).process(before).process(after)
                    .assertion("both_fixtures_installed", both_fixtures_installed)
                    .assertion("nothing_is_stale_before_the_edit",
                            nothing_is_stale_before_the_edit)
                    .assertion("the_units_skill_script_was_edited",
                            the_units_skill_script_was_edited)
                    .assertion("stale_can_be_asked", stale_can_be_asked)
                    .assertion("stale_names_the_edited_units_shim",
                            stale_names_the_edited_units_shim)
                    .assertion("stale_names_the_cache_tree_that_shim_runs_out_of",
                            stale_names_the_cache_tree_that_shim_runs_out_of)
                    .assertion("stale_leaves_the_untouched_sibling_alone",
                            stale_leaves_the_untouched_sibling_alone)
                    .assertion("stale_says_which_inputs_moved", stale_says_which_inputs_moved)
                    .assertion("asking_what_is_stale_rebuilt_no_bytes",
                            asking_what_is_stale_rebuilt_no_bytes)
                    .assertion("asking_what_is_stale_ran_no_installer",
                            asking_what_is_stale_ran_no_installer)
                    .metric("staleAfterEdit", stale.size())
                    .publish("home", store.toString());
        });
    }

    /**
     * The slice of the {@code stale --json} document between two array keys.
     *
     * <p>{@code stale} and {@code unverifiable} are sibling arrays of objects
     * with the same field names, so an id read from the whole document could
     * come from either. The verdict under test is membership of the FIRST one.
     */
    private static String section(String json, String from, String to) {
        int start = json.indexOf(from);
        if (start < 0) return "";
        int end = json.indexOf(to, start);
        return end < 0 ? json.substring(start) : json.substring(start, end);
    }
}
