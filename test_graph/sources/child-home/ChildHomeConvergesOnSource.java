///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;

/**
 * External.tla {@code UnmodifiedChildUnitsConvergeOnTheirSource}, driven by
 * {@code UpgradeParentStoreUnit} and {@code UpgradeLinkedParentSource}.
 *
 * <p>Two independent upgrades of the parent store, both of which the child copy
 * of {@code chm-unit-b} must pick up:
 *
 * <ol>
 *   <li>The unit's own bytes change. Any freshness check notices this.</li>
 *   <li>The bytes behind the in-unit store link change, while the unit's own
 *       raw tree is untouched. A materializer that digests the RAW source tree
 *       hashes the link as its target string, so this upgrade is invisible to
 *       it and the child keeps stale dereferenced content forever. That is the
 *       {@code External_regression_rawdigest} model, and it is why this node
 *       asserts the linked file separately.</li>
 * </ol>
 *
 * <p>The held-back units from the previous nodes must still be held back, so
 * convergence is not bought by dropping the hold-back rule.
 */
public class ChildHomeConvergesOnSource {
    static final NodeSpec SPEC = NodeSpec.of("child.home.converges.on.source")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.prune.preserves.edits")
            .tags("child-home", "convergence")
            .timeout("300s")
            .output("projectDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.unit.edited", "projectDir").orElse(null);
            String expectedUnit = ctx.get("child.home.unit.edited", "editedUnitContent").orElse(null);
            if (home == null || projectDirRaw == null || expectedUnit == null) {
                return NodeResult.fail("child.home.converges.on.source", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);

            Path childB = ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_B);

            // Upgrade the parent store in place: this is what `install`, `sync`
            // and `upgrade` all end up doing to a unit directory.
            //
            // The two upgrades are driven SEPARATELY and on purpose. If the
            // linked source and the unit's own bytes changed in the same pass,
            // a materializer that digests the raw source tree would still
            // refresh — because the unit's own file changed — and would pick up
            // the new linked content as a side effect. The link-only upgrade
            // first is the one that isolates the freshness bug.
            String newLinked = "linked rev2\n";
            ChildHomeSupport.write(ChildHomeSupport.parentUnit(homeDir, ChildHomeSupport.LINKED_SOURCE)
                    .resolve("NOTE.md"), newLinked);
            ProcessRecord linkedResolve = ChildHomeSupport.sm(ctx, "resolve-after-linked-upgrade", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String sampledLinked = ChildHomeSupport.read(childB.resolve("linked/NOTE.md"));
            boolean linkedBytesConverged = sampledLinked.equals(newLinked);

            String newBody = ChildHomeSupport.skillBody(ChildHomeSupport.UNIT_B, "rev2");
            ChildHomeSupport.write(ChildHomeSupport.parentUnit(homeDir, ChildHomeSupport.UNIT_B)
                    .resolve("SKILL.md"), newBody);
            ProcessRecord resolve = ChildHomeSupport.sm(ctx, "resolve-after-body-upgrade", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());

            boolean resolveOk = linkedResolve.exitCode() == 0 && resolve.exitCode() == 0;
            boolean ownBytesConverged = ChildHomeSupport.read(childB.resolve("SKILL.md"))
                    .equals(newBody);
            boolean linkedStillDereferenced = ChildHomeSupport.isRealFile(childB.resolve("linked/NOTE.md"));

            // The refresh must have re-recorded the tree it wrote. Asserted
            // behaviorally rather than by re-implementing the digest: if the
            // record still described the pre-upgrade tree, this immediately
            // following pass would decide chm-unit-b was locally modified and
            // report it held back.
            ProcessRecord settle = ChildHomeSupport.sm(ctx, "resolve-settled", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String settledJson = ChildHomeSupport.jsonSummary(ChildHomeSupport.log(ctx, "resolve-settled"));
            java.util.Set<String> settledHeldBack = ChildHomeSupport.heldBackUnits(settledJson);
            // heldBackUnits returns an EMPTY set for input it could not parse, and
            // this is the graph's only held-back check phrased negatively — so
            // without the presence guard a JSON-shape change would satisfy
            // !contains(...) and read as "the unit was not held back".
            boolean recordMatchesContent = settle.exitCode() == 0
                    && settledJson.contains("\"heldBack\":")
                    && !settledHeldBack.contains("skill:" + ChildHomeSupport.UNIT_B)
                    && ChildHomeSupport.read(childB.resolve("SKILL.md")).equals(newBody)
                    && ChildHomeSupport.read(childB.resolve("linked/NOTE.md")).equals(newLinked);

            boolean heldBackUnitStillHeldBack = ChildHomeSupport
                    .read(ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_A)
                            .resolve("SKILL.md"))
                    .equals(expectedUnit);

            boolean pass = resolveOk && ownBytesConverged && linkedBytesConverged
                    && linkedStillDereferenced && recordMatchesContent && heldBackUnitStillHeldBack;
            return (pass
                    ? NodeResult.pass("child.home.converges.on.source")
                    : NodeResult.fail("child.home.converges.on.source",
                            "linkedResolveExit=" + linkedResolve.exitCode()
                                    + " resolveExit=" + resolve.exitCode()
                                    + " ownBytesConverged=" + ownBytesConverged
                                    + " linkedBytesConverged=" + linkedBytesConverged
                                    // AS SAMPLED after the link-only resolve. Re-reading
                                    // here would report the state after the later body
                                    // upgrade refreshed the unit, printing "linked rev2"
                                    // beside a false assertion.
                                    + " sampledLinkedAfterLinkOnlyResolve=" + sampledLinked.trim()
                                    + " linkedStillDereferenced=" + linkedStillDereferenced
                                    + " recordMatchesContent=" + recordMatchesContent
                                    + " settleExit=" + settle.exitCode()
                                    + " heldBackUnitStillHeldBack=" + heldBackUnitStillHeldBack))
                    .process(linkedResolve)
                    .process(resolve)
                    .process(settle)
                    .assertion("both_resolves_after_parent_upgrades_ok", resolveOk)
                    .assertion("unmodified_child_unit_picks_up_new_parent_bytes", ownBytesConverged)
                    .assertion("unmodified_child_unit_picks_up_bytes_behind_the_store_link",
                            linkedBytesConverged)
                    .assertion("refreshed_link_content_is_still_dereferenced", linkedStillDereferenced)
                    .assertion("next_pass_agrees_the_refreshed_unit_is_unmodified", recordMatchesContent)
                    .assertion("convergence_did_not_cost_the_hold_back_rule", heldBackUnitStillHeldBack)
                    .metric("linkedResolveExitCode", linkedResolve.exitCode())
                    .metric("resolveExitCode", resolve.exitCode())
                    .publish("projectDir", projectDir.toString());
        });
    }
}
