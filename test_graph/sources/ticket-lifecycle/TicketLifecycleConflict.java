///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;
import com.hayden.testgraphsdk.sdk.Procs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>Step 5 — the conflict.</b> Two tickets edited one skill differently. One
 * of them reached the project home first; the other must be told, in terms it
 * can act on, and must not be allowed to overwrite what is already there.
 *
 * <h2>Which ticket wins is not decided here, and must not be</h2>
 *
 * <p>The previous node deliberately made the two syncs contend, so which one
 * acquired the lock first is the kernel's answer, not the fixture's. Hard-coding
 * "B conflicts" would make this node pass or fail on scheduling. So it reads the
 * project home, works out which side landed, and asserts the properties that
 * must hold whichever it was.
 *
 * <h2>The three claims</h2>
 *
 * <ol>
 *   <li><b>Reported, with the in-unit paths to resolve.</b> Not "there was a
 *       problem" — the file, named relative to the unit, so an agent can open
 *       it.</li>
 *   <li><b>Not silently clobbered.</b> The winner's bytes are still in the
 *       project home, compared byte for byte. This is the claim with a
 *       companion below: the same oracle is pointed at a planted clobber and
 *       must fire.</li>
 *   <li><b>The rest still lands.</b> {@code tl-x} and {@code tl-y} both arrive
 *       clean. A conflict that stopped the whole reconcile would be a different
 *       and much worse failure than the one being tested.</li>
 * </ol>
 *
 * <h2>The remedy's TAIL, not only its head</h2>
 *
 * <p>{@code close-change.sh} once ran a regex substitution over the CLI's
 * rendered remedy to make the command runnable, and its token boundary matched
 * inside {@code skill-manager.toml} — the most common conflicted file there is
 * — rewriting the operator's conflict list into a path in a different
 * repository. A head-only assertion is satisfied by that defect. So the tail is
 * checked as data: every path it names must be a relative path that exists
 * inside the conflicted unit, and none of them may be absolute.
 *
 * <h2>What resolving actually takes, which is not what the remedy implies</h2>
 *
 * <p>The remedy ends "then resolve: SKILL.md". Measured here: resolving by hand
 * <em>in the worktree</em> — even to the union of both sides — does NOT clear
 * the conflict, because the merge base stays the worktree's own clone-time
 * baseline and the file is then still changed on both sides. What clears it is
 * the two homes agreeing on the file. That is what this node does, and it is
 * asserted rather than narrated: after the resolution the reconcile reports
 * {@code unchanged} and both close-outs pass.
 */
public class TicketLifecycleConflict {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.conflict")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.concurrent.close.out")
            .tags("ticket-lifecycle", "conflict", "no-destruction")
            .timeout("900s")
            .output("resolvedShared", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String projectRaw = ctx.get("ticket.lifecycle.fixture.built", "projectHome")
                    .orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            String homeARaw = ctx.get("ticket.lifecycle.provisioned", "homeA").orElse(null);
            String homeBRaw = ctx.get("ticket.lifecycle.provisioned", "homeB").orElse(null);
            String contentA = ctx.get("ticket.lifecycle.agent.edits", "sharedContentA")
                    .orElse(null);
            String contentB = ctx.get("ticket.lifecycle.agent.edits", "sharedContentB")
                    .orElse(null);
            if (projectRaw == null || ambient == null || homeARaw == null || homeBRaw == null
                    || contentA == null || contentB == null) {
                return NodeResult.fail("ticket.lifecycle.conflict", "missing upstream context");
            }
            Path project = Path.of(projectRaw);
            Path homeA = Path.of(homeARaw);
            Path homeB = Path.of(homeBRaw);
            Path projectShared = TicketLifecycleSupport
                    .unitDir(project, TicketLifecycleSupport.SHARED).resolve("SKILL.md");

            // --- who landed? --------------------------------------------------
            String landed = HomeSyncSupport.read(projectShared);
            boolean aLanded = landed.equals(contentA);
            boolean bLanded = landed.equals(contentB);
            boolean exactlyOneSideLanded = aLanded ^ bLanded;
            if (!exactlyOneSideLanded) {
                return NodeResult.fail("ticket.lifecycle.conflict",
                        "the project home's shared unit matches neither ticket's edit byte for "
                                + "byte, so nothing below can be about a conflict between them; "
                                + "landed=[" + landed + "]")
                        .assertion("exactly_one_tickets_edit_landed_in_the_project_home", false);
            }
            String winnerContent = aLanded ? contentA : contentB;
            String loserContent = aLanded ? contentB : contentA;
            Path loserHome = aLanded ? homeB : homeA;
            String loserTicket = aLanded
                    ? TicketLifecycleSupport.TICKET_B : TicketLifecycleSupport.TICKET_A;

            // --- the loser is blocked, and told what to do ---------------------
            ProcessRecord blocked = TicketLifecycleSupport.sm(ctx, "close-out-loser", ambient,
                    "home", "close-out", "--home", loserHome.toString(), "--into", projectRaw,
                    "--json");
            Map<String, Object> verdict = HomeSyncSupport.json(ctx, "close-out-loser");
            Map<String, Object> sharedBlocker = HomeSyncSupport.blocker(verdict,
                    "skill:" + TicketLifecycleSupport.SHARED);
            boolean theLoserIsBlockedOnTheSharedUnit =
                    blocked.exitCode() != 0 && !sharedBlocker.isEmpty();

            // Run the remedy verbatim. The first pass' remedy is a plain sync,
            // whose verdict is `held-back`; the remedy it then prints carries
            // --merge, and that is the one that produces a conflict report. Both
            // rounds are executed, because a gate that hands out a remedy which
            // hands out another remedy is only useful if the chain terminates.
            List<String> rounds = new ArrayList<>();
            Map<String, Object> conflictReport = Map.of();
            String conflictRemedy = "";
            int round = 0;
            Map<String, Object> currentBlocker = sharedBlocker;
            while (round < 3 && !currentBlocker.isEmpty()) {
                round++;
                String remedy = String.valueOf(currentBlocker.get("remedy"));
                rounds.add(remedy);
                List<String> argv = argv(remedy);
                if (argv.isEmpty()) break;
                argv.add("--json");
                ProcessRecord run = TicketLifecycleSupport.sm(ctx, "loser-remedy-" + round,
                        ambient, argv.subList(1, argv.size()).toArray(new String[0]));
                Map<String, Object> report = HomeSyncSupport.json(ctx, "loser-remedy-" + round);
                if ("conflicted".equals(HomeSyncSupport.status(report,
                        "skill:" + TicketLifecycleSupport.SHARED))) {
                    conflictReport = report;
                    conflictRemedy = remedy;
                    break;
                }
                ProcessRecord again = TicketLifecycleSupport.sm(ctx, "close-out-loser-" + round,
                        ambient, "home", "close-out", "--home", loserHome.toString(),
                        "--into", projectRaw, "--json");
                currentBlocker = HomeSyncSupport.blocker(
                        HomeSyncSupport.json(ctx, "close-out-loser-" + round),
                        "skill:" + TicketLifecycleSupport.SHARED);
                if (again.exitCode() == 0) break;
            }

            List<String> conflicts = HomeSyncSupport.conflicts(conflictReport,
                    "skill:" + TicketLifecycleSupport.SHARED);
            boolean theSharedUnitIsReportedConflicted = !conflicts.isEmpty();
            // In-unit paths: relative, and actually there.
            List<String> badPaths = new ArrayList<>();
            for (String conflict : conflicts) {
                Path candidate = Path.of(conflict);
                if (candidate.isAbsolute()
                        || !Files.exists(TicketLifecycleSupport
                                .unitDir(loserHome, TicketLifecycleSupport.SHARED)
                                .resolve(conflict))) {
                    badPaths.add(conflict);
                }
            }
            boolean everyConflictIsAnInUnitPath =
                    theSharedUnitIsReportedConflicted && badPaths.isEmpty();

            // --- the remedy's tail --------------------------------------------
            String tail = tailOf(conflictRemedy);
            List<String> tailPaths = pathsIn(tail);
            List<String> badTailPaths = new ArrayList<>();
            for (String p : tailPaths) {
                if (p.startsWith("/") || !conflicts.contains(p)) badTailPaths.add(p);
            }
            boolean theRemedyTailListsTheFilesToResolve =
                    !tailPaths.isEmpty() && badTailPaths.isEmpty();

            // --- no silent clobber --------------------------------------------
            boolean theWinnersBytesAreIntact = clobbered(projectShared, winnerContent).isEmpty();
            // ... and the oracle that says so can say otherwise. A planted
            // clobber — the loser's bytes written over the winner's, which is
            // exactly what a broken reconcile would leave — must be detected by
            // the SAME function, and the tree is restored immediately after.
            String saved = HomeSyncSupport.read(projectShared);
            HomeSyncSupport.write(projectShared, loserContent);
            List<String> plantedDetection = clobbered(projectShared, winnerContent);
            HomeSyncSupport.write(projectShared, saved);
            boolean aSilentClobberIsDetected = !plantedDetection.isEmpty();
            boolean theTreeWasRestored = HomeSyncSupport.read(projectShared).equals(saved);

            // --- the units that are not in conflict still landed ---------------
            boolean bothPrivateUnitsLanded = HomeSyncSupport.read(TicketLifecycleSupport
                        .unitDir(project, TicketLifecycleSupport.UNIT_A)
                        .resolve("references/from-a.md"))
                        .equals(TicketLifecycleSupport.A_ONLY)
                    && HomeSyncSupport.read(TicketLifecycleSupport
                        .unitDir(project, TicketLifecycleSupport.UNIT_B)
                        .resolve("references/from-b.md"))
                        .equals(TicketLifecycleSupport.B_ONLY);

            // --- resolve, and prove the resolution is what clears the gate ------
            // The resolved text goes into BOTH worktree homes and the project.
            //
            // Not decoration, and not a shortcut: MEASURED here, resolving on
            // one side only does not clear the gate. The merge base for each
            // worktree stays its own clone-time baseline, so a file that both
            // the worktree and the project have moved off it stays "changed on
            // both sides" no matter what it was changed TO — including to the
            // union of both edits. The state that clears the gate is the two
            // homes AGREEING on the file, which is what "resolve" has to mean
            // here. The still-live ticket adopting the resolution is a real
            // step in the workflow, and this is where it happens.
            String resolved = winnerContent
                    + (aLanded ? TicketLifecycleSupport.B_SHARED : TicketLifecycleSupport.A_SHARED);
            HomeSyncSupport.write(projectShared, resolved);
            for (Path home : List.of(homeA, homeB)) {
                HomeSyncSupport.write(TicketLifecycleSupport
                        .unitDir(home, TicketLifecycleSupport.SHARED).resolve("SKILL.md"), resolved);
            }
            ProcessRecord afterResolve = TicketLifecycleSupport.sm(ctx, "sync-after-resolve",
                    ambient, "home", "sync", "--from", loserHome.toString(), "--to", projectRaw,
                    "--merge", "--json");
            boolean theResolutionEndsTheConflict = afterResolve.exitCode() == 0
                    && !"conflicted".equals(HomeSyncSupport.status(
                            HomeSyncSupport.json(ctx, "sync-after-resolve"),
                            "skill:" + TicketLifecycleSupport.SHARED));
            // And the OTHER change to the same unit survives the conflict.
            //
            // The nested file under references/ was edited in ticket A's home
            // only (by ticket.lifecycle.first.launch, to close the drift gate).
            // It reaches the project home by a different route depending on who
            // won the race — with A's plain sync when A won, or with A's merge
            // after the resolution when A lost, since the conflicted pass wrote
            // NOTHING including that file. Either way it must be there: a
            // conflict on one file may not cost the unit its other bytes.
            //
            // Stated against the home that made the edit rather than against
            // "the loser", which was this assertion's first spelling and was
            // only true in one of the two orders — caught by a run where the
            // kernel picked the other one.
            boolean theOtherEditToTheConflictedUnitSurvives = HomeSyncSupport.read(
                    TicketLifecycleSupport.unitDir(Path.of(projectRaw),
                            TicketLifecycleSupport.SHARED).resolve("references/page.md"))
                    .equals(HomeSyncSupport.read(TicketLifecycleSupport
                            .unitDir(homeA, TicketLifecycleSupport.SHARED)
                            .resolve("references/page.md")));

            ProcessRecord clearedA = TicketLifecycleSupport.sm(ctx, "close-out-cleared-a", ambient,
                    "home", "close-out", "--home", homeARaw, "--into", projectRaw, "--json");
            ProcessRecord clearedB = TicketLifecycleSupport.sm(ctx, "close-out-cleared-b", ambient,
                    "home", "close-out", "--home", homeBRaw, "--into", projectRaw, "--json");
            boolean bothTicketsCanNowBeToreDown = clearedA.exitCode() == 0
                    && clearedB.exitCode() == 0
                    && HomeSyncSupport.blockerCount(
                            HomeSyncSupport.json(ctx, "close-out-cleared-a")) == 0
                    && HomeSyncSupport.blockerCount(
                            HomeSyncSupport.json(ctx, "close-out-cleared-b")) == 0;
            boolean bothContributionsSurvivedTheResolution =
                    HomeSyncSupport.read(projectShared).contains(
                            TicketLifecycleSupport.A_SHARED.strip())
                    && HomeSyncSupport.read(projectShared).contains(
                            TicketLifecycleSupport.B_SHARED.strip());

            boolean pass = exactlyOneSideLanded && theLoserIsBlockedOnTheSharedUnit
                    && theSharedUnitIsReportedConflicted && everyConflictIsAnInUnitPath
                    && theRemedyTailListsTheFilesToResolve && theWinnersBytesAreIntact
                    && aSilentClobberIsDetected && theTreeWasRestored && bothPrivateUnitsLanded
                    && theResolutionEndsTheConflict
                    && theOtherEditToTheConflictedUnitSurvives
                    && bothTicketsCanNowBeToreDown
                    && bothContributionsSurvivedTheResolution;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.conflict")
                    : NodeResult.fail("ticket.lifecycle.conflict",
                            "loser=" + loserTicket + " blockedExit=" + blocked.exitCode()
                                    + " rounds=" + rounds
                                    + " conflicts=" + conflicts + " badPaths=" + badPaths
                                    + " tail=[" + tail + "] badTailPaths=" + badTailPaths
                                    + " winnerIntact=" + theWinnersBytesAreIntact
                                    + " clobberDetected=" + aSilentClobberIsDetected
                                    + " privateUnits=" + bothPrivateUnitsLanded
                                    + " resolveExit=" + afterResolve.exitCode()
                                    + " clearedExits=" + clearedA.exitCode() + "/"
                                    + clearedB.exitCode()))
                    .process(blocked).process(afterResolve).process(clearedA).process(clearedB)
                    .assertion("exactly_one_tickets_edit_landed_in_the_project_home",
                            exactlyOneSideLanded)
                    .assertion("the_ticket_that_lost_the_race_is_blocked_on_the_shared_unit",
                            theLoserIsBlockedOnTheSharedUnit)
                    .assertion("the_shared_unit_is_reported_conflicted",
                            theSharedUnitIsReportedConflicted)
                    .assertion("every_reported_conflict_is_a_path_inside_the_unit",
                            everyConflictIsAnInUnitPath)
                    .assertion("the_remedys_tail_lists_the_files_to_resolve_and_nothing_else",
                            theRemedyTailListsTheFilesToResolve)
                    .assertion("the_winning_tickets_bytes_were_not_silently_clobbered",
                            theWinnersBytesAreIntact)
                    .assertion("the_clobber_oracle_detects_a_planted_silent_clobber",
                            aSilentClobberIsDetected)
                    .assertion("the_planted_clobber_was_reverted", theTreeWasRestored)
                    .assertion("the_units_that_are_not_in_conflict_landed_clean",
                            bothPrivateUnitsLanded)
                    .assertion("resolving_the_file_on_both_sides_ends_the_conflict",
                            theResolutionEndsTheConflict)
                    .assertion("the_other_edit_to_the_conflicted_unit_survives_the_conflict",
                            theOtherEditToTheConflictedUnitSurvives)
                    .assertion("both_worktrees_can_be_torn_down_once_the_conflict_is_resolved",
                            bothTicketsCanNowBeToreDown)
                    .assertion("both_tickets_contributions_survived_the_resolution",
                            bothContributionsSurvivedTheResolution)
                    .metric("remedyRounds", rounds.size())
                    .metric("conflictsReported", conflicts.size())
                    .log("loser was " + loserTicket + "; remedies: " + rounds)
                    .publish("resolvedShared", resolved);
        });
    }

    /**
     * The oracle for "was the winner's work silently overwritten".
     *
     * <p>One function, used for the real measurement AND for the planted
     * clobber. An oracle re-spelled for its own sensitivity test proves nothing
     * about the spelling that matters.
     */
    private static List<String> clobbered(Path landedFile, String expected) {
        String actual = HomeSyncSupport.read(landedFile);
        return actual.equals(expected)
                ? List.of()
                : List.of("expected " + expected.length() + " bytes, found " + actual.length());
    }

    /** Everything after the two-space run that separates the command from its prose. */
    private static String tailOf(String remedy) {
        if (remedy == null) return "";
        String[] parts = remedy.split(" {2}", 2);
        return parts.length < 2 ? "" : parts[1].strip();
    }

    /** The file paths named in a remedy tail such as {@code (then resolve: a.md, b/c.md)}. */
    private static List<String> pathsIn(String tail) {
        List<String> out = new ArrayList<>();
        int colon = tail.indexOf(':');
        if (colon < 0) return out;
        String list = tail.substring(colon + 1).replace(")", "").strip();
        for (String token : list.split(",")) {
            String path = token.strip();
            if (!path.isEmpty()) out.add(path);
        }
        return out;
    }

    private static List<String> argv(String remedy) {
        if (remedy == null || remedy.isBlank() || "null".equals(remedy)) return new ArrayList<>();
        String command = remedy.split(" {2}")[0].trim();
        return new ArrayList<>(List.of(command.split("\\s+")));
    }
}
