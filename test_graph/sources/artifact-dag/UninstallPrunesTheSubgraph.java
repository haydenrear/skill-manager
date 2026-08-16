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
 * ARTI-08: the teardown edges — when an owner goes, its subgraph goes with it.
 *
 * <p>Every artifact in the DAG has an owner, and an uninstall is the one
 * operation that deletes an owner. The property is a round trip: install a unit
 * into a home that already holds another, uninstall it, and the home is back
 * where it started. Anything the pair leaves behind is an artifact whose owner
 * no longer exists — unreachable from any unit, invisible to every command that
 * enumerates by owner, and paid for on disk forever.
 *
 * <h2>What "byte-comparable" means here, exactly</h2>
 *
 * <p>The comparison is over every top-level entry of the home EXCEPT its
 * journals — {@code logs/}, {@code audit.log} and {@code tmp/} — which are
 * append-only records of what happened and are SUPPOSED to differ. Everything
 * else is state the pair is expected to restore: {@code skills/}, {@code bin/},
 * {@code cache/}, {@code installed/}, both lock files, the generated
 * marketplace. Measured on this branch, both lock files and every record come
 * back byte-identical, so the exclusion is not hiding anything: the digest
 * fails on exactly one thing.
 *
 * <h2>The two assertions that are red on purpose</h2>
 *
 * <p>Measured: {@code uninstall} prunes the store entry, the {@code bin/cli}
 * shim and the {@code cli-lock.toml} row — and
 * <b>leaves {@code cache/skill-script-&lt;unit&gt;-&lt;tool&gt;/} on disk</b>.
 * That directory is the {@code provisioned-tree} artifact the removed unit's
 * install produced, and it is precisely the edge ARTI-08 (#109) exists to draw.
 * The tree is credited to its shim by {@code ArtifactBackfill.provisionedTrees}
 * for the purposes of REPORTING, and {@code ArtifactBuild} refuses to treat it
 * as a build target because the inference is at the wrong granularity — so
 * today nothing at all is responsible for removing it.
 *
 * <p>{@code the_provisioned_tree_the_removed_unit_produced_is_gone} and
 * {@code the_home_is_byte_comparable_to_before_the_install} are the same
 * finding seen from two directions, and both are left asserted and red. The
 * rest of the teardown is asserted separately and passes, so the report says
 * which part of ARTI-08 is already true.
 *
 * <h2>What this node does NOT assert, said plainly</h2>
 *
 * <p>An earlier version of this comment credited the uninstall with pruning
 * "all three agent projections". It does prune them — that was observed — but
 * <b>no assertion here checks it</b>, and a review was right to call the claim
 * out. The byte-comparison assertion would catch a leftover projection
 * incidentally, which is not the same as testing for one and is not what the
 * sentence said. The eleven assertions below are the complete list of what is
 * actually established; projections are covered by name in the
 * {@code home-integrity} graph instead.
 */
public class UninstallPrunesTheSubgraph {

    static final NodeSpec SPEC = NodeSpec.of("uninstall.prunes.the.subgraph")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "uninstall", "teardown")
            .timeout("180s")
            .output("home", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "teardown");
            Path store = ArtifactDagSupport.storeOf(ws);
            Path units = ws.resolve("units");
            Path resident = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A);
            Path transient_ = ArtifactDagSupport.scaffoldUnit(units,
                    ArtifactDagSupport.UNIT_T, ArtifactDagSupport.TOOL_T);

            // A home that is not empty, so "byte-comparable" is a statement
            // about a populated home rather than about two empty directories.
            ProcessRecord installResident = ArtifactDagSupport.sm(ctx, "install-resident", store,
                    "install", resident.toString(), "--yes");
            boolean the_resident_unit_installed = installResident.exitCode() == 0;

            String digestBefore = ArtifactDagSupport.homeDigest(store);
            List<String> inventoryBefore = ArtifactDagSupport.homeInventory(store);

            ProcessRecord installTransient = ArtifactDagSupport.sm(ctx, "install-transient", store,
                    "install", transient_.toString(), "--yes");
            boolean the_transient_unit_installed = installTransient.exitCode() == 0;

            ProcessRecord listWith = ArtifactDagSupport.sm(ctx, "artifacts-list-with", store,
                    "artifacts", "list", "--json");
            List<String> idsWith = ArtifactDagSupport.ids(ArtifactDagSupport.log(ctx, listWith));
            String shimT = ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_T);
            String treeT = ArtifactDagSupport.treeId(
                    ArtifactDagSupport.UNIT_T, ArtifactDagSupport.TOOL_T);
            // The precondition: the subgraph to be pruned actually existed.
            boolean the_census_grew_the_removed_units_artifacts =
                    idsWith.contains(shimT) && idsWith.contains(treeT)
                            && idsWith.contains(
                                    ArtifactDagSupport.unitStoreId(ArtifactDagSupport.UNIT_T));

            ProcessRecord uninstall = ArtifactDagSupport.sm(ctx, "uninstall-transient", store,
                    "uninstall", ArtifactDagSupport.UNIT_T, "--yes");
            boolean the_uninstall_succeeds = uninstall.exitCode() == 0;

            ProcessRecord listAfter = ArtifactDagSupport.sm(ctx, "artifacts-list-after", store,
                    "artifacts", "list", "--json");
            List<String> idsAfter = ArtifactDagSupport.ids(ArtifactDagSupport.log(ctx, listAfter));
            List<String> survivors = idsAfter.stream()
                    .filter(id -> id.contains(ArtifactDagSupport.UNIT_T)
                            || id.contains(ArtifactDagSupport.TOOL_T))
                    .toList();

            boolean the_removed_units_store_entry_is_gone =
                    !Files.exists(store.resolve("skills").resolve(ArtifactDagSupport.UNIT_T));
            boolean the_removed_units_cli_shim_is_gone =
                    !Files.exists(store.resolve("bin/cli").resolve(ArtifactDagSupport.TOOL_T));
            boolean the_removed_units_lock_row_is_gone =
                    ArtifactDagSupport.cliLockRows(store).stream()
                            .noneMatch(row -> ArtifactDagSupport.lockValue(row, "binary")
                                    .equals(ArtifactDagSupport.TOOL_T));
            boolean the_resident_units_artifacts_survive =
                    idsAfter.contains(ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_A))
                            && idsAfter.contains(ArtifactDagSupport.treeId(
                                    ArtifactDagSupport.UNIT_A, ArtifactDagSupport.TOOL_A));
            boolean the_provisioned_tree_the_removed_unit_produced_is_gone =
                    !Files.exists(store.resolve("cache")
                            .resolve(ArtifactDagSupport.treeDirName(
                                    ArtifactDagSupport.UNIT_T, ArtifactDagSupport.TOOL_T)));
            boolean the_census_names_nothing_the_removed_unit_owned = survivors.isEmpty();

            String digestAfter = ArtifactDagSupport.homeDigest(store);
            List<String> inventoryAfter = ArtifactDagSupport.homeInventory(store);
            boolean the_home_is_byte_comparable_to_before_the_install =
                    digestBefore.equals(digestAfter);

            boolean pass = the_resident_unit_installed
                    && the_transient_unit_installed
                    && the_census_grew_the_removed_units_artifacts
                    && the_uninstall_succeeds
                    && the_removed_units_store_entry_is_gone
                    && the_removed_units_cli_shim_is_gone
                    && the_removed_units_lock_row_is_gone
                    && the_resident_units_artifacts_survive
                    && the_provisioned_tree_the_removed_unit_produced_is_gone
                    && the_census_names_nothing_the_removed_unit_owned
                    && the_home_is_byte_comparable_to_before_the_install;

            NodeResult result = pass
                    ? NodeResult.pass("uninstall.prunes.the.subgraph")
                    : NodeResult.fail("uninstall.prunes.the.subgraph",
                            "the_resident_unit_installed=" + the_resident_unit_installed
                                    + " the_transient_unit_installed="
                                    + the_transient_unit_installed
                                    + " the_census_grew_the_removed_units_artifacts="
                                    + the_census_grew_the_removed_units_artifacts
                                    + " the_uninstall_succeeds=" + the_uninstall_succeeds
                                    + " the_removed_units_store_entry_is_gone="
                                    + the_removed_units_store_entry_is_gone
                                    + " the_removed_units_cli_shim_is_gone="
                                    + the_removed_units_cli_shim_is_gone
                                    + " the_removed_units_lock_row_is_gone="
                                    + the_removed_units_lock_row_is_gone
                                    + " the_resident_units_artifacts_survive="
                                    + the_resident_units_artifacts_survive
                                    + " the_provisioned_tree_the_removed_unit_produced_is_gone="
                                    + the_provisioned_tree_the_removed_unit_produced_is_gone
                                    + " the_census_names_nothing_the_removed_unit_owned="
                                    + the_census_names_nothing_the_removed_unit_owned
                                    + " the_home_is_byte_comparable_to_before_the_install="
                                    + the_home_is_byte_comparable_to_before_the_install
                                    + " | survivingIds=" + survivors
                                    + " onlyAfter="
                                    + ArtifactDagSupport.only(inventoryAfter, inventoryBefore, 12)
                                    + " onlyBefore="
                                    + ArtifactDagSupport.only(inventoryBefore, inventoryAfter, 12)
                                    + " uninstallExit=" + uninstall.exitCode());
            return result
                    .process(installResident).process(installTransient)
                    .process(listWith).process(uninstall).process(listAfter)
                    .assertion("the_resident_unit_installed", the_resident_unit_installed)
                    .assertion("the_transient_unit_installed", the_transient_unit_installed)
                    .assertion("the_census_grew_the_removed_units_artifacts",
                            the_census_grew_the_removed_units_artifacts)
                    .assertion("the_uninstall_succeeds", the_uninstall_succeeds)
                    .assertion("the_removed_units_store_entry_is_gone",
                            the_removed_units_store_entry_is_gone)
                    .assertion("the_removed_units_cli_shim_is_gone",
                            the_removed_units_cli_shim_is_gone)
                    .assertion("the_removed_units_lock_row_is_gone",
                            the_removed_units_lock_row_is_gone)
                    .assertion("the_resident_units_artifacts_survive",
                            the_resident_units_artifacts_survive)
                    .assertion("the_provisioned_tree_the_removed_unit_produced_is_gone",
                            the_provisioned_tree_the_removed_unit_produced_is_gone)
                    .assertion("the_census_names_nothing_the_removed_unit_owned",
                            the_census_names_nothing_the_removed_unit_owned)
                    .assertion("the_home_is_byte_comparable_to_before_the_install",
                            the_home_is_byte_comparable_to_before_the_install)
                    .metric("artifactsWithTransient", idsWith.size())
                    .metric("artifactsAfterUninstall", idsAfter.size())
                    .log("entries only in the AFTER inventory:\n  "
                            + String.join("\n  ",
                                    ArtifactDagSupport.only(inventoryAfter, inventoryBefore, 50))
                            + "\nentries only in the BEFORE inventory:\n  "
                            + String.join("\n  ",
                                    ArtifactDagSupport.only(inventoryBefore, inventoryAfter, 50)))
                    .publish("home", store.toString());
        });
    }
}
