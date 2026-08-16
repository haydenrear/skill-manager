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
 * ARTI-07: a cloned home DECLARES its artifacts and builds them on demand.
 *
 * <p>{@code home clone} skips {@code cache/}, {@code venvs/}, {@code tools/}
 * and {@code npm/} on purpose — that is what makes a ticket worktree's home
 * cheap. The bet the whole epic rests on is that the copy is nevertheless a
 * complete DESCRIPTION of the home: every artifact still named, every one of
 * them re-derivable, and the missing bytes a normal state rather than damage.
 *
 * <h2>The one assertion that is red on purpose</h2>
 *
 * <p>{@code home_verify_accepts_a_declared_but_unbuilt_home_as_normal} is
 * ARTI-07's stated outcome and it is <b>NOT implemented on this branch</b>.
 * Measured: {@code home verify} on a fresh lazy clone exits 1 and prints
 *
 * <pre>
 *   ✗ 2 reference(s) in &lt;clone&gt; do not resolve — provisioning was never
 *     completed, so the tools they name will fail at exec time
 * </pre>
 *
 * <p>i.e. it treats "declared, not built" as a refusal. It is left asserted and
 * red rather than removed or softened: this is the ticket's acceptance
 * criterion, and a graph that quietly dropped it would report ARTI-07 done.
 *
 * <p>The half that IS shipped is asserted separately and passes:
 * {@code home_verify_names_the_build_that_completes_the_clone} — the refusal
 * carries a runnable {@code skill-manager build '<id>' ...} for exactly the
 * cold artifacts. That is also the contract
 * {@code sources/common/HomeFixpointLaw.java} enforces over every home this
 * graph produces, so the two nodes disagree about whether a refusal is
 * acceptable and BOTH readings are on the record until ARTI-07 lands and
 * settles it.
 */
public class LazyCloneDeclaresWithoutBuilding {

    static final NodeSpec SPEC = NodeSpec.of("lazy.clone.declares.without.building")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("artifact-dag", "home-clone", "lazy")
            .timeout("180s")
            .output("home", "string")
            .output("cloneHome", "string");

    public static void main(String[] a) {
        Node.run(a, SPEC, ctx -> {
            Path ws = ArtifactDagSupport.workspace(ctx, "lazy-source");
            Path store = ArtifactDagSupport.storeOf(ws);
            Path cloneWs = ArtifactDagSupport.workspace(ctx, "lazy-clone");
            Path clone = ArtifactDagSupport.storeOf(cloneWs);
            // `home clone --to` requires a destination that does not exist or
            // is empty; the workspace helper seeded a policy file into it.
            ArtifactDagSupport.deleteTree(clone);

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
            boolean the_source_home_holds_built_cache_trees =
                    ArtifactDagSupport.cacheTrees(store).size() >= 2;

            ProcessRecord cloned = ArtifactDagSupport.sm(ctx, "home-clone", store,
                    "home", "clone", "--to", clone.toString());
            boolean the_clone_succeeds = cloned.exitCode() == 0;

            ProcessRecord list = ArtifactDagSupport.sm(ctx, "artifacts-list-clone", clone,
                    "artifacts", "list", "--json");
            String json = ArtifactDagSupport.log(ctx, list);
            List<String> ids = ArtifactDagSupport.ids(json);

            String shimA = ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_A);
            String shimB = ArtifactDagSupport.shimId(ArtifactDagSupport.TOOL_B);
            boolean the_clone_declares_every_cli_shim_the_source_had =
                    list.exitCode() == 0 && ids.contains(shimA) && ids.contains(shimB);
            boolean the_clone_declares_every_unit_store_the_source_had =
                    ids.contains(ArtifactDagSupport.unitStoreId(ArtifactDagSupport.UNIT_A))
                            && ids.contains(ArtifactDagSupport
                                    .unitStoreId(ArtifactDagSupport.UNIT_B));
            boolean every_declared_shim_reports_itself_unbuilt =
                    "declared-only".equals(ArtifactDagSupport.jsonString(
                                    ArtifactDagSupport.sectionFor(json, shimA), "materialization"))
                            && "declared-only".equals(ArtifactDagSupport.jsonString(
                                    ArtifactDagSupport.sectionFor(json, shimB),
                                    "materialization"));
            List<String> cloneTrees = ArtifactDagSupport.cacheTrees(clone);
            boolean the_clone_carries_no_built_cache_tree = cloneTrees.isEmpty();

            ProcessRecord verify = ArtifactDagSupport.sm(ctx, "home-verify-clone", clone,
                    "home", "verify", "--home", clone.toString());
            String verifyOut = ArtifactDagSupport.log(ctx, verify);

            // SHIPPED: the refusal is runnable — it names `build` and the exact
            // cold artifact ids, which is what HomeFixpointLaw pastes back.
            boolean home_verify_names_the_build_that_completes_the_clone =
                    verifyOut.contains("complete it with: ")
                            && verifyOut.contains(" build ")
                            && verifyOut.contains(shimA) && verifyOut.contains(shimB);
            // NOT SHIPPED: ARTI-07's stated outcome. See the class javadoc.
            boolean home_verify_accepts_a_declared_but_unbuilt_home_as_normal =
                    verify.exitCode() == 0;

            boolean pass = both_fixtures_installed
                    && the_source_home_holds_built_cache_trees
                    && the_clone_succeeds
                    && the_clone_declares_every_cli_shim_the_source_had
                    && the_clone_declares_every_unit_store_the_source_had
                    && every_declared_shim_reports_itself_unbuilt
                    && the_clone_carries_no_built_cache_tree
                    && home_verify_names_the_build_that_completes_the_clone
                    && home_verify_accepts_a_declared_but_unbuilt_home_as_normal;

            NodeResult result = pass
                    ? NodeResult.pass("lazy.clone.declares.without.building")
                    : NodeResult.fail("lazy.clone.declares.without.building",
                            "both_fixtures_installed=" + both_fixtures_installed
                                    + " the_source_home_holds_built_cache_trees="
                                    + the_source_home_holds_built_cache_trees
                                    + " the_clone_succeeds=" + the_clone_succeeds
                                    + " the_clone_declares_every_cli_shim_the_source_had="
                                    + the_clone_declares_every_cli_shim_the_source_had
                                    + " the_clone_declares_every_unit_store_the_source_had="
                                    + the_clone_declares_every_unit_store_the_source_had
                                    + " every_declared_shim_reports_itself_unbuilt="
                                    + every_declared_shim_reports_itself_unbuilt
                                    + " the_clone_carries_no_built_cache_tree="
                                    + the_clone_carries_no_built_cache_tree
                                    + " home_verify_names_the_build_that_completes_the_clone="
                                    + home_verify_names_the_build_that_completes_the_clone
                                    + " home_verify_accepts_a_declared_but_unbuilt_home_as_normal="
                                    + home_verify_accepts_a_declared_but_unbuilt_home_as_normal
                                    + " | cloneExit=" + cloned.exitCode()
                                    + " listExit=" + list.exitCode()
                                    + " verifyExit=" + verify.exitCode()
                                    + " cloneCacheTrees=" + cloneTrees
                                    + " declaredIds=" + ids);
            return result
                    .process(installA).process(installB).process(cloned)
                    .process(list).process(verify)
                    .assertion("both_fixtures_installed", both_fixtures_installed)
                    .assertion("the_source_home_holds_built_cache_trees",
                            the_source_home_holds_built_cache_trees)
                    .assertion("the_clone_succeeds", the_clone_succeeds)
                    .assertion("the_clone_declares_every_cli_shim_the_source_had",
                            the_clone_declares_every_cli_shim_the_source_had)
                    .assertion("the_clone_declares_every_unit_store_the_source_had",
                            the_clone_declares_every_unit_store_the_source_had)
                    .assertion("every_declared_shim_reports_itself_unbuilt",
                            every_declared_shim_reports_itself_unbuilt)
                    .assertion("the_clone_carries_no_built_cache_tree",
                            the_clone_carries_no_built_cache_tree)
                    .assertion("home_verify_names_the_build_that_completes_the_clone",
                            home_verify_names_the_build_that_completes_the_clone)
                    .assertion("home_verify_accepts_a_declared_but_unbuilt_home_as_normal",
                            home_verify_accepts_a_declared_but_unbuilt_home_as_normal)
                    .metric("declaredArtifacts", ids.size())
                    .metric("cloneCacheTrees", cloneTrees.size())
                    .publish("home", store.toString())
                    .publish("cloneHome", clone.toString());
        });
    }
}
