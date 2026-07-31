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
 * External.tla {@code SyncProjectChildHome}.
 *
 * <p>{@code project sync} currently tears the project realization down and
 * re-resolves it ("placeholder uninstall/reinstall"). That makes it the most
 * dangerous path for an agent's work in the child home, and the one where a
 * hold-back regression would be easiest to miss: the destruction would look
 * like ordinary teardown. Same survival + reporting pair as the resolve node,
 * driven through {@code project sync}.
 */
public class ChildHomeSyncPreservesEdits {
    static final NodeSpec SPEC = NodeSpec.of("child.home.sync.preserves.edits")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.resolve.preserves.edits")
            .tags("child-home", "sync", "no-destruction")
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
                return NodeResult.fail("child.home.sync.preserves.edits", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);

            ProcessRecord sync = ChildHomeSupport.sm(ctx, "sync-after-edit", home,
                    "project", "sync", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String json = ChildHomeSupport.jsonSummary(ChildHomeSupport.log(ctx, "sync-after-edit"));
            Set<String> heldBack = ChildHomeSupport.heldBackUnits(json);

            boolean syncOk = sync.exitCode() == 0;
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
            boolean parentStoreUnchanged = digestBefore.equals(
                    ChildHomeSupport.treeDigest(homeDir.resolve("skills")));

            boolean pass = syncOk && editedUnitSurvived && editedLinkedSurvived
                    && reportedExactly && parentStoreUnchanged;
            return (pass
                    ? NodeResult.pass("child.home.sync.preserves.edits")
                    : NodeResult.fail("child.home.sync.preserves.edits",
                            "syncExit=" + sync.exitCode()
                                    + " editedUnitSurvived=" + editedUnitSurvived
                                    + " editedLinkedSurvived=" + editedLinkedSurvived
                                    + " heldBack=" + heldBack + " expected=" + expectedHeldBack
                                    + " parentStoreUnchanged=" + parentStoreUnchanged))
                    .process(sync)
                    .assertion("sync_over_modified_child_home_ok", syncOk)
                    .assertion("sync_did_not_destroy_the_agent_edit", editedUnitSurvived)
                    .assertion("sync_did_not_destroy_the_edit_behind_the_link", editedLinkedSurvived)
                    .assertion("sync_reports_exactly_the_held_back_units", reportedExactly)
                    .assertion("sync_left_the_parent_store_untouched", parentStoreUnchanged)
                    .metric("syncExitCode", sync.exitCode())
                    .metric("heldBackCount", heldBack.size());
        });
    }
}
