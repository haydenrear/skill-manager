///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ChildHomeSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Internal.tla {@code InFlightMaterializationLeavesTheChildUnitIntact}.
 *
 * <p>A materialization that fails part way through must leave the previous
 * child unit exactly as it was. The parent unit is upgraded so a refresh is
 * required, and the child home's {@code .materialization} directory is made
 * non-writable.
 *
 * <p><b>Where the injected failure actually lands:</b> {@code Fs.ensureDir} in
 * {@code ChildHomeMaterializer#stage}, called from {@code copyUnit}. That is
 * AFTER both digests are computed and after the hold-back / unchanged decision,
 * but BEFORE {@code swapIn} — so this node covers the staging window, not the
 * window between {@code swapIn}'s two {@code ATOMIC_MOVE}s. A materializer that
 * removed the live unit before writing the new one (Core.tla
 * {@code DELETE_THEN_WRITE}) leaves nothing behind at this point, which is what
 * makes the assertions below sensitive.
 *
 * <p>Then the permission is restored and the same resolve is repeated: the
 * failure must have been recoverable, not a wedged child home.
 */
public class ChildHomeMaterializationAtomic {
    static final NodeSpec SPEC = NodeSpec.of("child.home.materialization.atomic")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("child.home.converges.on.source")
            .tags("child-home", "atomicity")
            .timeout("300s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String projectDirRaw = ctx.get("child.home.converges.on.source", "projectDir").orElse(null);
            if (home == null || projectDirRaw == null) {
                return NodeResult.fail("child.home.materialization.atomic", "missing upstream context");
            }
            Path homeDir = Path.of(home);
            Path projectDir = Path.of(projectDirRaw);
            Path childB = ChildHomeSupport.childUnit(projectDir, ChildHomeSupport.UNIT_B);
            Path records = ChildHomeSupport.childHome(projectDir).resolve(".materialization");

            String contentBefore = ChildHomeSupport.read(childB.resolve("SKILL.md"));
            String linkedBefore = ChildHomeSupport.read(childB.resolve("linked/NOTE.md"));
            String digestBefore = ChildHomeSupport.treeDigest(childB);

            // Force a refresh, then break the staging area stage() must create.
            String rev3 = ChildHomeSupport.skillBody(ChildHomeSupport.UNIT_B, "rev3");
            ChildHomeSupport.write(ChildHomeSupport.parentUnit(homeDir, ChildHomeSupport.UNIT_B)
                    .resolve("SKILL.md"), rev3);

            Set<PosixFilePermission> original = Files.getPosixFilePermissions(records);
            Files.setPosixFilePermissions(records, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            // Running as root would defeat the injection and turn every
            // assertion below vacuous, so say so instead of reporting coverage.
            boolean injectionEffective = !Files.isWritable(records);

            ProcessRecord failed = ChildHomeSupport.sm(ctx, "resolve-with-broken-staging", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());

            // Everything about the post-failure state must be sampled BEFORE the
            // repair below, or the recovery resolve rebuilds what we came to
            // check and every assertion here reads as intact.
            boolean unitStillPresent = ChildHomeSupport.isRealDirectory(childB);
            String contentAfter = ChildHomeSupport.read(childB.resolve("SKILL.md"));
            String linkedAfter = ChildHomeSupport.read(childB.resolve("linked/NOTE.md"));
            String digestAfter = ChildHomeSupport.treeDigest(childB);
            List<String> unitsAfterFailure = ChildHomeSupport.childUnitNames(projectDir);

            Files.setPosixFilePermissions(records, original);
            ProcessRecord recovered = ChildHomeSupport.sm(ctx, "resolve-after-repair", home,
                    "project", "resolve", "--skip-gateway", "--json",
                    "--project-dir", projectDir.toString());

            boolean materializationFailed = failed.exitCode() != 0;
            boolean unitBytesIntact = contentBefore.equals(contentAfter)
                    && !contentAfter.isEmpty()
                    && linkedBefore.equals(linkedAfter)
                    && !linkedAfter.isEmpty();
            boolean unitTreeIntact = digestBefore.equals(digestAfter);
            // A displaced tree must never be parked beside the unit: unit identity
            // comes from SKILL.md, so a sibling would load as a second copy of the
            // same unit. Scope: sampled AFTER the failed resolve, so this is a live
            // guard against a displaced tree leaked by any EARLIER pass (mutation
            // M8 showed such siblings persist and stay listed for the rest of the
            // run). It does not cover the in-flight window inside swapIn, which
            // needs a fault between the two ATOMIC_MOVEs and is not reachable
            // without a test-only hook.
            boolean noDisplacedSibling = unitsAfterFailure.stream()
                    .allMatch(name -> name.equals(ChildHomeSupport.UNIT_B)
                            || name.equals(ChildHomeSupport.UNIT_A)
                            || name.equals(ChildHomeSupport.UNIT_D));
            boolean recoveredOk = recovered.exitCode() == 0
                    && ChildHomeSupport.read(childB.resolve("SKILL.md")).equals(rev3);

            boolean pass = injectionEffective && materializationFailed && unitStillPresent
                    && unitBytesIntact && unitTreeIntact && noDisplacedSibling && recoveredOk;
            return (pass
                    ? NodeResult.pass("child.home.materialization.atomic")
                    : NodeResult.fail("child.home.materialization.atomic",
                            "injectionEffective=" + injectionEffective
                                    + " failedExit=" + failed.exitCode()
                                    + " unitStillPresent=" + unitStillPresent
                                    + " unitBytesIntact=" + unitBytesIntact
                                    + " unitTreeIntact=" + unitTreeIntact
                                    + " unitsAfterFailure=" + unitsAfterFailure
                                    + " noDisplacedSibling=" + noDisplacedSibling
                                    + " recoveredExit=" + recovered.exitCode()
                                    + " recoveredOk=" + recoveredOk))
                    .process(failed)
                    .process(recovered)
                    .assertion("failure_injection_is_effective_not_running_as_root", injectionEffective)
                    .assertion("materialization_failed_as_intended", materializationFailed)
                    .assertion("failed_materialization_left_the_child_unit_present", unitStillPresent)
                    .assertion("failed_materialization_left_the_child_unit_bytes_intact", unitBytesIntact)
                    .assertion("failed_materialization_left_the_child_unit_tree_intact", unitTreeIntact)
                    .assertion("failed_materialization_left_no_displaced_sibling_unit", noDisplacedSibling)
                    .assertion("child_home_recovers_on_the_next_resolve", recoveredOk)
                    .metric("failedResolveExitCode", failed.exitCode())
                    .metric("recoveredResolveExitCode", recovered.exitCode());
        });
    }
}
