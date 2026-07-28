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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two ways a reconcile used to report on units it had never looked at, and
 * call the result clean.
 *
 * <h2>1. A gate that failed open</h2>
 *
 * <p>{@code home close-out --home <the worktree DIRECTORY>} — rather than
 * {@code <worktree>/.skill-manager} — exited <b>0</b> with {@code "safe": true}
 * and {@code "blockers": []}, and printed
 * {@code ✓ <path> holds nothing that removing it would destroy} while naming
 * the directory that held the only copy of the agent's edit. So did a
 * {@code --home} that did not exist at all. The mechanism was arithmetic rather
 * than intent: {@code unitsToVisit} returns the union of both sides, a non-home
 * contributes zero units, every remaining unit becomes {@code removed-upstream},
 * and {@code removed-upstream} maps to no blocker.
 *
 * <p>That mistake is maximally likely, which is why it is a refusal rather than
 * a gentler report: {@code <worktree>} is exactly the argument
 * {@code git worktree remove} takes, so the wrong path is the one already in
 * the operator's hand. The same hole was on the sync side —
 * {@code home sync --from <a path that does not exist>} printed
 * {@code ✓ reconciled} with all-zero counts, exit 0, and created the
 * destination's layout on the way.
 *
 * <h2>2. Units nobody enumerated</h2>
 *
 * <p>{@code ChildHomeMaterializer.unitDirectories} skipped any unit directory
 * that was a symlink, and skipped an entire KIND directory that was one, with
 * no report of either. Measured: a home whose {@code skills/} was a symlink
 * reconciled as {@code {"clean":true,"units":[]}} and the destination's
 * {@code skills/} stayed empty; a single symlinked unit beside a normal one
 * synced only the normal one, reported {@code clean=true}, and never named the
 * other. {@code MaterializationMode.LINK} produces exactly that shape.
 *
 * <p>The two code paths also disagreed: {@code reconcile} applies
 * {@code NOFOLLOW_LINKS} only to the final path component, so it read THROUGH a
 * linked {@code skills/}, and the same unit was invisible to {@code home sync}
 * while {@code home close-out} called it conflicted — purely because the
 * destination contributed the name to the union.
 *
 * <h2>One node, because it is one claim</h2>
 *
 * <p>Both are the same sentence: <b>a report may not be clean about a unit it
 * did not account for</b>. A path that is not a home accounts for nothing at
 * all; a linked unit accounts for bytes that belong to whatever the link points
 * at. Asserting them apart would have let each be read as a quirk of its own
 * command rather than as the property they share.
 *
 * <p>Every assertion is on an exit code AND on bytes or on the report's own
 * unit list. An exit code alone cannot tell a command that refused from one
 * that crashed, and a status string alone cannot tell a report that named a
 * unit from one that invented it.
 */
public class HomeSyncHomeness {

    private static final String UNIT = "hn-unit";
    private static final String LINKED = "hn-linked";
    /** {@code NotAHomeException.EXIT_CODE} — restated because a node runs the CLI as a process. */
    private static final int NOT_A_HOME_EXIT_CODE = 2;

    static final NodeSpec SPEC = NodeSpec.of("home.sync.homeness")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("home.sync.fixture.built")
            .tags("home-sync", "close-out", "gate", "symlink")
            .timeout("420s");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String workspaceRaw = ctx.get("home.sync.fixture.built", "workspace").orElse(null);
            String ambient = ctx.get("home.sync.fixture.built", "ambientHome").orElse(null);
            if (workspaceRaw == null || ambient == null) {
                return NodeResult.fail("home.sync.homeness", "missing upstream context");
            }
            Path base = Path.of(workspaceRaw).resolve("homeness");
            Files.createDirectories(base);

            // ---------- 1. the gate, pointed one directory too high ----------
            // A worktree the way `git worktree add` leaves one: a checkout with
            // files in it, and the Skill Manager home one level down.
            Path worktreeDir = base.resolve("ticket-worktree");
            Path worktreeHome = worktreeDir.resolve(".skill-manager");
            Path projectHome = base.resolve("project/.skill-manager");
            Files.createDirectories(worktreeDir);
            HomeSyncSupport.write(worktreeDir.resolve("README.md"), "a checkout, not a home\n");
            HomeSyncSupport.mkUnit(projectHome, UNIT, "unit v1");
            HomeSyncSupport.sm(ctx, "hn-seed-worktree", ambient, "home", "sync",
                    "--from", projectHome.toString(), "--to", worktreeHome.toString(), "--json");

            String onlyCopy = "THE ONLY COPY OF THE AGENT'S WORK\n";
            HomeSyncSupport.write(HomeSyncSupport.unitDir(worktreeHome, UNIT).resolve("SKILL.md"),
                    onlyCopy);

            ProcessRecord tooHigh = HomeSyncSupport.sm(ctx, "hn-close-out-dir", ambient,
                    "home", "close-out", "--home", worktreeDir.toString(),
                    "--into", projectHome.toString(), "--json");
            Map<String, Object> tooHighVerdict = HomeSyncSupport.json(ctx, "hn-close-out-dir");
            boolean theWorktreeDirectoryIsRefused = tooHigh.exitCode() == NOT_A_HOME_EXIT_CODE
                    && !HomeSyncSupport.flag(tooHighVerdict, "safe");

            Path missing = base.resolve("no/such/home");
            ProcessRecord nonexistent = HomeSyncSupport.sm(ctx, "hn-close-out-missing", ambient,
                    "home", "close-out", "--home", missing.toString(),
                    "--into", projectHome.toString(), "--json");
            boolean aNonexistentHomeIsRefused = nonexistent.exitCode() == NOT_A_HOME_EXIT_CODE
                    && !HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "hn-close-out-missing"),
                            "safe");

            Path plainFile = base.resolve("not-a-directory");
            HomeSyncSupport.write(plainFile, "definitely not a home\n");
            ProcessRecord regularFile = HomeSyncSupport.sm(ctx, "hn-close-out-file", ambient,
                    "home", "close-out", "--home", plainFile.toString(),
                    "--into", projectHome.toString(), "--json");
            boolean aRegularFileIsRefused = regularFile.exitCode() == NOT_A_HOME_EXIT_CODE;

            // The refusal has to be a refusal, not a crash: nothing may be
            // created at the path that was named, and the real home's bytes
            // must be exactly where they were.
            boolean theRefusalCreatedNothing = !Files.exists(missing)
                    && !Files.exists(worktreeDir.resolve("skills"), LinkOption.NOFOLLOW_LINKS)
                    && HomeSyncSupport.read(HomeSyncSupport.unitDir(worktreeHome, UNIT)
                            .resolve("SKILL.md")).equals(onlyCopy);

            // And naming the real home gives the real answer, so the refusal is
            // not simply "close-out never clears anything now".
            ProcessRecord correct = HomeSyncSupport.sm(ctx, "hn-close-out-correct", ambient,
                    "home", "close-out", "--home", worktreeHome.toString(),
                    "--into", projectHome.toString(), "--json");
            boolean theRealHomeStillGetsTheRealVerdict = correct.exitCode() == 1
                    && !HomeSyncSupport.flag(HomeSyncSupport.json(ctx, "hn-close-out-correct"),
                            "safe")
                    && HomeSyncSupport.blockerCount(
                            HomeSyncSupport.json(ctx, "hn-close-out-correct")) == 1;

            // The same hole on the sync side.
            Path wouldHaveBeenCreated = base.resolve("would-have-been-created");
            ProcessRecord syncFromNothing = HomeSyncSupport.sm(ctx, "hn-sync-from-missing", ambient,
                    "home", "sync", "--from", missing.toString(),
                    "--to", wouldHaveBeenCreated.toString(), "--json");
            boolean syncFromANonHomeIsRefused =
                    syncFromNothing.exitCode() == NOT_A_HOME_EXIT_CODE
                            && !Files.exists(wouldHaveBeenCreated);

            // ---------- 2. symlinked unit and kind directories ---------------
            Path elsewhere = base.resolve("elsewhere");
            HomeSyncSupport.mkSource(elsewhere, LINKED, "lives outside every home");

            Path linkSource = base.resolve("link-source");
            Path linkDest = base.resolve("link-dest");
            HomeSyncSupport.mkUnit(linkSource, UNIT, "an ordinary unit beside a linked one");
            Files.createSymbolicLink(HomeSyncSupport.unitDir(linkSource, LINKED),
                    elsewhere.resolve(LINKED));

            ProcessRecord linkedUnit = HomeSyncSupport.sm(ctx, "hn-sync-linked-unit", ambient,
                    "home", "sync", "--from", linkSource.toString(), "--to", linkDest.toString(),
                    "--json");
            Map<String, Object> linkedReport = HomeSyncSupport.json(ctx, "hn-sync-linked-unit");
            String linkedStatus = HomeSyncSupport.status(linkedReport, "skill:" + LINKED);
            boolean aLinkedUnitIsNamedInTheReport = !linkedStatus.equals("(absent)");
            boolean aLinkedUnitIsReportedAsLinked = linkedStatus.equals("linked");
            boolean aReportWithALinkedUnitIsNotClean = !HomeSyncSupport.flag(linkedReport, "clean")
                    && linkedUnit.exitCode() != 0;
            boolean theOrdinaryUnitBesideItStillReconciles =
                    HomeSyncSupport.status(linkedReport, "skill:" + UNIT).equals("new")
                            && Files.isRegularFile(
                                    HomeSyncSupport.unitDir(linkDest, UNIT).resolve("SKILL.md"));
            boolean nothingWasWrittenThroughTheLink =
                    !Files.exists(HomeSyncSupport.unitDir(linkDest, LINKED),
                            LinkOption.NOFOLLOW_LINKS);

            // The whole kind directory is a link — the shape that reconciled as
            // {"clean":true,"units":[]} with an empty destination.
            Path linkedHome = base.resolve("linked-kind-home");
            Path linkedKindDest = base.resolve("linked-kind-dest");
            HomeSyncSupport.mkHome(linkedHome);
            Files.delete(HomeSyncSupport.skills(linkedHome));
            Files.createSymbolicLink(HomeSyncSupport.skills(linkedHome), elsewhere);

            LinkedHashMap<String, String> elsewhereBefore =
                    HomeSyncSupport.entryDigests(elsewhere);
            ProcessRecord linkedKind = HomeSyncSupport.sm(ctx, "hn-sync-linked-kind", ambient,
                    "home", "sync", "--from", linkedHome.toString(),
                    "--to", linkedKindDest.toString(), "--json");
            Map<String, Object> kindReport = HomeSyncSupport.json(ctx, "hn-sync-linked-kind");
            String kindStatus = HomeSyncSupport.status(kindReport, "skill:" + LINKED);
            boolean aHomeReachedThroughALinkedKindDirIsNotEmpty = !kindStatus.equals("(absent)");
            boolean itsUnitIsReportedAsLinked = kindStatus.equals("linked");
            boolean thatReportIsNotCleanEither = !HomeSyncSupport.flag(kindReport, "clean")
                    && linkedKind.exitCode() != 0;
            boolean nothingWasCopiedThroughTheLinkedKindDir =
                    HomeSyncSupport.names(HomeSyncSupport.skills(linkedKindDest)).isEmpty();
            boolean theLinkTargetWasNotWrittenTo = HomeSyncSupport
                    .difference(elsewhereBefore, HomeSyncSupport.entryDigests(elsewhere)).isEmpty();

            // The gate cannot say whether a link points inside the home about to
            // be removed or outside it, so it must refuse rather than clear.
            ProcessRecord linkedGate = HomeSyncSupport.sm(ctx, "hn-close-out-linked", ambient,
                    "home", "close-out", "--home", linkSource.toString(),
                    "--into", linkDest.toString(), "--json");
            Map<String, Object> linkedVerdict = HomeSyncSupport.json(ctx, "hn-close-out-linked");
            boolean closeOutBlocksOnAUnitItCannotAccountFor = linkedGate.exitCode() != 0
                    && !HomeSyncSupport.flag(linkedVerdict, "safe")
                    && !HomeSyncSupport.blocker(linkedVerdict, "skill:" + LINKED).isEmpty();

            boolean pass = theWorktreeDirectoryIsRefused && aNonexistentHomeIsRefused
                    && aRegularFileIsRefused && theRefusalCreatedNothing
                    && theRealHomeStillGetsTheRealVerdict && syncFromANonHomeIsRefused
                    && aLinkedUnitIsNamedInTheReport && aLinkedUnitIsReportedAsLinked
                    && aReportWithALinkedUnitIsNotClean && theOrdinaryUnitBesideItStillReconciles
                    && nothingWasWrittenThroughTheLink
                    && aHomeReachedThroughALinkedKindDirIsNotEmpty && itsUnitIsReportedAsLinked
                    && thatReportIsNotCleanEither && nothingWasCopiedThroughTheLinkedKindDir
                    && theLinkTargetWasNotWrittenTo && closeOutBlocksOnAUnitItCannotAccountFor;
            return (pass
                    ? NodeResult.pass("home.sync.homeness")
                    : NodeResult.fail("home.sync.homeness",
                            "closeOutDirExit=" + tooHigh.exitCode()
                                    + " missingExit=" + nonexistent.exitCode()
                                    + " fileExit=" + regularFile.exitCode()
                                    + " (expected " + NOT_A_HOME_EXIT_CODE + ")"
                                    + " correctExit=" + correct.exitCode()
                                    + " syncFromMissingExit=" + syncFromNothing.exitCode()
                                    + " refusalCreatedNothing=" + theRefusalCreatedNothing
                                    + " linkedUnitStatus=" + linkedStatus
                                    + " linkedClean=" + HomeSyncSupport.flag(linkedReport, "clean")
                                    + " linkedKindStatus=" + kindStatus
                                    + " linkedKindDestSkills=" + HomeSyncSupport.names(
                                            HomeSyncSupport.skills(linkedKindDest))
                                    + " linkedGateExit=" + linkedGate.exitCode()))
                    .process(tooHigh).process(nonexistent).process(regularFile).process(correct)
                    .process(syncFromNothing).process(linkedUnit).process(linkedKind)
                    .process(linkedGate)
                    .assertion("close_out_refuses_the_worktree_directory_instead_of_clearing_it",
                            theWorktreeDirectoryIsRefused)
                    .assertion("close_out_refuses_a_home_that_does_not_exist", aNonexistentHomeIsRefused)
                    .assertion("close_out_refuses_a_regular_file_named_as_a_home", aRegularFileIsRefused)
                    .assertion("the_refusal_writes_nothing_and_leaves_the_real_home_intact",
                            theRefusalCreatedNothing)
                    .assertion("naming_the_real_home_still_produces_the_real_verdict",
                            theRealHomeStillGetsTheRealVerdict)
                    .assertion("home_sync_refuses_a_source_that_is_not_a_home",
                            syncFromANonHomeIsRefused)
                    .assertion("a_symlinked_unit_directory_is_named_in_the_report",
                            aLinkedUnitIsNamedInTheReport)
                    .assertion("and_it_is_reported_as_linked", aLinkedUnitIsReportedAsLinked)
                    .assertion("a_report_that_could_not_account_for_a_unit_is_not_clean",
                            aReportWithALinkedUnitIsNotClean)
                    .assertion("an_ordinary_unit_beside_a_linked_one_still_reconciles",
                            theOrdinaryUnitBesideItStillReconciles)
                    .assertion("nothing_is_written_through_a_linked_unit_directory",
                            nothingWasWrittenThroughTheLink)
                    .assertion("a_home_whose_kind_directory_is_a_link_is_not_reported_as_empty",
                            aHomeReachedThroughALinkedKindDirIsNotEmpty)
                    .assertion("its_units_are_reported_as_linked", itsUnitIsReportedAsLinked)
                    .assertion("that_report_is_not_clean_either", thatReportIsNotCleanEither)
                    .assertion("nothing_is_copied_through_a_linked_kind_directory",
                            nothingWasCopiedThroughTheLinkedKindDir)
                    .assertion("the_link_target_outside_every_home_is_never_written_to",
                            theLinkTargetWasNotWrittenTo)
                    .assertion("close_out_blocks_on_a_unit_it_cannot_account_for",
                            closeOutBlocksOnAUnitItCannotAccountFor)
                    .metric("closeOutOnWorktreeDirectoryExitCode", tooHigh.exitCode())
                    .metric("closeOutOnMissingHomeExitCode", nonexistent.exitCode())
                    .metric("syncFromNonHomeExitCode", syncFromNothing.exitCode())
                    .metric("linkedUnitsReported",
                            (aLinkedUnitIsReportedAsLinked ? 1 : 0) + (itsUnitIsReportedAsLinked ? 1 : 0));
        });
    }
}
