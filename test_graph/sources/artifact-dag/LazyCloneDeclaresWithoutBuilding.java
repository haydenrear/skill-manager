///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES ArtifactDagSupport.java
//SOURCES ../lib/HomeIsolation.java

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
 * <h2>ARTI-07 landed, and it took the assertion beside it with it</h2>
 *
 * <p>{@code home_verify_accepts_a_declared_but_unbuilt_home_as_normal} was
 * <b>red on purpose</b> — ARTI-07's stated outcome, asserted before the fix so
 * that a graph could not report the ticket done. It is now GREEN. Measured on
 * this branch, {@code 2026-08-23}, run {@code 20260823-161915}: {@code home
 * verify} on a fresh lazy clone exits <b>0</b> and prints
 *
 * <pre>
 *   ✓ every reference in &lt;clone&gt; resolves, and no path in it reaches any
 *     other Skill Manager home
 * </pre>
 *
 * <p>Beside it sat {@code home_verify_names_the_build_that_completes_the_clone}
 * — that {@code verify}'s REFUSAL carries a runnable {@code build} naming
 * exactly the cold artifact ids. That assertion has been <b>removed</b>, and
 * this paragraph is here so the removal is a decision on the record rather than
 * a red that quietly stopped being counted.
 *
 * <p>It was removed because it is now unsatisfiable by construction, not
 * because it was inconvenient. ARTI-07 did not make the refusal better; it
 * removed the refusal. A cold entry point is no longer a dangling reference for
 * {@code verify} to find — it is a generated {@code skill-manager:cold-artifact}
 * stub that carries the remedy in its own body and exits 86 when run:
 *
 * <pre>
 *   skill-manager: 'ad-alpha-tool' is declared in this home and has not been built.
 *     build it:  skill-manager build 'cli-shim:skill-script/ad-alpha-tool'
 * </pre>
 *
 * <p>So {@code verify} finds nothing to report, prints no {@code complete it
 * with:} line, and the two assertions became mutually exclusive: the one this
 * node was written to fail on can only pass while the other one fails. The
 * claim itself is not dropped — it moved with the behaviour, to
 * {@code cold.artifact.refusal.names.build}, which EXECUTES a cold shim and
 * requires its refusal to name both the artifact id and the {@code build}
 * command. That node was written red for the same ticket and is in this graph.
 *
 * <p><b>Recorded as HIS-17's, and it is not HIS-17's defect.</b> Issue #238
 * predicted this node was red from a verify-TEXT drift caused by HIS-12/HIS-14's
 * remedy and caveat changes. Re-measured, it was not: the remedy text is intact
 * wherever it is still printed, and the cause is ARTI-07 shipping. The lesson
 * kept is the one the ticket is about — an assertion pinned to the text of
 * another component's output goes stale silently, and reads as a fixed defect.
 * The cross-check this node gained instead is asserted in BOTH directions on
 * every run, so it cannot fail that way; see {@code HomeIsolation}.
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

            // --against, so that the SOURCE-REFERENCE half of the check runs.
            //
            // HIS-17. Without it production prints "NOT CHECKED: whether a
            // reference back to the home this one was copied from survives" and
            // skips exactly the half every clone in this repository now
            // exercises: HIS-10 (#227) made `home clone` record its descent in
            // <clone>/home.provenance.json, which names the source home on
            // purpose, and exempts that one file from the isolation rule under
            // byte accounting. This node clones a home on every run and was
            // asking production the weaker question.
            ProcessRecord verify = ArtifactDagSupport.sm(ctx, "home-verify-clone", clone,
                    "home", "verify", "--home", clone.toString(),
                    "--against", store.toString());
            String verifyOut = ArtifactDagSupport.log(ctx, verify);

            // SHIPPED, and now measurably so: ARTI-07's stated outcome. A home
            // that DECLARES artifacts it has not built verifies as normal
            // rather than as damage.
            //
            // This assertion was red on purpose and is green. See the class
            // javadoc for what that cost the assertion below it.
            boolean home_verify_accepts_a_declared_but_unbuilt_home_as_normal =
                    verify.exitCode() == 0;

            // --- PRODUCTION'S ISOLATION VERDICT, and the control for it -----
            //
            // HIS-17: "does anything in this copy name the home it came from"
            // had four answers in this tree, three of them private to a graph.
            // This node is the third graph to ask it, and it asks through
            // HomeIsolation -- one spelling, shared -- rather than growing a
            // fourth walk of its own.
            //
            // Both directions on every run. The clean half alone is satisfied
            // by a production that cannot refuse anything (vacuity ledger,
            // mechanism C), so a real path into the source is planted, refused,
            // named, and removed again. Removal is asserted: four nodes
            // downstream read this clone.
            boolean the_clone_records_the_descent_that_makes_it_a_clone =
                    HomeIsolation.recordsDescentNaming(clone, store.toString());
            boolean production_agrees_no_path_in_the_clone_names_the_source =
                    HomeIsolation.verdictIsClean(verifyOut, clone);
            boolean production_still_refuses_a_planted_path_into_the_source = false;
            try {
                HomeIsolation.plantDecoy(clone, store.resolve("home.policy.toml"));
                ProcessRecord verifyDecoy = ArtifactDagSupport.sm(ctx, "home-verify-decoy",
                        clone, "home", "verify", "--home", clone.toString(),
                        "--against", store.toString());
                production_still_refuses_a_planted_path_into_the_source =
                        verifyDecoy.exitCode() != 0
                                && HomeIsolation.verdictRefusesNaming(
                                        ArtifactDagSupport.log(ctx, verifyDecoy),
                                        HomeIsolation.DECOY_LINK);
            } finally {
                HomeIsolation.removeDecoy(clone);
            }
            boolean the_planted_decoy_is_removed_from_the_clone =
                    HomeIsolation.decoyIsGone(clone);

            boolean pass = both_fixtures_installed
                    && the_source_home_holds_built_cache_trees
                    && the_clone_succeeds
                    && the_clone_declares_every_cli_shim_the_source_had
                    && the_clone_declares_every_unit_store_the_source_had
                    && every_declared_shim_reports_itself_unbuilt
                    && the_clone_carries_no_built_cache_tree
                    && home_verify_accepts_a_declared_but_unbuilt_home_as_normal
                    && the_clone_records_the_descent_that_makes_it_a_clone
                    && production_agrees_no_path_in_the_clone_names_the_source
                    && production_still_refuses_a_planted_path_into_the_source
                    && the_planted_decoy_is_removed_from_the_clone;

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
                                    + " home_verify_accepts_a_declared_but_unbuilt_home_as_normal="
                                    + home_verify_accepts_a_declared_but_unbuilt_home_as_normal
                                    + " | cloneExit=" + cloned.exitCode()
                                    + " listExit=" + list.exitCode()
                                    + " verifyExit=" + verify.exitCode()
                                    + " the_clone_records_the_descent_that_makes_it_a_clone="
                                    + the_clone_records_the_descent_that_makes_it_a_clone
                                    + " production_agrees_no_path_in_the_clone_names_the_source="
                                    + production_agrees_no_path_in_the_clone_names_the_source
                                    + " production_still_refuses_a_planted_path_into_the_source="
                                    + production_still_refuses_a_planted_path_into_the_source
                                    + " the_planted_decoy_is_removed_from_the_clone="
                                    + the_planted_decoy_is_removed_from_the_clone
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
                    .assertion("home_verify_accepts_a_declared_but_unbuilt_home_as_normal",
                            home_verify_accepts_a_declared_but_unbuilt_home_as_normal)
                    .assertion("the_clone_records_the_descent_that_makes_it_a_clone",
                            the_clone_records_the_descent_that_makes_it_a_clone)
                    .assertion("production_agrees_no_path_in_the_clone_names_the_source",
                            production_agrees_no_path_in_the_clone_names_the_source)
                    .assertion("production_still_refuses_a_planted_path_into_the_source",
                            production_still_refuses_a_planted_path_into_the_source)
                    .assertion("the_planted_decoy_is_removed_from_the_clone",
                            the_planted_decoy_is_removed_from_the_clone)
                    .metric("declaredArtifacts", ids.size())
                    .metric("cloneCacheTrees", cloneTrees.size())
                    .metric("verifyExitCode", verify.exitCode())
                    .publish("home", store.toString())
                    .publish("cloneHome", clone.toString());
        });
    }
}
