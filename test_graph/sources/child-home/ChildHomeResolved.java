///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives {@code skill-manager project resolve} twice against the fixture
 * project, injecting a symlink into the parent store between the two passes.
 *
 * <p>The injected link models Core.tla's {@code StoreLinkEdges}: a symlink
 * INSIDE a parent-store unit whose target is another parent-store unit. Under
 * COPY it must be dereferenced into real content in the child home, which is
 * both an isolation requirement (nothing in the child home may write through
 * into the store) and a freshness requirement (the child must still converge
 * when the linked unit changes).
 *
 * <p>It can only be injected after the first resolve, because the parent-store
 * unit directories do not exist until the project installs them.
 */
public class ChildHomeResolved {
    static final NodeSpec SPEC = NodeSpec.of("child.home.resolved")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("child.home.project.fixture")
            .tags("child-home", "project", "resolve")
            .timeout("300s")
            .output("projectDir", "string")
            .output("parentSkillsDigest", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.project.fixture", "projectDir").orElse(null);
            if (home == null || projectDirRaw == null) {
                return NodeResult.fail("child.home.resolved", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);

            ProcessRecord first = ChildHomeSupport.sm(ctx, "resolve-initial", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String firstJson = ChildHomeSupport.jsonSummary(ChildHomeSupport.log(ctx, "resolve-initial"));

            // Inject the in-unit store link into two parent-store units. Verified
            // by reading the link back, not by "no exception was raised": the
            // whole point of the two later dereference assertions is that this
            // link was really there to be dereferenced.
            Path linkedSource = ChildHomeSupport.parentUnit(homeDir, ChildHomeSupport.LINKED_SOURCE);
            boolean linksInjected = true;
            for (String unit : new String[] { ChildHomeSupport.UNIT_B, ChildHomeSupport.UNIT_D }) {
                Path link = ChildHomeSupport.parentUnit(homeDir, unit).resolve("linked");
                try {
                    if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(link, linkedSource);
                    }
                } catch (Exception e) {
                    // Fall through to the read-back below, which is what decides.
                }
                linksInjected = linksInjected
                        && Files.isSymbolicLink(link)
                        && Files.readSymbolicLink(link).equals(linkedSource);
            }

            ProcessRecord second = ChildHomeSupport.sm(ctx, "resolve-with-store-link", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());
            String secondJson = ChildHomeSupport.jsonSummary(ChildHomeSupport.log(ctx, "resolve-with-store-link"));

            boolean bothOk = first.exitCode() == 0 && second.exitCode() == 0;
            // heldBackUnits returns an empty set for input it could not parse,
            // so "held back nothing" is only meaningful once the summary is
            // known to be there. Asserted separately rather than folded in, so a
            // JSON-shape change reports as a parse failure and not as a clean
            // child home.
            boolean summariesParsed = firstJson.contains("\"heldBack\":")
                    && secondJson.contains("\"heldBack\":");
            boolean nothingHeldBackYet = summariesParsed
                    && ChildHomeSupport.heldBackUnits(firstJson).isEmpty()
                    && ChildHomeSupport.heldBackUnits(secondJson).isEmpty();
            boolean allUnitsPresent = ChildHomeSupport.childUnitNames(projectDir)
                    .containsAll(java.util.List.of(ChildHomeSupport.UNIT_A, ChildHomeSupport.UNIT_B,
                            ChildHomeSupport.UNIT_C, ChildHomeSupport.UNIT_D));
            // NOFOLLOW, not just readable: Files.readString would report the
            // parent store's bytes through a preserved store link just as
            // happily, which is exactly the blindness this graph exists to fix.
            Path linkedNote = ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_B)
                    .resolve("linked/NOTE.md");
            boolean linkedContentMaterialized = ChildHomeSupport.isRealFile(linkedNote)
                    && ChildHomeSupport.read(linkedNote).contains("linked rev1");

            String parentDigest = ChildHomeSupport.treeDigest(homeDir.resolve("skills"));

            boolean pass = bothOk && linksInjected && summariesParsed && nothingHeldBackYet
                    && allUnitsPresent && linkedContentMaterialized;
            return (pass
                    ? NodeResult.pass("child.home.resolved")
                    : NodeResult.fail("child.home.resolved",
                            "first=" + first.exitCode() + " second=" + second.exitCode()
                                    + " linksInjected=" + linksInjected
                                    + " summariesParsed=" + summariesParsed
                                    + " nothingHeldBackYet=" + nothingHeldBackYet
                                    + " allUnitsPresent=" + allUnitsPresent
                                    + " linkedContentMaterialized=" + linkedContentMaterialized
                                    + " childUnits=" + ChildHomeSupport.childUnitNames(projectDir)))
                    .process(first)
                    .process(second)
                    .assertion("initial_resolve_ok", first.exitCode() == 0)
                    .assertion("resolve_after_store_link_injection_ok", second.exitCode() == 0)
                    .assertion("store_link_injected_into_parent_units", linksInjected)
                    .assertion("resolve_json_summary_carries_a_held_back_field", summariesParsed)
                    .assertion("clean_child_home_holds_back_nothing", nothingHeldBackYet)
                    .assertion("all_claimed_units_materialized", allUnitsPresent)
                    .assertion("in_unit_store_link_materialized_as_content", linkedContentMaterialized)
                    .metric("initialResolveExitCode", first.exitCode())
                    .metric("secondResolveExitCode", second.exitCode())
                    .publish("projectDir", projectDir.toString())
                    .publish("parentSkillsDigest", parentDigest);
        });
    }
}
