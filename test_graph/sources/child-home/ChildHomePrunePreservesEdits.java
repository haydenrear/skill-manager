///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * External.tla {@code ResolveLeavesOnlyClaimedOrHeldBackUnits} plus
 * {@code AgentEditedChildUnitsAreNeverDestroyed} on the PRUNE path.
 *
 * <p>The manifest is rewritten to claim only {@code chm-unit-b}, so resolve
 * must prune the other three. Two of them the agent edited; one it did not.
 * The clean one must actually be deleted — otherwise this node would pass on an
 * implementation that never prunes at all — and the edited ones must survive
 * and be reported. "No longer a dependency" is not a licence to delete work.
 */
public class ChildHomePrunePreservesEdits {
    static final NodeSpec SPEC = NodeSpec.of("child.home.prune.preserves.edits")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.sync.preserves.edits")
            .tags("child-home", "prune", "no-destruction")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.unit.edited", "projectDir").orElse(null);
            String unitsDirRaw = ctx.get("child.home.project.fixture", "unitsDir").orElse(null);
            String expectedUnit = ctx.get("child.home.unit.edited", "editedUnitContent").orElse(null);
            String expectedLinked = ctx.get("child.home.unit.edited", "editedLinkedContent").orElse(null);
            String digestBefore = ctx.get("child.home.unit.edited", "parentSkillsDigestBeforeEdit")
                    .orElse(null);
            if (home == null || projectDirRaw == null || unitsDirRaw == null
                    || expectedUnit == null || expectedLinked == null || digestBefore == null) {
                return NodeResult.fail("child.home.prune.preserves.edits", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);
            Path unitsDir = Path.of(unitsDirRaw);

            // Drop chm-unit-a (edited), chm-unit-c (clean) and chm-unit-d (edited
            // behind the link); keep only chm-unit-b.
            ChildHomeSupport.write(projectDir.resolve("skill-project.toml"),
                    ChildHomeSupport.projectManifest(unitsDir, ChildHomeSupport.UNIT_B));

            ProcessRecord resolve = ChildHomeSupport.sm(ctx, "resolve-after-drop", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String json = ChildHomeSupport.jsonSummary(ChildHomeSupport.log(ctx, "resolve-after-drop"));
            Set<String> heldBack = ChildHomeSupport.heldBackUnits(json);

            boolean resolveOk = resolve.exitCode() == 0;
            boolean editedUnitSurvivedPrune = ChildHomeSupport
                    .read(ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_A)
                            .resolve("SKILL.md"))
                    .equals(expectedUnit);
            boolean editedLinkedSurvivedPrune = ChildHomeSupport
                    .read(ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_D)
                            .resolve("linked/NOTE.md"))
                    .equals(expectedLinked);
            boolean cleanDroppedUnitPruned = !Files.exists(
                    ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_C),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Set<String> expectedHeldBack = Set.of(
                    "skill:" + ChildHomeSupport.UNIT_A, "skill:" + ChildHomeSupport.UNIT_D);
            boolean reportedExactly = heldBack.equals(expectedHeldBack);
            List<String> present = ChildHomeSupport.childUnitNames(projectDir);
            boolean onlyClaimedOrHeldBack = present.stream().allMatch(name ->
                    name.equals(ChildHomeSupport.UNIT_B) || heldBack.contains("skill:" + name));
            boolean claimedUnitStillPresent = present.contains(ChildHomeSupport.UNIT_B);
            // Pruning the child home must not touch the parent store's units.
            boolean parentStoreUnchanged = digestBefore.equals(
                    ChildHomeSupport.treeDigest(homeDir.resolve("skills")));

            boolean pass = resolveOk && editedUnitSurvivedPrune && editedLinkedSurvivedPrune
                    && cleanDroppedUnitPruned && reportedExactly && onlyClaimedOrHeldBack
                    && claimedUnitStillPresent && parentStoreUnchanged;
            return (pass
                    ? NodeResult.pass("child.home.prune.preserves.edits")
                    : NodeResult.fail("child.home.prune.preserves.edits",
                            "resolveExit=" + resolve.exitCode()
                                    + " editedUnitSurvivedPrune=" + editedUnitSurvivedPrune
                                    + " editedLinkedSurvivedPrune=" + editedLinkedSurvivedPrune
                                    + " cleanDroppedUnitPruned=" + cleanDroppedUnitPruned
                                    + " heldBack=" + heldBack + " expected=" + expectedHeldBack
                                    + " present=" + present
                                    + " onlyClaimedOrHeldBack=" + onlyClaimedOrHeldBack
                                    + " claimedUnitStillPresent=" + claimedUnitStillPresent
                                    + " parentStoreUnchanged=" + parentStoreUnchanged))
                    .process(resolve)
                    .assertion("resolve_after_dropping_dependencies_ok", resolveOk)
                    .assertion("prune_did_not_destroy_the_agent_edit", editedUnitSurvivedPrune)
                    .assertion("prune_did_not_destroy_the_edit_behind_the_link", editedLinkedSurvivedPrune)
                    .assertion("prune_removed_the_unmodified_dropped_unit", cleanDroppedUnitPruned)
                    .assertion("prune_reports_exactly_the_held_back_units", reportedExactly)
                    .assertion("child_home_holds_only_claimed_or_held_back_units", onlyClaimedOrHeldBack)
                    .assertion("still_claimed_unit_survives_prune", claimedUnitStillPresent)
                    .assertion("prune_left_the_parent_store_untouched", parentStoreUnchanged)
                    .metric("resolveExitCode", resolve.exitCode())
                    .metric("heldBackCount", heldBack.size());
        });
    }
}
