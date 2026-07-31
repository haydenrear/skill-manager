///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * External.tla {@code ChildHomeWritesNeverReachTheParentStore}, empirically.
 *
 * <p>The agent has just written into two places in the child home. The parent
 * store must be byte-for-byte what it was before those writes — compared as a
 * NOFOLLOW digest over the whole {@code <home>/skills} tree, so a symlink that
 * changed target counts as a change and a symlink's target contents are never
 * mistaken for the link itself.
 *
 * <p>Also asserts the mechanism, not just the outcome: no symlink at or below
 * any child unit resolves into the parent store. A single such link is a live
 * write-through channel even if this particular edit happened to miss it.
 */
public class ChildHomeEditStaysInChildHome {
    static final NodeSpec SPEC = NodeSpec.of("child.home.edit.stays.in.child.home")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.unit.edited")
            .tags("child-home", "independence")
            .timeout("120s");

    private static final String[] UNITS = {
            ChildHomeSupport.UNIT_A, ChildHomeSupport.UNIT_B,
            ChildHomeSupport.UNIT_C, ChildHomeSupport.UNIT_D };

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.unit.edited", "projectDir").orElse(null);
            String digestBefore = ctx.get("child.home.unit.edited", "parentSkillsDigestBeforeEdit")
                    .orElse(null);
            String marker = ctx.get("child.home.unit.edited", "editMarker").orElse(null);
            if (home == null || projectDirRaw == null || digestBefore == null || marker == null) {
                return NodeResult.fail("child.home.edit.stays.in.child.home", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);
            Path parentSkills = homeDir.resolve("skills");

            String digestAfter = ChildHomeSupport.treeDigest(parentSkills);
            boolean parentStoreUnchanged = digestBefore.equals(digestAfter);

            // read() returns "" for a missing file, so a bare !contains(marker)
            // would call a vanished parent unit "clean". Require the file to be
            // there and non-empty as well.
            String parentUnitText = ChildHomeSupport.read(
                    ChildHomeSupport.parentUnit(homeDir, ChildHomeSupport.UNIT_A).resolve("SKILL.md"));
            boolean parentUnitClean = !parentUnitText.isEmpty() && !parentUnitText.contains(marker);
            String parentLinkTargetText = ChildHomeSupport.read(
                    ChildHomeSupport.parentUnit(homeDir, ChildHomeSupport.LINKED_SOURCE)
                            .resolve("NOTE.md"));
            boolean parentLinkTargetClean = !parentLinkTargetText.isEmpty()
                    && !parentLinkTargetText.contains(marker);

            List<String> storeLinks = new ArrayList<>();
            for (String unit : UNITS) {
                for (String link : ChildHomeSupport.storeLinksBelow(
                        ChildHomeSupport.childUnit(projectDir, unit), parentSkills)) {
                    storeLinks.add(unit + "/" + link);
                }
            }
            boolean noStoreLinksInChildUnits = storeLinks.isEmpty();

            boolean linkedFileIsRealContent = ChildHomeSupport.isRealFile(
                    ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_D)
                            .resolve("linked/NOTE.md"))
                    && ChildHomeSupport.isRealDirectory(
                            ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_D)
                                    .resolve("linked"));

            boolean pass = parentStoreUnchanged && parentUnitClean && parentLinkTargetClean
                    && noStoreLinksInChildUnits && linkedFileIsRealContent;
            return (pass
                    ? NodeResult.pass("child.home.edit.stays.in.child.home")
                    : NodeResult.fail("child.home.edit.stays.in.child.home",
                            "parentStoreUnchanged=" + parentStoreUnchanged
                                    + " before=" + digestBefore + " after=" + digestAfter
                                    + " parentUnitClean=" + parentUnitClean
                                    + " parentLinkTargetClean=" + parentLinkTargetClean
                                    + " storeLinksInChildUnits=" + storeLinks
                                    + " linkedFileIsRealContent=" + linkedFileIsRealContent))
                    .assertion("parent_store_tree_is_byte_identical_after_child_edit", parentStoreUnchanged)
                    .assertion("edited_child_unit_bytes_did_not_reach_the_parent_unit", parentUnitClean)
                    .assertion("edit_behind_the_link_did_not_reach_the_linked_parent_unit",
                            parentLinkTargetClean)
                    .assertion("no_symlink_in_a_child_unit_resolves_into_the_parent_store",
                            noStoreLinksInChildUnits)
                    .assertion("in_unit_store_link_is_dereferenced_content", linkedFileIsRealContent);
        });
    }
}
