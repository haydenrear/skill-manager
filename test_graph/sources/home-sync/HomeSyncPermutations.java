///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES HomeSyncSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reconciliation cases that are not any one direction: the ones a
 * three-tier mechanism has to get right no matter which pair of homes it is
 * pointed at.
 *
 * <ul>
 *   <li><b>A dry run writes nothing.</b> Measured over the whole destination
 *       home with an EMPTY allow-list, and over a destination that does not
 *       exist at all. The allow-list used to name one artefact — the home lock,
 *       which a dry run created by taking it. That was measured in the
 *       operator's own read-only {@code ~/.skill-manager} and it is issue #42:
 *       content-benign, and still the write a caller reaches for
 *       {@code --dry-run} to avoid, and a hard failure against a read-only or
 *       frozen destination. The lock is still taken; it is taken through a path
 *       that creates nothing, which is sound because the lock file's own
 *       absence is what proves no peer holds the lock.</li>
 *   <li><b>A dry run describes the run that follows it.</b> Same statuses, unit
 *       for unit. A {@code --dry-run} that is a separate code path is how a
 *       plan comes to promise something the real pass does not do.</li>
 *   <li><b>A unit changed on both sides is a conflict, and a conflict writes
 *       nothing at all.</b> Not the source's version, not a marker file, not a
 *       partial tree.</li>
 *   <li><b>A unit the source no longer has is reported, never deleted</b> —
 *       including when the destination has edited it, which is the case where
 *       deleting would be worst and easiest.</li>
 *   <li><b>A unit with no materialization record has no common ancestor</b>, so
 *       it is held back, and {@code --merge} reports a conflict rather than
 *       guessing. This is the shape of a real defect from earlier in this epic:
 *       {@code home clone} used to produce provenance-less homes, and every
 *       unit in them reported as conflicted forever.</li>
 *   <li><b>A frozen destination refuses</b>, before anything is staged, dry run
 *       included — and the destination is read back as {@code frozen} first,
 *       because a refusal measured against a home that was never frozen is a
 *       measurement of nothing.</li>
 * </ul>
 *
 * <h2>Setup steps are preconditions, not claims</h2>
 *
 * <p>Every command here that exists to put the fixture into a state goes
 * through {@link HomeSyncSupport#setup}, which fails the node when the command
 * refuses. Issue #135: the freeze below used to run through
 * {@link HomeSyncSupport#sm} with its record discarded, against a path that was
 * never laid out as a home. Once {@code home policy} grew its
 * {@code NotAHomeException} refusal the freeze exited 2 and did nothing, the
 * destination stayed <em>live</em>, and the three commands asserted to exit 9
 * exited 0 and populated it. The node reported {@code frozenExits=0/0/0}, which
 * names the freeze contract; the actual defect was one line above, in this
 * file.
 *
 * <p>Runs against its own pair of homes rather than the graph's three tiers:
 * these cases need the destination in states the tiered flow deliberately never
 * reaches, and building them there would leave the later nodes asserting
 * against a home that had been deliberately corrupted.
 */
public class HomeSyncPermutations {

    private static final String KEEP = "p-keep";
    private static final String BOTH = "p-both";
    private static final String ORPHAN = "p-orphan";
    private static final String FRESH = "p-fresh";
    private static final String GHOST = "p-ghost";
    /** {@code FrozenHomeException.EXIT_CODE} — the contract, restated here because a graph node runs the CLI as a process. */
    private static final int FROZEN_EXIT_CODE = 9;

    static final NodeSpec SPEC = NodeSpec.of("home.sync.permutations")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "permutations", "dry-run", "conflict", "frozen")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            if (workspaceRaw == null) {
                return NodeResult.fail("home.sync.permutations", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("permutations");
            Path source = base.resolve("source");
            Path dest = base.resolve("dest");
            Files.createDirectories(base);

            HomeSyncSupport.mkUnit(source, KEEP, "keep v1");
            HomeSyncSupport.mkUnit(source, BOTH, "both v1");
            HomeSyncSupport.write(HomeSyncSupport.unitDir(source, BOTH).resolve("shared.md"),
                    "shared v1\n");
            HomeSyncSupport.mkUnit(source, ORPHAN, "orphan v1");

            ProcessRecord seed = HomeSyncSupport.sm(ctx, "perm-seed", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(), "--json");
            boolean seeded = seed.exitCode() == 0
                    && HomeSyncSupport.status(HomeSyncSupport.json(ctx, "perm-seed"),
                            "skill:" + KEEP).equals("new");

            // --- put the destination into every interesting state ------------
            HomeSyncSupport.mkUnit(source, FRESH, "fresh v1 — appeared upstream");
            HomeSyncSupport.append(HomeSyncSupport.unitDir(source, BOTH).resolve("shared.md"),
                    "SOURCE SIDE OF THE CONFLICT\n");
            HomeSyncSupport.append(HomeSyncSupport.unitDir(dest, BOTH).resolve("shared.md"),
                    "DESTINATION SIDE OF THE CONFLICT\n");
            HomeSyncSupport.append(HomeSyncSupport.unitDir(dest, ORPHAN).resolve("SKILL.md"),
                    "orphan — edited locally, then deleted upstream\n");
            HomeSyncSupport.deleteTree(HomeSyncSupport.unitDir(source, ORPHAN));
            // A directory nobody has provenance for: not materialized, not
            // cloned, just present. The conservative path is the only safe one.
            HomeSyncSupport.mkUnit(dest, GHOST, "ghost — no materialization record");
            HomeSyncSupport.mkUnit(source, GHOST, "ghost — the source's own idea of it");

            LinkedHashMap<String, String> destBeforeDry = HomeSyncSupport.entryDigests(dest);
            LinkedHashMap<String, String> bothBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, BOTH));
            LinkedHashMap<String, String> orphanBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, ORPHAN));
            LinkedHashMap<String, String> ghostBefore =
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, GHOST));

            // --- 1. the dry run ---------------------------------------------
            ProcessRecord dry = HomeSyncSupport.sm(ctx, "perm-dry-run", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(),
                    "--dry-run", "--json");
            Map<String, Object> dryReport = HomeSyncSupport.json(ctx, "perm-dry-run");
            // NOTHING is allowed now. The allow-list used to name the home lock,
            // which a dry run created by taking it — issue #42, measured in the
            // operator's own read-only ~/.skill-manager. An empty allow-list is
            // the whole claim: `--dry-run` is documented "write nothing".
            List<String> dryWrote = HomeSyncSupport.wroteNothingBut(destBeforeDry,
                    HomeSyncSupport.entryDigests(dest), Set.of());
            boolean dryRunWroteNothing = dryWrote.isEmpty();
            boolean dryRunSawTheNewUnit =
                    HomeSyncSupport.status(dryReport, "skill:" + FRESH).equals("new")
                            && !Files.exists(HomeSyncSupport.unitDir(dest, FRESH));

            // A destination that does not exist yet is where "writes nothing" is
            // easiest to get wrong, and it had TWO ways to go wrong: the store's
            // own init() would lay out a whole home before anything was
            // reported, and taking the home lock created .materialization/ and
            // a zero-byte .home.lock inside it. The second was live until issue
            // #42, and it is the write that would have failed outright against a
            // read-only or frozen destination — precisely the write a caller
            // reaches for --dry-run to avoid.
            Path fresh = base.resolve("never-existed");
            ProcessRecord dryFresh = HomeSyncSupport.sm(ctx, "perm-dry-run-fresh", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", fresh.toString(),
                    "--dry-run", "--json");
            boolean dryRunCreatesNothing = dryFresh.exitCode() == 0
                    && !Files.exists(fresh)
                    && HomeSyncSupport.names(fresh).isEmpty();

            // --- 2. the real pass, which must match the plan ------------------
            ProcessRecord real = HomeSyncSupport.sm(ctx, "perm-plain", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(), "--json");
            Map<String, Object> realReport = HomeSyncSupport.json(ctx, "perm-plain");
            boolean planMatchedTheRun = true;
            for (String unit : List.of(KEEP, BOTH, ORPHAN, FRESH, GHOST)) {
                if (!HomeSyncSupport.status(dryReport, "skill:" + unit)
                        .equals(HomeSyncSupport.status(realReport, "skill:" + unit))) {
                    planMatchedTheRun = false;
                }
            }
            boolean newUnitArrived = HomeSyncSupport.status(realReport, "skill:" + FRESH)
                    .equals("new") && Files.isRegularFile(
                            HomeSyncSupport.unitDir(dest, FRESH).resolve("SKILL.md"));
            boolean editedUnitHeldBack = HomeSyncSupport.status(realReport, "skill:" + BOTH)
                    .equals("held-back");
            boolean recordlessUnitHeldBack = HomeSyncSupport.status(realReport, "skill:" + GHOST)
                    .equals("held-back");
            boolean removedUpstreamReported = HomeSyncSupport.status(realReport, "skill:" + ORPHAN)
                    .equals("removed-upstream");
            boolean removedUpstreamNeverDeleted = HomeSyncSupport
                    .difference(orphanBefore,
                            HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, ORPHAN)))
                    .isEmpty();

            // --- 3. --merge, where the two hard cases separate ---------------
            ProcessRecord merge = HomeSyncSupport.sm(ctx, "perm-merge", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", dest.toString(),
                    "--merge", "--json");
            Map<String, Object> mergeReport = HomeSyncSupport.json(ctx, "perm-merge");
            List<String> bothMoved = HomeSyncSupport.difference(bothBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, BOTH)));
            List<String> ghostMoved = HomeSyncSupport.difference(ghostBefore,
                    HomeSyncSupport.entryDigests(HomeSyncSupport.unitDir(dest, GHOST)));

            boolean conflictReported = merge.exitCode() == 1
                    && HomeSyncSupport.status(mergeReport, "skill:" + BOTH).equals("conflicted")
                    && HomeSyncSupport.conflicts(mergeReport, "skill:" + BOTH)
                            .equals(List.of("shared.md"));
            boolean conflictWroteNothing = bothMoved.isEmpty();
            boolean recordlessUnitConflicts =
                    HomeSyncSupport.status(mergeReport, "skill:" + GHOST).equals("conflicted")
                            && ghostMoved.isEmpty();

            // --- 4. a frozen destination ------------------------------------
            // `--init` and `setup` are both issue #135. This path had never
            // been laid out as a home, so once `home policy` grew its
            // NotAHomeException refusal the freeze exited 2 and did nothing —
            // and because the record was discarded, the destination stayed
            // LIVE and the three commands below exited 0 and populated it. The
            // node reported `frozenExits=0/0/0`, which reads as "the freeze
            // contract broke" and was in fact "nothing was ever frozen".
            // HomeSyncSupport.setup fails the node at the setup step instead.
            Path frozen = base.resolve("frozen-dest");
            HomeSyncSupport.setup(ctx, "perm-freeze", frozen.toString(),
                    "home", "policy", "frozen", "--home", frozen.toString(), "--init");
            // And the state itself is read back, not inferred from exit 0: an
            // exit code says a command did not refuse, and only the policy the
            // home reports says the destination is in the state the three
            // refusals below are a claim ABOUT. This assertion is what makes
            // `a_frozen_destination_refuses_...` mean anything at all.
            HomeSyncSupport.setup(ctx, "perm-freeze-readback", frozen.toString(),
                    "home", "policy", "--home", frozen.toString());
            boolean theDestinationIsActuallyFrozenBeforeAnythingIsMeasured =
                    HomeSyncSupport.policyLine(HomeSyncSupport.log(ctx, "perm-freeze-readback"))
                            .equals("frozen");
            LinkedHashMap<String, String> frozenBefore = HomeSyncSupport.entryDigests(frozen);
            ProcessRecord refused = HomeSyncSupport.sm(ctx, "perm-frozen-sync", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", frozen.toString(),
                    "--json");
            ProcessRecord refusedDry = HomeSyncSupport.sm(ctx, "perm-frozen-dry",
                    source.toString(), "home", "sync", "--from", source.toString(),
                    "--to", frozen.toString(), "--dry-run", "--json");
            ProcessRecord refusedGate = HomeSyncSupport.sm(ctx, "perm-frozen-close-out",
                    source.toString(), "home", "close-out", "--home", source.toString(),
                    "--into", frozen.toString(), "--json");
            List<String> frozenMoved = HomeSyncSupport.difference(frozenBefore,
                    HomeSyncSupport.entryDigests(frozen));
            // The exact code, not merely non-zero. FrozenHomeException.EXIT_CODE
            // is 9 and its whole purpose is to be branched on by a caller that
            // is not this JVM: "refused, nothing attempted" is a different
            // situation from 1 ("this worktree still holds work") and from the
            // 2 a picocli usage error exits with. Both commands used to let the
            // exception escape as a stack trace, which is 1.
            boolean frozenRefuses = refused.exitCode() == FROZEN_EXIT_CODE
                    && refusedDry.exitCode() == FROZEN_EXIT_CODE
                    && refusedGate.exitCode() == FROZEN_EXIT_CODE;
            boolean frozenWroteNothing = frozenMoved.isEmpty()
                    && HomeSyncSupport.names(HomeSyncSupport.skills(frozen)).isEmpty();

            HomeSyncSupport.setup(ctx, "perm-thaw", frozen.toString(),
                    "home", "policy", "live", "--home", frozen.toString());
            ProcessRecord thawed = HomeSyncSupport.sm(ctx, "perm-thawed-sync", source.toString(),
                    "home", "sync", "--from", source.toString(), "--to", frozen.toString(),
                    "--json");
            boolean thawedHomeAcceptsTheSameSync = thawed.exitCode() == 0
                    && Files.isRegularFile(
                            HomeSyncSupport.unitDir(frozen, KEEP).resolve("SKILL.md"));

            boolean pass = seeded && dryRunWroteNothing && dryRunSawTheNewUnit
                    && dryRunCreatesNothing && planMatchedTheRun && newUnitArrived
                    && editedUnitHeldBack && recordlessUnitHeldBack && removedUpstreamReported
                    && removedUpstreamNeverDeleted && conflictReported && conflictWroteNothing
                    && recordlessUnitConflicts
                    && theDestinationIsActuallyFrozenBeforeAnythingIsMeasured
                    && frozenRefuses && frozenWroteNothing
                    && thawedHomeAcceptsTheSameSync;
            return (pass
                    ? NodeResult.pass("home.sync.permutations")
                    : NodeResult.fail("home.sync.permutations",
                            "seeded=" + seeded + " dryWrote=" + dryWrote
                                    + " dryFreshNames=" + HomeSyncSupport.names(fresh)
                                    + " planMatched=" + planMatchedTheRun
                                    + " both=" + HomeSyncSupport.status(realReport, "skill:" + BOTH)
                                    + " ghost=" + HomeSyncSupport.status(realReport, "skill:" + GHOST)
                                    + " orphan=" + HomeSyncSupport.status(realReport, "skill:" + ORPHAN)
                                    + " mergeExit=" + merge.exitCode()
                                    + " bothMerge="
                                    + HomeSyncSupport.status(mergeReport, "skill:" + BOTH)
                                    + " bothMoved=" + bothMoved + " ghostMoved=" + ghostMoved
                                    + " destinationPolicy=" + HomeSyncSupport.policyLine(
                                            HomeSyncSupport.log(ctx, "perm-freeze-readback"))
                                    + " frozenExits=" + refused.exitCode() + "/"
                                    + refusedDry.exitCode() + "/" + refusedGate.exitCode()
                                    + " (expected " + FROZEN_EXIT_CODE + ")"
                                    + " frozenMoved=" + frozenMoved))
                    .process(seed).process(dry).process(dryFresh).process(real).process(merge)
                    .process(refused).process(refusedDry).process(refusedGate).process(thawed)
                    .assertion("a_dry_run_writes_nothing_into_the_destination_home", dryRunWroteNothing)
                    .assertion("a_dry_run_reports_a_new_unit_without_creating_it",
                            dryRunSawTheNewUnit)
                    .assertion("a_dry_run_into_a_fresh_home_creates_nothing_at_all", dryRunCreatesNothing)
                    .assertion("the_dry_run_reported_exactly_what_the_real_run_did",
                            planMatchedTheRun)
                    .assertion("a_unit_that_appeared_upstream_is_created", newUnitArrived)
                    .assertion("a_locally_modified_destination_unit_is_held_back",
                            editedUnitHeldBack)
                    .assertion("a_unit_with_no_materialization_record_is_held_back",
                            recordlessUnitHeldBack)
                    .assertion("a_unit_removed_upstream_is_reported", removedUpstreamReported)
                    .assertion("a_unit_removed_upstream_while_edited_locally_is_never_deleted",
                            removedUpstreamNeverDeleted)
                    .assertion("a_file_changed_on_both_sides_is_reported_as_a_conflict",
                            conflictReported)
                    .assertion("a_conflicted_unit_has_nothing_written_into_it", conflictWroteNothing)
                    .assertion("a_unit_with_no_common_ancestor_conflicts_rather_than_guessing",
                            recordlessUnitConflicts)
                    .assertion("the_destination_the_refusals_are_measured_against_is_really_frozen",
                            theDestinationIsActuallyFrozenBeforeAnythingIsMeasured)
                    .assertion("a_frozen_destination_refuses_a_sync_a_dry_run_and_a_close_out_with_exit_9",
                            frozenRefuses)
                    .assertion("a_frozen_destination_has_nothing_written_into_it",
                            frozenWroteNothing)
                    .assertion("the_same_sync_succeeds_once_the_home_is_live_again",
                            thawedHomeAcceptsTheSameSync)
                    .metric("frozenSyncExitCode", refused.exitCode())
                    .metric("frozenDryRunExitCode", refusedDry.exitCode())
                    .metric("frozenCloseOutExitCode", refusedGate.exitCode())
                    .metric("mergeExitCode", merge.exitCode());
        });
    }
}
