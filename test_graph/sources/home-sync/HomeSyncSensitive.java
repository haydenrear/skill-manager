///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Proves this graph can FAIL.
 *
 * <p>Every other node in {@code home-sync} asserts that something was
 * <em>not</em> destroyed. That is the weakest possible shape of assertion: a
 * check that cannot see is indistinguishable from a check that saw nothing
 * wrong, and both report green. So one mutation per defect class this graph
 * claims to detect is planted here, on a fresh pair of real homes, driven
 * through the real CLI, and each is asserted DETECTED by the same oracle the
 * corresponding node uses — {@link HomeSyncSupport#difference},
 * {@link HomeSyncSupport#wroteNothingBut}, {@link HomeSyncSupport#stagingLeftovers}.
 *
 * <p>Unmutated controls are asserted clean in the same run. Without them an
 * oracle that reported "violation" unconditionally would kill every mutant
 * below and mean nothing — the same reason a kill-test harness runs the
 * unmutated corpus first.
 *
 * <h2>The mutation is applied to the outcome, not to the production code</h2>
 *
 * <p>Each scenario runs the real reconcile, and then does to the destination
 * what the corresponding bug would have done: overwrite the edit that was just
 * held back, resolve the conflict that was just reported, write into the home a
 * dry run just declined to touch, leave half a swap behind, delete the unit the
 * source no longer has. If the oracle cannot tell that state from the correct
 * one, the node that relies on it is asserting nothing, and this node says so
 * before the green suite gets believed.
 *
 * <h2>A fresh pair of homes per mutation</h2>
 *
 * <p>Not tidiness. Mutations that share a fixture have to be reverted, and a
 * revert competes with the measurement — "did the revert work" becomes an
 * assertion about the harness rather than about the oracle. Isolating by
 * construction removes the question.
 */
public class HomeSyncSensitive {

    private static final String UNIT = "s-unit";
    private static final String ORPHAN = "s-orphan";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.sensitive")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("env.prepared")
            .tags("home-sync", "mutation", "self-test")
            .timeout("420s");

    /** A change planted into a destination home after a real reconcile. */
    private interface Mutation {
        void apply(Path source, Path dest) throws Exception;
    }

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String sandbox = ctx.get("env.prepared", "home").orElse(null);
            if (sandbox == null) {
                return NodeResult.fail("home.sync.sensitive", "missing env.prepared.home");
            }
            Path base = Path.of(sandbox, "home-sync-sensitive");
            Files.createDirectories(base);

            List<String> failures = new ArrayList<>();
            boolean controlHoldBackClean;
            boolean controlDryRunClean;
            boolean controlNoLeftovers;
            boolean overwrittenEditDetected;
            boolean resolvedConflictDetected;
            boolean dryRunWriteDetected;
            boolean partialSyncUnitDetected;
            boolean partialSyncStagingDetected;
            boolean deletedOrphanDetected;
            try {
                // --- control: the correct outcome, measured by every oracle ---
                Probe control = holdBack(ctx, base, "control", null);
                controlHoldBackClean = control.unitViolations().isEmpty();
                controlNoLeftovers = control.leftovers().isEmpty();

                Probe dryControl = dryRun(ctx, base, "dry-control", null);
                controlDryRunClean = dryControl.homeViolations().isEmpty();

                // --- M1: the held-back edit is silently overwritten ----------
                overwrittenEditDetected = !holdBack(ctx, base, "m1-overwrite",
                        (source, dest) -> Files.copy(
                                HomeSyncSupport.unitDir(source, UNIT).resolve("SKILL.md"),
                                HomeSyncSupport.unitDir(dest, UNIT).resolve("SKILL.md"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING))
                        .unitViolations().isEmpty();

                // --- M2: the reported conflict is silently resolved ----------
                resolvedConflictDetected = !conflict(ctx, base, "m2-resolve",
                        (source, dest) -> Files.copy(
                                HomeSyncSupport.unitDir(source, UNIT).resolve("shared.md"),
                                HomeSyncSupport.unitDir(dest, UNIT).resolve("shared.md"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING))
                        .unitViolations().isEmpty();

                // --- M3: the dry run writes -----------------------------------
                dryRunWriteDetected = !dryRun(ctx, base, "m3-dry-write",
                        (source, dest) -> HomeSyncSupport.write(
                                dest.resolve("skills/planted/SKILL.md"), "planted by a dry run\n"))
                        .homeViolations().isEmpty();

                // --- M4: half of a swap is left behind ------------------------
                Probe partial = holdBack(ctx, base, "m4-partial", (source, dest) -> {
                    Files.delete(HomeSyncSupport.unitDir(dest, UNIT).resolve("references/page.md"));
                    HomeSyncSupport.write(
                            dest.resolve(".materialization/tmp/replaced-abc123/SKILL.md"),
                            "a displaced tree an interrupted swap left behind\n");
                });
                partialSyncUnitDetected = !partial.unitViolations().isEmpty();
                partialSyncStagingDetected = !partial.leftovers().isEmpty();

                // --- M5: a unit the source no longer has is deleted -----------
                deletedOrphanDetected = !orphan(ctx, base, "m5-delete",
                        (source, dest) -> HomeSyncSupport
                                .deleteTree(HomeSyncSupport.unitDir(dest, ORPHAN)))
                        .unitViolations().isEmpty();
            } catch (Exception e) {
                return NodeResult.error("home.sync.sensitive", e);
            }

            if (!controlHoldBackClean) failures.add("control hold-back was not clean");
            if (!controlDryRunClean) failures.add("control dry run was not clean");
            if (!controlNoLeftovers) failures.add("control run left staging behind");
            if (!overwrittenEditDetected) failures.add("M1 silent overwrite not detected");
            if (!resolvedConflictDetected) failures.add("M2 resolved conflict not detected");
            if (!dryRunWriteDetected) failures.add("M3 dry-run write not detected");
            if (!partialSyncUnitDetected) failures.add("M4 partial unit not detected");
            if (!partialSyncStagingDetected) failures.add("M4 staging residue not detected");
            if (!deletedOrphanDetected) failures.add("M5 deleted orphan not detected");

            boolean pass = failures.isEmpty();
            return (pass
                    ? NodeResult.pass("home.sync.sensitive")
                    : NodeResult.fail("home.sync.sensitive", String.join("; ", failures)))
                    .assertion("an_unmutated_hold_back_reports_clean", controlHoldBackClean)
                    .assertion("an_unmutated_dry_run_reports_clean", controlDryRunClean)
                    .assertion("an_unmutated_run_leaves_no_staging_residue", controlNoLeftovers)
                    .assertion("an_overwritten_held_back_edit_is_detected", overwrittenEditDetected)
                    .assertion("a_silently_resolved_conflict_is_detected", resolvedConflictDetected)
                    .assertion("a_dry_run_that_writes_is_detected", dryRunWriteDetected)
                    .assertion("a_partially_written_unit_is_detected", partialSyncUnitDetected)
                    .assertion("a_staging_tree_left_behind_is_detected", partialSyncStagingDetected)
                    .assertion("a_deleted_removed_upstream_unit_is_detected", deletedOrphanDetected)
                    .metric("mutationsPlanted", 5)
                    .metric("controlsAsserted", 3);
        });
    }

    /** What each oracle saw across one scenario. */
    private record Probe(List<String> unitViolations, List<String> homeViolations,
                         List<String> leftovers) {}

    /**
     * A fresh source/destination pair, already reconciled once.
     *
     * <p>The seed goes through {@link HomeSyncSupport#setup} — issue #135. This
     * node's whole claim is that the oracles report a planted defect, and a
     * seed that refused would leave every scenario measuring an empty
     * destination: the unmutated controls would still read clean, so the node
     * would look green-adjacent while measuring nothing.
     */
    private static Path[] pair(NodeContext ctx, Path base, String name) throws Exception {
        Path source = base.resolve(name).resolve("source");
        Path dest = base.resolve(name).resolve("dest");
        HomeSyncSupport.mkUnit(source, UNIT, "unit v1");
        HomeSyncSupport.write(HomeSyncSupport.unitDir(source, UNIT).resolve("shared.md"),
                "shared v1\n");
        HomeSyncSupport.write(HomeSyncSupport.unitDir(source, UNIT).resolve("references/page.md"),
                "page v1\n");
        HomeSyncSupport.setup(ctx, name + "-seed", source.toString(), "home", "sync",
                "--from", source.toString(), "--to", dest.toString(), "--json");
        return new Path[] {source, dest};
    }

    /**
     * A destination edit that a plain sync must hold back. The mutation, if any,
     * is applied after the real pass and measured against the state the pass
     * was handed.
     */
    private static Probe holdBack(NodeContext ctx, Path base, String name, Mutation mutation)
            throws Exception {
        Path[] homes = pair(ctx, base, name);
        Path source = homes[0];
        Path dest = homes[1];
        HomeSyncSupport.append(HomeSyncSupport.unitDir(dest, UNIT).resolve("SKILL.md"),
                "the destination's own work\n");
        HomeSyncSupport.append(HomeSyncSupport.unitDir(source, UNIT).resolve("shared.md"),
                "the source moved on\n");
        LinkedHashMap<String, String> before =
                HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, UNIT));
        HomeSyncSupport.sm(ctx, name + "-sync", source.toString(), "home", "sync",
                "--from", source.toString(), "--to", dest.toString(), "--json");
        if (mutation != null) mutation.apply(source, dest);
        return new Probe(
                HomeSyncSupport.difference(before,
                        HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, UNIT))),
                List.of(),
                HomeSyncSupport.stagingLeftovers(dest));
    }

    /** Both sides move the same file, so {@code --merge} must report and not write. */
    private static Probe conflict(NodeContext ctx, Path base, String name, Mutation mutation)
            throws Exception {
        Path[] homes = pair(ctx, base, name);
        Path source = homes[0];
        Path dest = homes[1];
        HomeSyncSupport.append(HomeSyncSupport.unitDir(source, UNIT).resolve("shared.md"),
                "SOURCE SIDE\n");
        HomeSyncSupport.append(HomeSyncSupport.unitDir(dest, UNIT).resolve("shared.md"),
                "DESTINATION SIDE\n");
        LinkedHashMap<String, String> before =
                HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, UNIT));
        HomeSyncSupport.sm(ctx, name + "-sync", source.toString(), "home", "sync",
                "--from", source.toString(), "--to", dest.toString(), "--merge", "--json");
        if (mutation != null) mutation.apply(source, dest);
        return new Probe(
                HomeSyncSupport.difference(before,
                        HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, UNIT))),
                List.of(),
                HomeSyncSupport.stagingLeftovers(dest));
    }

    /** A dry run over a home that has real work to report, measured whole-home. */
    private static Probe dryRun(NodeContext ctx, Path base, String name, Mutation mutation)
            throws Exception {
        Path[] homes = pair(ctx, base, name);
        Path source = homes[0];
        Path dest = homes[1];
        HomeSyncSupport.append(HomeSyncSupport.unitDir(source, UNIT).resolve("SKILL.md"),
                "the source moved on\n");
        LinkedHashMap<String, String> before = HomeSyncSupport.entryDigests(dest);
        HomeSyncSupport.sm(ctx, name + "-sync", source.toString(), "home", "sync",
                "--from", source.toString(), "--to", dest.toString(), "--dry-run", "--json");
        if (mutation != null) mutation.apply(source, dest);
        return new Probe(List.of(),
                HomeSyncSupport.wroteNothingBut(before, HomeSyncSupport.entryDigests(dest),
                        Set.of(".materialization", ".materialization/.home.lock")),
                HomeSyncSupport.stagingLeftovers(dest));
    }

    /** A unit the destination has and the source does not: reported, never deleted. */
    private static Probe orphan(NodeContext ctx, Path base, String name, Mutation mutation)
            throws Exception {
        Path[] homes = pair(ctx, base, name);
        Path source = homes[0];
        Path dest = homes[1];
        HomeSyncSupport.mkUnit(dest, ORPHAN, "orphan v1 — the source never had this");
        LinkedHashMap<String, String> before =
                HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, ORPHAN));
        HomeSyncSupport.sm(ctx, name + "-sync", source.toString(), "home", "sync",
                "--from", source.toString(), "--to", dest.toString(), "--json");
        if (mutation != null) mutation.apply(source, dest);
        return new Probe(
                HomeSyncSupport.difference(before,
                        HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, ORPHAN))),
                List.of(),
                HomeSyncSupport.stagingLeftovers(dest));
    }
}
