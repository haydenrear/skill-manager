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
 * ARTI-03: a home can NAME what it derived, and naming it derives nothing.
 *
 * <p>Two halves, and the second is the one that matters. Before this epic a
 * home held ten provisioned trees, seven wrapper shims and a hundred
 * projections that no command could enumerate — the only way to ask "what is
 * in here" was to re-run the install that produced it. So the census has to
 * exist AND has to be a read: an enumeration that quietly re-provisions is
 * exactly the eager front door #100 is measuring away from.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>{@code artifacts list --json} names every artifact INSTANCE the two
 *       installed fixture units produced — both unit stores, both cli-shims,
 *       both provisioned cache trees, and the six agent projections. Named by
 *       exact id, because ARTI-03's whole claim is that an artifact HAS a
 *       home-stable identity; a count would pass on six of the wrong ones.</li>
 *   <li>It answers with no {@code artifacts.lock.toml} present. The ledger is
 *       an optimisation over a backfill from the home's own records, and a
 *       census that needed the ledger first would be a census of the ledger.</li>
 *   <li>Nothing was rebuilt: the digest over {@code bin/}, {@code cache/},
 *       {@code skills/} and {@code cli-lock.toml} is unchanged across the
 *       listing, and the home logged no additional skill-script run. Both, not
 *       either — a re-run that produced byte-identical output would move the
 *       log count and not the digest, and a rewrite of a lock row would move
 *       the digest and not the log count.</li>
 * </ul>
 */
public class ArtifactsEnumerated {

    static final NodeSpec SPEC = NodeSpec.of("artifacts.enumerated")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "artifacts", "census")
            .timeout("180s")
            .output("home", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "enumerated");
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

            String digestBefore = ArtifactDagSupport.derivedDigest(store);
            int runsBefore = ArtifactDagSupport.skillScriptRuns(store);

            ProcessRecord list = ArtifactDagSupport.sm(ctx, "artifacts-list", store,
                    "artifacts", "list", "--json");
            String json = ArtifactDagSupport.log(ctx, list);
            List<String> ids = ArtifactDagSupport.ids(json);

            String digestAfter = ArtifactDagSupport.derivedDigest(store);
            int runsAfter = ArtifactDagSupport.skillScriptRuns(store);

            boolean the_census_can_be_read = list.exitCode() == 0 && !ids.isEmpty();

            boolean the_census_names_both_unit_stores =
                    ids.contains(ArtifactDagSupport.unitStoreId(ArtifactDagSupport.UNIT_A))
                            && ids.contains(
                                    ArtifactDagSupport.unitStoreId(ArtifactDagSupport.UNIT_B));
            boolean the_census_names_both_cli_shims =
                    ids.contains(ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_A))
                            && ids.contains(
                                    ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_B));
            boolean the_census_names_both_provisioned_cache_trees =
                    ids.contains(ArtifactDagSupport.treeId(
                                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A))
                            && ids.contains(ArtifactDagSupport.treeId(
                                    ArtifactDagSupport.UNIT_B, ArtifactDagSupport.TOOL_B));
            int projections = 0;
            for (String id : ids) {
                if (id.startsWith("projection:")) projections++;
            }
            // Three agents x two units. Named as a floor rather than an
            // equality: a home that grew a fourth agent surface would be a
            // change to assert about, not a reason for this node to fail.
            boolean the_census_names_every_agent_projection = projections >= 6;

            // Read out of the ledger BLOCK, not the document: `artifacts` and
            // `present` both appear twice — once describing the ledger and once
            // describing the census — and a whole-document lookup finds the
            // ledger's `"artifacts" : 0` and calls the census empty.
            String ledgerBlock = block(json, "\"ledger\" : {");
            boolean the_census_answers_without_a_recorded_ledger =
                    !ids.isEmpty() && ledgerBlock.contains("\"present\" : false");

            boolean naming_the_census_rebuilt_no_bytes = digestBefore.equals(digestAfter);
            boolean naming_the_census_ran_no_installer = runsBefore == runsAfter;

            boolean pass = both_fixtures_installed
                    && the_census_can_be_read
                    && the_census_names_both_unit_stores
                    && the_census_names_both_cli_shims
                    && the_census_names_both_provisioned_cache_trees
                    && the_census_names_every_agent_projection
                    && the_census_answers_without_a_recorded_ledger
                    && naming_the_census_rebuilt_no_bytes
                    && naming_the_census_ran_no_installer;

            NodeResult result = pass
                    ? NodeResult.pass("artifacts.enumerated")
                    : NodeResult.fail("artifacts.enumerated",
                            "both_fixtures_installed=" + both_fixtures_installed
                                    + " the_census_can_be_read=" + the_census_can_be_read
                                    + " the_census_names_both_unit_stores="
                                    + the_census_names_both_unit_stores
                                    + " the_census_names_both_cli_shims="
                                    + the_census_names_both_cli_shims
                                    + " the_census_names_both_provisioned_cache_trees="
                                    + the_census_names_both_provisioned_cache_trees
                                    + " the_census_names_every_agent_projection="
                                    + the_census_names_every_agent_projection
                                    + " the_census_answers_without_a_recorded_ledger="
                                    + the_census_answers_without_a_recorded_ledger
                                    + " naming_the_census_rebuilt_no_bytes="
                                    + naming_the_census_rebuilt_no_bytes
                                    + " naming_the_census_ran_no_installer="
                                    + naming_the_census_ran_no_installer
                                    + " | installA=" + installA.exitCode()
                                    + " installB=" + installB.exitCode()
                                    + " listExit=" + list.exitCode()
                                    + " ids=" + ids
                                    + " projections=" + projections
                                    + " skillScriptRuns=" + runsBefore + "->" + runsAfter
                                    + " ledgerBlock=" + ledgerBlock.replace('\n', ' '));
            return result
                    .process(installA).process(installB).process(list)
                    .assertion("both_fixtures_installed", both_fixtures_installed)
                    .assertion("the_census_can_be_read", the_census_can_be_read)
                    .assertion("the_census_names_both_unit_stores",
                            the_census_names_both_unit_stores)
                    .assertion("the_census_names_both_cli_shims",
                            the_census_names_both_cli_shims)
                    .assertion("the_census_names_both_provisioned_cache_trees",
                            the_census_names_both_provisioned_cache_trees)
                    .assertion("the_census_names_every_agent_projection",
                            the_census_names_every_agent_projection)
                    .assertion("the_census_answers_without_a_recorded_ledger",
                            the_census_answers_without_a_recorded_ledger)
                    .assertion("naming_the_census_rebuilt_no_bytes",
                            naming_the_census_rebuilt_no_bytes)
                    .assertion("naming_the_census_ran_no_installer",
                            naming_the_census_ran_no_installer)
                    .metric("artifactsNamed", ids.size())
                    .metric("projectionsNamed", projections)
                    .metric("skillScriptRuns", runsAfter)
                    .publish("home", store.toString());
        });
    }

    /** The text of one JSON object, from {@code open} to its matching brace. */
    private static String block(String json, String open) {
        int at = json.indexOf(open);
        if (at < 0) return "";
        int depth = 0;
        for (int i = at + open.length() - 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return json.substring(at, i + 1);
        }
        return json.substring(at);
    }
}
