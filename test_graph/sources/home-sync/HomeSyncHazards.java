///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The two ways a reconcile can go wrong that are not about which unit moved:
 * one pass that fails half way through, and two passes that arrive at once.
 *
 * <h2>A failed sync must be a no-op, not a partial one</h2>
 *
 * <p>The failure is injected rather than simulated: the destination's
 * {@code .materialization/} directory — where staging is built — is made
 * read-only, so the write fails at exactly the point a real disk-full or
 * permission failure would, after the pass has decided to write and before the
 * live unit may be touched. A test that instead deleted a file and called that
 * "interrupted" would be asserting about its own edit.
 *
 * <p>The injection is itself checked ({@code the_failure_injection_took_effect}):
 * run as root, the {@code chmod} does nothing, every assertion below passes
 * vacuously, and the node would report a guarantee it never exercised.
 *
 * <h2>Two syncs into one home must not interleave</h2>
 *
 * <p>Per-unit staging makes a single unit's swap atomic; only a home-wide lock
 * makes the <em>home</em> coherent when two worktrees close out at once. The
 * corruption that lock prevents is not a torn file — it is a home that is half
 * reconciled against one source and half against another, with materialization
 * records describing a state that never existed on disk.
 *
 * <p>So the check is not "both commands exited 0". It is that the destination
 * afterwards is exactly one source's tree, and — the part that catches a torn
 * record specifically — that re-running the winner's sync reports
 * {@code unchanged}. A record that described a tree which was never written
 * would make the winner report {@code held-back} instead, because the bytes on
 * disk would not match the baseline the record claims for them.
 */
public class HomeSyncHazards {

    private static final String UNIT = "h-unit";

    static final NodeSpec SPEC = NodeSpec.of("home.sync.hazards")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "atomicity", "concurrency", "lock")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            if (workspaceRaw == null) {
                return NodeResult.fail("home.sync.hazards", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("hazards");
            Files.createDirectories(base);

            // ------------------ a sync that fails part way -------------------
            Path source = base.resolve("source");
            Path dest = base.resolve("dest");
            HomeSyncSupport.mkUnit(source, UNIT, "unit v1");
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, UNIT).resolve("keep.md"),
                    "keep me\n");
            ProcessRecord seed = HomeSyncSupport.sm(ctx, "hazard-seed", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(), "--json");

            HomeSyncSupport.append(HomeSyncSupport.unitDir(source, UNIT).resolve("SKILL.md"),
                    "unit v2 — the refresh that will be interrupted\n");
            Path destUnit = HomeSyncSupport.unitDir(dest, UNIT);
            LinkedHashMap<String, String> unitBefore = HomeSyncSupport.entryDigests(destUnit);

            Path records = dest.resolve(".materialization");
            Set<PosixFilePermission> original = Files.getPosixFilePermissions(records);
            Files.setPosixFilePermissions(records, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            boolean injectionTookEffect = !Files.isWritable(records);

            ProcessRecord interrupted = HomeSyncSupport.sm(ctx, "hazard-interrupted",
                    source.toString(), "home", "sync", "--from", source.toString(),
                    "--to", dest.toString(), "--json");
            List<String> unitMoved = HomeSyncSupport.difference(unitBefore,
                    HomeSyncSupport.entryDigests(destUnit));
            boolean noHalfWrittenSibling = !Files.exists(
                    destUnit.resolveSibling(destUnit.getFileName() + ".tmp"),
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(records, original);

            boolean interruptedFailed = interrupted.exitCode() != 0;
            boolean destinationUnitIntact = unitMoved.isEmpty()
                    && Files.isDirectory(destUnit, LinkOption.NOFOLLOW_LINKS);

            ProcessRecord recovered = HomeSyncSupport.sm(ctx, "hazard-recovered",
                    source.toString(), "home", "sync", "--from", source.toString(),
                    "--to", dest.toString(), "--json");
            boolean recoveredCleanly = recovered.exitCode() == 0
                    && HomeSyncSupport.status(HomeSyncSupport.json(ctx, "hazard-recovered"),
                            "skill:" + UNIT).equals("updated")
                    && HomeSyncSupport.difference(
                            HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(source, UNIT)),
                            HomeSyncSupport.entryDigests(destUnit)).isEmpty();
            boolean noStagingLeftovers = HomeSyncSupport.stagingLeftovers(dest).isEmpty();

            // ------------------ two syncs into one home ----------------------
            Path left = base.resolve("left");
            Path right = base.resolve("right");
            Path contended = base.resolve("contended");
            HomeSyncSupport.mkUnit(left, UNIT, "left");
            HomeSyncSupport.mkUnit(right, UNIT, "right");
            // Enough files that a copy takes long enough for two unsynchronised
            // passes to land inside each other.
            for (int i = 0; i < 40; i++) {
                HomeSyncSupport.write(HomeSyncSupport.unitDir(left, UNIT).resolve("f" + i + ".md"),
                        ("LEFT " + i + "\n").repeat(64));
                HomeSyncSupport.write(HomeSyncSupport.unitDir(right, UNIT).resolve("f" + i + ".md"),
                        ("RIGHT " + i + "\n").repeat(64));
            }
            String leftDigest = HomeSyncSupport.treeDigest(HomeSyncSupport.unitDir(left, UNIT));
            String rightDigest = HomeSyncSupport.treeDigest(HomeSyncSupport.unitDir(right, UNIT));

            List<ProcessRecord> racers = new CopyOnWriteArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (Path racer : List.of(left, right)) {
                String label = "hazard-race-" + racer.getFileName();
                Thread thread = new Thread(() -> racers.add(
                        HomeSyncSupport.sm(ctx, label, racer.toString(), "home", "sync",
                                "--from", racer.toString(), "--to", contended.toString(), "--json")));
                threads.add(thread);
            }
            for (Thread thread : threads) thread.start();
            for (Thread thread : threads) thread.join();

            boolean bothRacersCompleted = racers.size() == 2
                    && racers.stream().allMatch(record -> record.exitCode() == 0);
            String contendedDigest =
                    HomeSyncSupport.treeDigest(HomeSyncSupport.unitDir(contended, UNIT));
            boolean exactlyOneSourcesTree = contendedDigest.equals(leftDigest)
                    || contendedDigest.equals(rightDigest);

            // The record and the bytes have to agree: whichever source won, its
            // sync must now find nothing to do.
            Path winner = contendedDigest.equals(leftDigest) ? left : right;
            ProcessRecord settled = HomeSyncSupport.sm(ctx, "hazard-race-settled",
                    winner.toString(), "home", "sync", "--from", winner.toString(),
                    "--to", contended.toString(), "--dry-run", "--json");
            Map<String, Object> settledReport = HomeSyncSupport.json(ctx, "hazard-race-settled");
            boolean recordMatchesTheTree = settled.exitCode() == 0
                    && HomeSyncSupport.status(settledReport, "skill:" + UNIT).equals("unchanged");
            boolean contendedHasNoLeftovers =
                    HomeSyncSupport.stagingLeftovers(contended).isEmpty();

            boolean pass = seed.exitCode() == 0 && injectionTookEffect && interruptedFailed
                    && destinationUnitIntact && noHalfWrittenSibling && recoveredCleanly
                    && noStagingLeftovers && bothRacersCompleted && exactlyOneSourcesTree
                    && recordMatchesTheTree && contendedHasNoLeftovers;
            return (pass
                    ? NodeResult.pass("home.sync.hazards")
                    : NodeResult.fail("home.sync.hazards",
                            "seedExit=" + seed.exitCode()
                                    + " injectionTookEffect=" + injectionTookEffect
                                    + " interruptedExit=" + interrupted.exitCode()
                                    + " unitMoved=" + unitMoved
                                    + " recoveredCleanly=" + recoveredCleanly
                                    + " racers=" + racers.size()
                                    + " exactlyOneSourcesTree=" + exactlyOneSourcesTree
                                    + " recordMatchesTheTree=" + recordMatchesTheTree
                                    + " raceStatus=" + HomeSyncSupport.status(settledReport,
                                            "skill:" + UNIT)))
                    .process(seed).process(interrupted).process(recovered).process(settled)
                    .assertion("the_failure_injection_took_effect", injectionTookEffect)
                    .assertion("an_interrupted_sync_fails_rather_than_half_succeeding",
                            interruptedFailed)
                    .assertion("an_interrupted_sync_leaves_the_destination_unit_exactly_as_it_was",
                            destinationUnitIntact)
                    .assertion("an_interrupted_sync_leaves_no_half_written_tree_beside_the_unit",
                            noHalfWrittenSibling)
                    .assertion("the_same_sync_succeeds_once_the_failure_is_removed", recoveredCleanly)
                    .assertion("a_recovered_home_carries_no_staging_leftovers", noStagingLeftovers)
                    .assertion("two_concurrent_syncs_into_one_home_both_complete",
                            bothRacersCompleted)
                    .assertion("a_contended_destination_holds_exactly_one_sources_tree",
                            exactlyOneSourcesTree)
                    .assertion("the_record_left_by_a_contended_run_describes_the_tree_on_disk",
                            recordMatchesTheTree)
                    .assertion("a_contended_home_carries_no_staging_leftovers",
                            contendedHasNoLeftovers)
                    .metric("interruptedExitCode", interrupted.exitCode())
                    .metric("concurrentSyncs", racers.size());
        });
    }
}
