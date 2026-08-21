///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES ../lib/SmEnv.java
//SOURCES HomeIntegrity.java
//SOURCES HomeIntegritySupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * DEF-007: a {@code sync} in one home does not delete another home's toolchain.
 *
 * <h2>The measurement, and it is a DESTRUCTIVE one</h2>
 *
 * <p>2026-08-21, on the HIS-10 branch, two hand-built homes and no clone
 * involved:
 *
 * <pre>
 *   homeB/bin/cli -&gt; homeA/bin/cli   (the DIRECTORY is the link)
 *
 *   home verify homeB     -&gt; FOREIGN_HOME bin/cli      the GATE sees it
 *   sync in homeB         -&gt; "pruned bin/cli/alpha … not this home's parent store"
 *                            "pruned bin/cli/beta  … not this home's parent store"
 *   homeA/bin/cli         -&gt; 2 entries BEFORE, 0 entries AFTER, exit 0
 * </pre>
 *
 * <p>{@code CliShimPruner.prune} opens {@code store.cliBinDir()} with
 * {@code Files.isDirectory} and {@code Files.list}, and both FOLLOW the link. So
 * every entry it listed was in the other home, every one was judged foreign
 * <em>correctly</em>, and every one was deleted <em>there</em>. Two readers of
 * one rule — {@code home verify}'s walk does not follow links — disagreeing in
 * the one direction that destroys bytes, and reporting success while doing it.
 *
 * <h2>Why this is a graph node and not only a unit test</h2>
 *
 * <p>{@code PruneStaysInsideItsHomeTest} drives {@code CliShimPruner} directly,
 * on all three tiers. What it cannot show is the thing an operator meets: a
 * whole {@code sync} — resolve, prune, presence gate, install pass, recorder —
 * ending <b>exit 0</b> with another home emptied and nothing in the output
 * saying so. This node runs the real CLI, over real cloned homes, and reads the
 * other home's directory before and after.
 *
 * <h2>The victim is a CLONE, never the shared fixture home</h2>
 *
 * <p>Deliberate, and it is not caution about the operator's machine — it is
 * about this graph. If the guard ever regresses, a node pointed at the fixture
 * home would <em>delete the fixture's toolchain</em>, and every later node,
 * including {@code HomeFixpointLaw}, would fail for a reason that has nothing to
 * do with what it asserts. A destructive node has to be destructive to something
 * it owns.
 *
 * <h2>The control that makes the rest mean something</h2>
 *
 * <p>{@code without_the_link_the_same_sync_does_not_refuse} restores a real
 * {@code bin/cli} in the syncing home and runs the identical command again. If
 * that also refused, this node would be asserting "sync refuses", which is a
 * property a completely broken build satisfies — and two assertions in this
 * epic have already shipped green without their fix.
 */
public class SyncStaysInsideItsHome {

    static final NodeSpec SPEC = NodeSpec.of("home.integrity.sync.stays.inside.its.home")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.integrity.fixture")
            .tags("home", "integrity", "confinement", "his-9")
            .timeout("900s")
            // PUBLISHED SO home.fixpoint.law CAN SEE THEM, and that is not
            // bookkeeping. The law does not take a home list and does not scan
            // the filesystem -- HomeFixpointLaw.candidateHomes walks every
            // UPSTREAM CONTEXT VALUE and offers each existing directory to
            // `home verify`. A node that publishes nothing therefore hands it
            // nothing, whatever the dependency edges say.
            //
            // MEASURED on run 20260821-191337, before these two outputs
            // existed: seven store-shaped directories existed under the sandbox
            // when the law ran, production's own `home verify` called all seven
            // homes, and the law reported homesChecked = 1. This node's two
            // clones were among the six it never saw. See DEF-017.
            .output("victimHome", "string")
            .output("syncingHome", "string");

    /** The text every write-confinement refusal carries. */
    private static final String REFUSAL = "outside the home";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            try {
                return check(ctx);
            } catch (IOException e) {
                return NodeResult.error(SPEC.id(), e);
            }
        });
    }

    private static NodeResult check(NodeContext ctx) throws IOException {
        String homeStr = ctx.get("home.integrity.fixture", "integrityHome").orElse(null);
        String scratchStr = ctx.get("home.integrity.fixture", "scratchRoot").orElse(null);
        if (homeStr == null || scratchStr == null) return unproven("missing fixture context");

        Path fixture = Path.of(homeStr);
        Path scratch = Path.of(scratchStr).resolve("his9");
        Files.createDirectories(scratch);

        Path victim = scratch.resolve("victim-home/.skill-manager");
        Path syncing = scratch.resolve("syncing-home/.skill-manager");

        ProcessRecord mkVictim = HomeIntegritySupport.sm(ctx, "his9-clone-victim", fixture,
                "home", "clone", "--from", fixture.toString(), "--to", victim.toString());
        if (mkVictim.exitCode() != 0) {
            return unproven("could not build the victim home: home clone exited "
                    + mkVictim.exitCode()).process(mkVictim);
        }
        ProcessRecord mkSyncing = HomeIntegritySupport.sm(ctx, "his9-clone-syncing", fixture,
                "home", "clone", "--from", fixture.toString(), "--to", syncing.toString());
        if (mkSyncing.exitCode() != 0) {
            return unproven("could not build the syncing home: home clone exited "
                    + mkSyncing.exitCode()).process(mkSyncing);
        }

        Path victimBin = victim.resolve("bin/cli");
        int before = count(victimBin);
        if (before == 0) {
            // A victim with nothing in bin/cli cannot show a loss, and a node
            // that cannot show a loss would pass against the defect.
            return unproven("the victim home has an empty bin/cli, so nothing here could "
                    + "measure a deletion").process(mkVictim);
        }

        // THE SHAPE. bin/cli is not a directory holding links out; it IS a link
        // out, which is what makes Files.list enumerate the other home.
        Path syncingBin = syncing.resolve("bin/cli");
        deleteTree(syncingBin);
        Files.createSymbolicLink(syncingBin, victimBin);

        ProcessRecord sync = HomeIntegritySupport.sm(ctx, "his9-sync-through-the-link", syncing,
                "sync", "--skip-mcp", "--skip-agents", "--yes");
        String syncOut = readLog(ctx.reportDir(), sync);
        int after = count(victimBin);

        boolean victimIntact = after == before;
        boolean refused = syncOut.contains(REFUSAL);
        boolean refusalNamesTheHome = mentions(syncOut, syncing);
        boolean refusalNamesThePath = mentions(syncOut, victimBin);

        // THE CONTROL. Same home, same command, real bin/cli. If this refuses
        // too then the assertions above are about something else.
        Files.delete(syncingBin);
        Files.createDirectories(syncingBin);
        ProcessRecord control = HomeIntegritySupport.sm(ctx, "his9-sync-without-the-link", syncing,
                "sync", "--skip-mcp", "--skip-agents", "--yes");
        String controlOut = readLog(ctx.reportDir(), control);
        boolean controlDidNotRefuse = !controlOut.contains(REFUSAL);

        boolean pass = victimIntact && refused && refusalNamesTheHome
                && refusalNamesThePath && controlDidNotRefuse;

        NodeResult result = pass
                ? NodeResult.pass(SPEC.id())
                : NodeResult.fail(SPEC.id(),
                        "victim bin/cli " + before + " -> " + after
                                + " refused=" + refused
                                + " namesHome=" + refusalNamesTheHome
                                + " namesPath=" + refusalNamesThePath
                                + " controlDidNotRefuse=" + controlDidNotRefuse
                                + " syncExit=" + sync.exitCode()
                                + " controlExit=" + control.exitCode());

        return result
                .process(mkVictim).process(mkSyncing).process(sync).process(control)
                .assertion("a_sync_in_one_home_removes_nothing_from_another", victimIntact)
                .assertion("it_refuses_rather_than_pruning_through_the_link", refused)
                .assertion("the_refusal_names_the_home_it_was_given", refusalNamesTheHome)
                .assertion("the_refusal_names_the_path_it_would_have_reached",
                        refusalNamesThePath)
                .assertion("without_the_link_the_same_sync_does_not_refuse", controlDidNotRefuse)
                .metric("victimEntriesBefore", before)
                .metric("victimEntriesAfter", after)
                // Both verify clean (exit 0, measured on run 20260821-191337),
                // so handing them to the law extends its reach rather than
                // importing a known-bad fixture into a shared post-condition.
                .publish("victimHome", victim.toString())
                .publish("syncingHome", syncing.toString())
                .log("DEF-007, measured 2026-08-21: this sync took the other home's bin/cli "
                        + "from 2 entries to 0 and exited 0. The prune decided every entry "
                        + "correctly, in the wrong home.");
    }

    // ------------------------------------------------------------- plumbing

    private static int count(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> entries = Files.list(dir)) {
            return (int) entries.count();
        }
    }

    /** Remove a real directory tree, or the link standing where one should be. */
    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) {
            Files.delete(p);
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(each);
            }
        }
    }

    /**
     * Does {@code out} name {@code path}, in either spelling? On macOS a temp
     * path is reachable as {@code /var/…} and {@code /private/var/…}, and the
     * CLI prints whichever it resolved; a raw {@code contains} on one of them is
     * a false negative.
     */
    private static boolean mentions(String out, Path path) {
        if (out.contains(path.toString())) return true;
        try {
            return out.contains(path.toRealPath().toString());
        } catch (IOException notThere) {
            return false;
        }
    }

    private static String readLog(Path reportDir, ProcessRecord proc) {
        try {
            String log = proc.logPath();
            if (log == null || log.isBlank()) return "";
            Path p = Path.of(log);
            if (!p.isAbsolute() && reportDir != null) p = reportDir.resolve(p);
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static NodeResult unproven(String why) {
        return NodeResult.fail(SPEC.id(), why)
                .assertion("a_sync_in_one_home_removes_nothing_from_another", false)
                .assertion("it_refuses_rather_than_pruning_through_the_link", false)
                .assertion("the_refusal_names_the_home_it_was_given", false)
                .assertion("the_refusal_names_the_path_it_would_have_reached", false)
                .assertion("without_the_link_the_same_sync_does_not_refuse", false);
    }
}
