///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeCloneSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code SourceHomeIsByteIdenticalToItsCloneTimeSelf}, the half that matters:
 * USING the clone is also a read of the source.
 *
 * <p>Three kinds of write go through the clone, chosen because each reaches the
 * filesystem by a different route:
 *
 * <ol>
 *   <li><b>An ordinary editor write</b> into {@code skills/hc-unit-b/SKILL.md}.
 *       No skill-manager code participates, so nothing can guard it: if the copy
 *       held an absolute in-home symlink, or if the unit directory were a link
 *       into the source, this write would land in the source and the only thing
 *       that catches it is comparing the source's bytes.</li>
 *   <li><b>{@code unbind}</b>, which deletes the tree its projection ledger
 *       recorded as {@code destPath}. This is the reproduced defect on #20: a
 *       {@code destPath} that names a foreign home makes unbinding in the copy
 *       delete that home's tree, and the command still reports success. There is
 *       no report to check here — only what is still on disk.</li>
 *   <li><b>{@code bind}</b>, which creates an agent symlink pointing at the
 *       ledger's {@code sourcePath}. If the clone's ledger still named the
 *       source home, this produces a live channel from the agent's skill
 *       directory into the source, and every later agent edit writes there.
 *       Asserted by reading the created link's REAL path.</li>
 * </ol>
 *
 * <p>The assertion is byte-identity of the source, computed independently, not
 * an exit code and not a report. That is the difference between this node and a
 * green suite hiding a data-loss bug.
 */
public class HomeCloneEditStaysInClone {
    static final NodeSpec SPEC = NodeSpec.of("home.clone.edit.stays.in.clone")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.cloned.into.project")
            .tags("home-clone", "isolation")
            .timeout("300s")
            .output("agentBytes", "string");

    private static final String AGENT_BYTES = "AGENT EDIT THROUGH THE CLONE";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String fixture = ctx.get("home.clone.fixture.built", "fixtureHome").orElse(null);
            String cloneStoreRaw = ctx.get("home.clone.fixture.built", "cloneStore").orElse(null);
            String sourceDigest = ctx.get("home.clone.fixture.built", "sourceDigest").orElse(null);
            if (fixture == null || cloneStoreRaw == null || sourceDigest == null) {
                return NodeResult.fail("home.clone.edit.stays.in.clone", "missing upstream context");
            }
            Path fixtureHome = Path.of(fixture);
            Path cloneStore = Path.of(cloneStoreRaw);

            // --- 1. an ordinary editor write through the clone --------------
            Path clonedSkill = HomeCloneSupport.unitDir(cloneStore, HomeCloneSupport.UNIT_B)
                    .resolve("SKILL.md");
            Path sourceSkill = HomeCloneSupport.unitDir(fixtureHome, HomeCloneSupport.UNIT_B)
                    .resolve("SKILL.md");
            String sourceSkillBefore = HomeCloneSupport.read(sourceSkill);
            Files.writeString(clonedSkill,
                    HomeCloneSupport.skillBody(HomeCloneSupport.UNIT_B, "rev1") + AGENT_BYTES + "\n");

            boolean editLandedInTheClone = HomeCloneSupport.read(clonedSkill).contains(AGENT_BYTES);
            boolean sourceSkillUnchanged = HomeCloneSupport.read(sourceSkill).equals(sourceSkillBefore)
                    && !HomeCloneSupport.read(sourceSkill).contains(AGENT_BYTES);

            // --- 1b. a write THROUGH the clone's in-unit symlink ------------
            // The fixture's link was absolute into the source home; the clone
            // must have rewritten it relative. If it did not, this write is
            // delivered by the KERNEL into the source home's unit, with no
            // skill-manager code anywhere in the path and nothing able to guard
            // it. This is the one write in the graph that a preserved absolute
            // link turns into a source mutation, which makes it the mutation
            // handle for the byte-identity assertion below.
            Path throughLink = HomeCloneSupport.unitDir(cloneStore, HomeCloneSupport.UNIT_A)
                    .resolve(HomeCloneSupport.IN_UNIT_LINK).resolve("AGENT_THROUGH_LINK.md");
            Files.writeString(throughLink, AGENT_BYTES + " (through the in-unit link)\n");
            Path clonedLinkTarget = HomeCloneSupport.unitDir(cloneStore, HomeCloneSupport.LINKED)
                    .resolve("AGENT_THROUGH_LINK.md");
            Path sourceLinkTarget = HomeCloneSupport.unitDir(fixtureHome, HomeCloneSupport.LINKED)
                    .resolve("AGENT_THROUGH_LINK.md");
            boolean writeThroughTheLinkLandedInTheClone =
                    Files.isRegularFile(clonedLinkTarget)
                            && !Files.exists(sourceLinkTarget, LinkOption.NOFOLLOW_LINKS);

            // --- 2. unbind through the clone -------------------------------
            ProcessRecord list = HomeCloneSupport.sm(ctx, "bindings-list", cloneStoreRaw,
                    "bindings", "list", "--unit", HomeCloneSupport.UNIT_A, "--json");
            // `bindings list --json` pretty-prints, so the object spans lines:
            // scan the whole capture rather than looking for a one-line summary.
            String listJson = HomeCloneSupport.log(ctx, "bindings-list");
            String bindingId = HomeCloneSupport.jsonString(listJson, "id");
            String bindingTarget = HomeCloneSupport.jsonString(listJson, "target");
            boolean cloneSeesItsBindings = list.exitCode() == 0 && !bindingId.isBlank();

            ProcessRecord unbind = bindingId.isBlank()
                    ? null
                    : HomeCloneSupport.sm(ctx, "unbind", cloneStoreRaw, "unbind", bindingId);
            boolean unbindSucceeded = unbind != null && unbind.exitCode() == 0;

            // The unit must still be in BOTH stores. `unbind` removes a
            // projection, never an installed unit; the pre-epic defect removed
            // the unit and reported success.
            Path sourceUnitA = HomeCloneSupport.unitDir(fixtureHome, HomeCloneSupport.UNIT_A);
            Path cloneUnitA = HomeCloneSupport.unitDir(cloneStore, HomeCloneSupport.UNIT_A);
            boolean unbindDeletedNoInstalledUnit =
                    Files.isDirectory(sourceUnitA, LinkOption.NOFOLLOW_LINKS)
                            && Files.isRegularFile(sourceUnitA.resolve("SKILL.md"))
                            && Files.isDirectory(cloneUnitA, LinkOption.NOFOLLOW_LINKS)
                            && Files.isRegularFile(cloneUnitA.resolve("SKILL.md"));

            // --- 3. bind through the clone ---------------------------------
            // The re-created agent symlink must resolve into the CLONE. If the
            // clone's ledger still named the source, this is where the agent
            // gets a live channel into it.
            ProcessRecord bind = bindingTarget.isBlank()
                    ? null
                    : HomeCloneSupport.sm(ctx, "bind", cloneStoreRaw,
                            "bind", HomeCloneSupport.UNIT_A, "--to", bindingTarget);
            boolean bindSucceeded = bind != null && bind.exitCode() == 0;
            Path agentLink = bindingTarget.isBlank()
                    ? null
                    : Path.of(bindingTarget).resolve(HomeCloneSupport.UNIT_A);
            boolean agentLinkResolvesIntoTheClone = agentLink != null
                    && realPathStartsWith(agentLink, cloneStore);
            boolean agentLinkDoesNotResolveIntoTheSource = agentLink != null
                    && !realPathStartsWith(agentLink, fixtureHome);

            // --- the acceptance criterion ----------------------------------
            String afterDigest = HomeCloneSupport.treeDigest(fixtureHome);
            boolean sourceHomeIsByteIdentical = afterDigest.equals(sourceDigest);

            boolean pass = editLandedInTheClone && sourceSkillUnchanged
                    && writeThroughTheLinkLandedInTheClone && cloneSeesItsBindings
                    && unbindSucceeded && unbindDeletedNoInstalledUnit && bindSucceeded
                    && agentLinkResolvesIntoTheClone && agentLinkDoesNotResolveIntoTheSource
                    && sourceHomeIsByteIdentical;

            NodeResult result = (pass
                    ? NodeResult.pass("home.clone.edit.stays.in.clone")
                    : NodeResult.fail("home.clone.edit.stays.in.clone",
                            "editLandedInTheClone=" + editLandedInTheClone
                                    + " sourceSkillUnchanged=" + sourceSkillUnchanged
                                    + " writeThroughTheLinkLandedInTheClone="
                                    + writeThroughTheLinkLandedInTheClone
                                    + " bindingId=" + bindingId
                                    + " bindingTarget=" + bindingTarget
                                    + " unbindSucceeded=" + unbindSucceeded
                                    + " unbindDeletedNoInstalledUnit=" + unbindDeletedNoInstalledUnit
                                    + " bindSucceeded=" + bindSucceeded
                                    + " agentLink=" + (agentLink == null ? "-" : agentLink)
                                    + " intoClone=" + agentLinkResolvesIntoTheClone
                                    + " intoSource=" + !agentLinkDoesNotResolveIntoTheSource
                                    + " digestBefore=" + sourceDigest
                                    + " digestAfter=" + afterDigest
                                    + " changed=" + changedEntries(fixtureHome)))
                    .process(list)
                    .assertion("an_edit_through_the_clone_lands_in_the_clone", editLandedInTheClone)
                    .assertion("the_source_units_own_bytes_are_unchanged", sourceSkillUnchanged)
                    .assertion("a_write_through_the_clones_in_unit_symlink_lands_in_the_clone",
                            writeThroughTheLinkLandedInTheClone)
                    .assertion("the_clone_can_read_its_own_binding_ledger", cloneSeesItsBindings)
                    .assertion("unbind_through_the_clone_succeeds", unbindSucceeded)
                    .assertion("unbind_deleted_no_installed_unit_in_either_home",
                            unbindDeletedNoInstalledUnit)
                    .assertion("bind_through_the_clone_succeeds", bindSucceeded)
                    .assertion("the_rebound_agent_symlink_resolves_into_the_clone",
                            agentLinkResolvesIntoTheClone)
                    .assertion("the_rebound_agent_symlink_does_not_resolve_into_the_source",
                            agentLinkDoesNotResolveIntoTheSource)
                    .assertion("the_source_home_is_byte_identical_after_writing_through_the_clone",
                            sourceHomeIsByteIdentical)
                    .publish("agentBytes", AGENT_BYTES);
            if (unbind != null) result = result.process(unbind);
            if (bind != null) result = result.process(bind);
            return result;
        });
    }

    private static boolean realPathStartsWith(Path path, Path root) {
        try {
            return path.toRealPath().startsWith(root.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A short human-readable hint when the digest moved. A bare
     * "digest changed" failure would send the next reader back to a 5-minute
     * bisect; this names the entries so the leak is identifiable from the report.
     */
    private static String changedEntries(Path root) {
        try {
            List<String> inv = HomeCloneSupport.inventory(root);
            return inv.size() + " entries";
        } catch (Exception e) {
            return "unavailable: " + e.getMessage();
        }
    }
}
