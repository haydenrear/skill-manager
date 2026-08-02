///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>Step 2 — give the checkout a home, the way the documentation says to.</b>
 *
 * <p>An ACTION node: it performs the step and publishes what it produced, and
 * the assertions about that step live in the three nodes below it. The split is
 * deliberate — {@code onboarding.clone.is.honest},
 * {@code onboarding.clone.drops.foreign.claims} and
 * {@code onboarding.projections.materialized} each read this one log, and
 * running the bootstrap three times would give each of them a different home to
 * talk about.
 *
 * <p>The documented procedure says "copy {@code scripts/agent-home.sh} into the
 * repo root and run it". That file does not ship — see
 * {@code onboarding.docs.and.scripts.static} — so this node does what the
 * workaround does and calls {@code bootstrap-home.sh --root <proj>} directly.
 * That is a finding recorded there, not a shortcut taken here.
 *
 * <h2>What is asserted here, and why only this much</h2>
 *
 * <p>Only that the step REACHED ITS STATE: exit 0, a descriptor on disk, a
 * store holding the source's units, and a checkout still clean. A setup step
 * that quietly did not happen is how an earlier graph in this repository came
 * to assert against a destination it had never put into the state it believed —
 * for as long as the refusal it was ignoring had existed. So the state is
 * checked at the step, loudly, rather than left for a downstream node to report
 * as a symptom naming the wrong thing.
 */
public class OnboardingBootstrapped {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.bootstrapped")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("onboarding.bootstrap.refusals")
            .tags("onboarding", "bootstrap", "script")
            .timeout("900s")
            .output("projectHome", "string")
            .output("bootstrapLog", "string")
            .output("storeUnitCount", "string")
            .output("bootstrapExit", "string")
            .output("sourceForeignLedger", "string")
            .output("cloneForeignLedger", "string")
            .output("sourceForeignChildHomes", "string")
            .output("cloneForeignChildHomes", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fixture = "onboarding.fixture.built";
            Path proj = path(ctx, fixture, "proj");
            Path srcHome = path(ctx, fixture, "srcHome");
            Path ambient = path(ctx, fixture, "ambient");
            Path scriptsDir = path(ctx, fixture, "scriptsDir");
            if (proj == null || srcHome == null || ambient == null || scriptsDir == null) {
                return NodeResult.fail("onboarding.bootstrapped", "missing upstream context");
            }

            ProcessRecord bootstrap = OnboardingSupport.script(ctx, "bootstrap-project-home",
                    proj, scriptsDir.resolve("bootstrap-home.sh"), ambient,
                    "--root", proj.toString(), "--source", srcHome.toString());
            String log = OnboardingSupport.log(ctx, bootstrap);
            Path projectHome = proj.resolve(".skill-manager");

            boolean theBootstrapExitedZero = bootstrap.exitCode() == 0;
            boolean theHomeHasADescriptor =
                    Files.isRegularFile(projectHome.resolve("home.runtime.json"));
            // A clone, not a link: the two homes must be distinguishable on disk
            // before anything downstream can claim an edit stayed in one.
            boolean theProjectHomeIsItsOwnCopy =
                    Files.isDirectory(projectHome)
                            && !projectHome.toRealPath().equals(srcHome.toRealPath());
            int storeUnits = OnboardingSupport.storeUnits(projectHome).size();
            int sourceUnits = OnboardingSupport.storeUnits(srcHome).size();
            boolean theCloneCarriedTheSourcesUnits = storeUnits == sourceUnits && storeUnits >= 3;
            // The home is gitignored, so bootstrapping must leave the checkout
            // clean. If it did not, the work-tree-cleanliness node downstream
            // would be measuring the bootstrap rather than the sync.
            boolean theBootstrapLeftTheCheckoutClean =
                    HomeSyncSupport.git(proj, "status", "--porcelain").trimmed().isEmpty();

            // --- the ledger, captured at the one moment it holds this state ---
            //
            // Read as bytes, here, before ANY skill-manager command runs against
            // either home. Every command reconciles $SKILL_MANAGER_HOME first and
            // that pass rewrites installed/<u>.projections.json from live state,
            // so a downstream node that asked the CLI would erase the inherited
            // records it was about to ask about. Measured: it did, and the
            // assertion read zero foreign rows from a home that had two.
            //
            // "Outside" is computed against each home's OWN root — the source's
            // is the workspace, the clone's is the checkout — from real paths,
            // never from a literal prefix like /Users/.
            Path workspace = srcHome.getParent();
            List<String> sourceForeign =
                    OnboardingSupport.ledgerTargetsOutside(srcHome, workspace);
            List<String> cloneForeign =
                    OnboardingSupport.ledgerTargetsOutside(projectHome, proj);
            List<String> sourceChildHomes =
                    OnboardingSupport.childHomesOutside(srcHome, workspace);
            List<String> cloneChildHomes =
                    OnboardingSupport.childHomesOutside(projectHome, proj);

            boolean pass = theBootstrapExitedZero && theHomeHasADescriptor
                    && theProjectHomeIsItsOwnCopy && theCloneCarriedTheSourcesUnits
                    && theBootstrapLeftTheCheckoutClean;

            return (pass
                    ? NodeResult.pass("onboarding.bootstrapped")
                    : NodeResult.fail("onboarding.bootstrapped",
                            "exit=" + bootstrap.exitCode()
                                    + " descriptor=" + theHomeHasADescriptor
                                    + " storeUnits=" + storeUnits + "/" + sourceUnits
                                    + " gitStatus="
                                    + HomeSyncSupport.git(proj, "status", "--porcelain")
                                            .trimmed()))
                    .process(bootstrap)
                    .assertion("bootstrap_home_sh_exited_zero", theBootstrapExitedZero)
                    .assertion("the_new_home_has_a_runtime_descriptor", theHomeHasADescriptor)
                    .assertion("the_project_home_is_its_own_copy_of_the_source",
                            theProjectHomeIsItsOwnCopy)
                    .assertion("the_clone_carried_every_unit_the_source_home_held",
                            theCloneCarriedTheSourcesUnits)
                    .assertion("bootstrapping_a_home_leaves_the_checkout_clean",
                            theBootstrapLeftTheCheckoutClean)
                    .metric("storeUnits", storeUnits)
                    .metric("sourceUnits", sourceUnits)
                    .publish("projectHome", projectHome.toString())
                    .publish("bootstrapLog", bootstrap.logPath() == null ? ""
                            : ctx.reportDir().resolve(bootstrap.logPath()).toString())
                    .publish("storeUnitCount", String.valueOf(storeUnits))
                    // Published because the projection node needs it: a shortfall
                    // now exits 6, so "did the tool's exit code agree with what it
                    // actually projected" is checkable rather than inferred.
                    .publish("bootstrapExit", String.valueOf(bootstrap.exitCode()))
                    .publish("sourceForeignLedger", String.join("\n", sourceForeign))
                    .publish("cloneForeignLedger", String.join("\n", cloneForeign))
                    .publish("sourceForeignChildHomes", String.join("\n", sourceChildHomes))
                    .publish("cloneForeignChildHomes", String.join("\n", cloneChildHomes));
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
