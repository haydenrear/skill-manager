///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;

/**
 * External.tla {@code EditChildHomeUnit}: an agent edits a skill inside its own
 * project home.
 *
 * <p>Two edits, because there are two ways a write could escape into the parent
 * store:
 *
 * <ul>
 *   <li>{@code chm-unit-a/SKILL.md} — an ordinary file in the unit. Escapes if
 *       the unit is a symlink at the parent store (the pre-epic LINK layout).</li>
 *   <li>{@code chm-unit-d/linked/NOTE.md} — a file that sits behind a symlink
 *       INSIDE the parent-store unit. Escapes if that link was recreated in the
 *       child home instead of dereferenced.</li>
 * </ul>
 *
 * <p>This node publishes the exact bytes it wrote and the parent-store digest
 * taken immediately before the writes. Downstream assertions compare against
 * those bytes rather than against "some report was consistent": after an
 * overwrite the unit is no longer modified, so a held-back-set check passes
 * vacuously. Only remembering what the agent wrote catches destruction.
 */
public class ChildHomeUnitEdited {
    static final NodeSpec SPEC = NodeSpec.of("child.home.unit.edited")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("child.home.units.independent")
            .tags("child-home", "agent-edit")
            .timeout("120s")
            .output("projectDir", "string")
            .output("parentSkillsDigestBeforeEdit", "string")
            .output("editedUnitContent", "string")
            .output("editedLinkedContent", "string")
            .output("editMarker", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.resolved", "projectDir").orElse(null);
            if (home == null || projectDirRaw == null) {
                return NodeResult.fail("child.home.unit.edited", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);

            String digestBefore = ChildHomeSupport.treeDigest(homeDir.resolve("skills"));
            String marker = "AGENT-EDIT-" + java.util.UUID.randomUUID();

            // The pre-edit bytes are captured and required to be non-empty. write()
            // creates parents, so against a child home that was never materialized
            // this node would CREATE both files and then happily assert it had
            // edited them — an upstream materialization failure would arrive here
            // disguised as a successful agent edit.
            Path editedSkill = ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_A)
                    .resolve("SKILL.md");
            String skillBefore = ChildHomeSupport.read(editedSkill);
            String unitContent = skillBefore + marker + "\n";
            ChildHomeSupport.write(editedSkill, unitContent);

            Path editedLinked = ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_D)
                    .resolve("linked/NOTE.md");
            String linkedBefore = ChildHomeSupport.read(editedLinked);
            String linkedContent = marker + " through the dereferenced link\n";
            ChildHomeSupport.write(editedLinked, linkedContent);

            // `unitContent.contains(marker)` was dropped: it is true by
            // construction. The read-back is the real check.
            boolean unitEditLanded = !skillBefore.isEmpty()
                    && ChildHomeSupport.read(editedSkill).equals(unitContent);
            boolean linkedEditLanded = !linkedBefore.isEmpty()
                    && ChildHomeSupport.read(editedLinked).equals(linkedContent);

            boolean pass = unitEditLanded && linkedEditLanded;
            return (pass
                    ? NodeResult.pass("child.home.unit.edited")
                    : NodeResult.fail("child.home.unit.edited",
                            "unitEditLanded=" + unitEditLanded
                                    + " linkedEditLanded=" + linkedEditLanded
                                    + " skillBeforeEmpty=" + skillBefore.isEmpty()
                                    + " linkedBeforeEmpty=" + linkedBefore.isEmpty()))
                    .assertion("agent_edit_written_into_child_unit", unitEditLanded)
                    .assertion("agent_edit_written_behind_dereferenced_link", linkedEditLanded)
                    .publish("projectDir", projectDir.toString())
                    .publish("parentSkillsDigestBeforeEdit", digestBefore)
                    .publish("editedUnitContent", unitContent)
                    .publish("editedLinkedContent", linkedContent)
                    .publish("editMarker", marker);
        });
    }
}
