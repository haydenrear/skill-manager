///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Path;
import java.util.Set;

/**
 * External.tla {@code ResolveProjectChildHome} against
 * {@code AgentEditedChildUnitsAreNeverDestroyed} and
 * {@code EveryPassReportsExactlyTheHeldBackUnits}.
 *
 * <p>The two are asserted separately and BOTH are required. Reporting alone
 * cannot catch silent destruction: once a unit has been overwritten it is no
 * longer modified, so "held-back set matches modified set" is satisfied by the
 * empty set. The survival assertions compare against the exact bytes
 * {@code child.home.unit.edited} published, which is the only check an
 * overwrite cannot satisfy.
 */
public class ChildHomeResolvePreservesEdits {
    static final NodeSpec SPEC = NodeSpec.of("child.home.resolve.preserves.edits")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.edit.stays.in.child.home")
            .tags("child-home", "resolve", "no-destruction")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.unit.edited", "projectDir").orElse(null);
            String expectedUnit = ctx.get("child.home.unit.edited", "editedUnitContent").orElse(null);
            String expectedLinked = ctx.get("child.home.unit.edited", "editedLinkedContent").orElse(null);
            String digestBefore = ctx.get("child.home.unit.edited", "parentSkillsDigestBeforeEdit")
                    .orElse(null);
            if (home == null || projectDirRaw == null || expectedUnit == null
                    || expectedLinked == null || digestBefore == null) {
                return NodeResult.fail("child.home.resolve.preserves.edits", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);

            ProcessRecord resolve = ChildHomeSupport.sm(ctx, "resolve-after-edit", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String json = ChildHomeSupport.jsonSummary(ChildHomeSupport.log(ctx, "resolve-after-edit"));
            Set<String> heldBack = ChildHomeSupport.heldBackUnits(json);

            // The DEFAULT (non-JSON) output is what a human sees, and it is a
            // separate code path from heldBackJson. Nothing asserted it, so a
            // regression there would be invisible to every --json node.
            ProcessRecord human = ChildHomeSupport.sm(ctx, "resolve-human-report", home,
                    "project", "resolve", "--skip-gateway",
                    "--project-dir", projectDir.toString());
            String humanLog = ChildHomeSupport.log(ctx, "resolve-human-report");
            boolean humanReportNamesHeldBackUnits = human.exitCode() == 0
                    && humanLog.contains("held back 2 child-home unit(s) with local changes")
                    && humanLog.contains("skill:" + ChildHomeSupport.UNIT_A)
                    && humanLog.contains("skill:" + ChildHomeSupport.UNIT_D)
                    && humanLog.contains("re-run to take the parent store's version");

            boolean resolveOk = resolve.exitCode() == 0;
            boolean editedUnitSurvived = ChildHomeSupport
                    .read(ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_A)
                            .resolve("SKILL.md"))
                    .equals(expectedUnit);
            boolean editedLinkedSurvived = ChildHomeSupport
                    .read(ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_D)
                            .resolve("linked/NOTE.md"))
                    .equals(expectedLinked);
            Set<String> expectedHeldBack = Set.of(
                    "skill:" + ChildHomeSupport.UNIT_A, "skill:" + ChildHomeSupport.UNIT_D);
            boolean reportedExactly = heldBack.equals(expectedHeldBack);
            boolean cleanUnitsStillClaimed = ChildHomeSupport.childUnitNames(projectDir)
                    .containsAll(java.util.List.of(ChildHomeSupport.UNIT_B, ChildHomeSupport.UNIT_C));
            boolean parentStoreUnchanged = digestBefore.equals(
                    ChildHomeSupport.treeDigest(homeDir.resolve("skills")));

            boolean pass = resolveOk && editedUnitSurvived && editedLinkedSurvived
                    && reportedExactly && humanReportNamesHeldBackUnits
                    && cleanUnitsStillClaimed && parentStoreUnchanged;
            return (pass
                    ? NodeResult.pass("child.home.resolve.preserves.edits")
                    : NodeResult.fail("child.home.resolve.preserves.edits",
                            "resolveExit=" + resolve.exitCode()
                                    + " editedUnitSurvived=" + editedUnitSurvived
                                    + " editedLinkedSurvived=" + editedLinkedSurvived
                                    + " heldBack=" + heldBack + " expected=" + expectedHeldBack
                                    + " humanReportNamesHeldBackUnits=" + humanReportNamesHeldBackUnits
                                    + " humanExit=" + human.exitCode()
                                    + " cleanUnitsStillClaimed=" + cleanUnitsStillClaimed
                                    + " parentStoreUnchanged=" + parentStoreUnchanged))
                    .process(resolve)
                    .process(human)
                    .assertion("resolve_over_modified_child_home_ok", resolveOk)
                    .assertion("resolve_did_not_destroy_the_agent_edit", editedUnitSurvived)
                    .assertion("resolve_did_not_destroy_the_edit_behind_the_link", editedLinkedSurvived)
                    .assertion("resolve_reports_exactly_the_held_back_units", reportedExactly)
                    .assertion("default_output_names_the_held_back_units_and_how_to_take_the_parent_version",
                            humanReportNamesHeldBackUnits)
                    .assertion("resolve_still_claims_the_unmodified_units", cleanUnitsStillClaimed)
                    .assertion("resolve_left_the_parent_store_untouched", parentStoreUnchanged)
                    .metric("resolveExitCode", resolve.exitCode())
                    .metric("heldBackCount", heldBack.size());
        });
    }
}
