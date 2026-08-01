///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES TicketLifecycleSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>Step 6 — teardown.</b> The worktrees may go, and only now, and only
 * through the gate.
 *
 * <h2>"A bare git worktree remove is not what happened"</h2>
 *
 * <p>That is a claim about a negative, and the only honest way to make it is to
 * show the gate standing in the way of the thing it is supposed to stand in the
 * way of. So before removing anything, this node plants a fresh edit in
 * worktree A's home — work that exists nowhere else, exactly as an agent's does
 * — and runs {@code close-change.sh} again. It must refuse (exit 4), the
 * worktree must still be there, and the home must still hold the planted bytes.
 * A script that removed anyway would leave that file nowhere.
 *
 * <p>Only after clearing that does the removal run, and then the assertions are
 * about the filesystem: the worktree directory is gone, the home inside it is
 * gone with it, and {@code git worktree list} no longer names it. The last one
 * matters on its own — a removal that deleted the directory and left the
 * registration behind is the state where the NEXT {@code new-change.sh} for the
 * same ticket fails with "worktree path already exists".
 *
 * <h2>And the dry run really is dry</h2>
 *
 * <p>{@code --dry-run} is the safest gesture an operator has and the one they
 * will reach for while standing inside the worktree. It has to run the gate,
 * report what it would do, and remove nothing — asserted by the directory still
 * being there afterwards, not by reading the word "dry" in the output.
 */
public class TicketLifecycleTeardown {

    static final NodeSpec SPEC = NodeSpec.of("ticket.lifecycle.teardown")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("ticket.lifecycle.conflict")
            .tags("ticket-lifecycle", "close-change", "teardown")
            .timeout("900s");

    private static final int REFUSED_BY_THE_GATE = 4;

    private static final String PLANTED =
            "work that exists in this worktree home and nowhere else\n";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String checkoutRaw = ctx.get("ticket.lifecycle.fixture.built", "checkout").orElse(null);
            String projectRaw = ctx.get("ticket.lifecycle.fixture.built", "projectHome")
                    .orElse(null);
            String scriptsRaw = ctx.get("ticket.lifecycle.fixture.built", "scriptsDir")
                    .orElse(null);
            String ambient = ctx.get("ticket.lifecycle.fixture.built", "ambientHome").orElse(null);
            String homeARaw = ctx.get("ticket.lifecycle.provisioned", "homeA").orElse(null);
            String worktreeARaw = ctx.get("ticket.lifecycle.provisioned", "worktreeA").orElse(null);
            String worktreeBRaw = ctx.get("ticket.lifecycle.provisioned", "worktreeB").orElse(null);
            if (checkoutRaw == null || projectRaw == null || scriptsRaw == null || ambient == null
                    || homeARaw == null || worktreeARaw == null || worktreeBRaw == null) {
                return NodeResult.fail("ticket.lifecycle.teardown", "missing upstream context");
            }
            Path checkout = Path.of(checkoutRaw);
            Path closeChange = Path.of(scriptsRaw).resolve("close-change.sh");
            Path homeA = Path.of(homeARaw);
            Path worktreeA = Path.of(worktreeARaw);
            Path worktreeB = Path.of(worktreeBRaw);

            // --- the dry run removes nothing ---------------------------------
            ProcessRecord dry = TicketLifecycleSupport.script(ctx, "close-change-dry-run",
                    checkout, closeChange, ambient,
                    TicketLifecycleSupport.TICKET_A, "--dry-run");
            String dryText = HomeSyncSupport.log(ctx, "close-change-dry-run");
            boolean theDryRunRanTheGateAndRemovedNothing = dry.exitCode() == 0
                    && dryText.contains("Gate: does this worktree still hold work?")
                    && dryText.contains("would run:")
                    && Files.isDirectory(worktreeA)
                    && Files.isDirectory(homeA);

            // --- the gate stands in the way of a real removal ----------------
            Path planted = TicketLifecycleSupport
                    .unitDir(homeA, TicketLifecycleSupport.UNIT_A).resolve("references/planted.md");
            HomeSyncSupport.write(planted, PLANTED);
            ProcessRecord refused = TicketLifecycleSupport.script(ctx, "close-change-refused",
                    checkout, closeChange, ambient, TicketLifecycleSupport.TICKET_A);
            boolean theGateRefusesWhileWorkRemains = refused.exitCode() == REFUSED_BY_THE_GATE;
            boolean nothingWasRemovedByTheRefusal = Files.isDirectory(worktreeA)
                    && Files.isDirectory(homeA)
                    && HomeSyncSupport.read(planted).equals(PLANTED);
            boolean theRefusalNamesTheUnitAtRisk = HomeSyncSupport
                    .log(ctx, "close-change-refused")
                    .contains("BLOCKED  skill:" + TicketLifecycleSupport.UNIT_A);

            // --- clear it the way the gate said, then remove ------------------
            ProcessRecord sync = TicketLifecycleSupport.sm(ctx, "sync-planted-work", ambient,
                    "home", "sync", "--from", homeARaw, "--to", projectRaw, "--merge", "--json");
            boolean thePlantedWorkReachedTheProjectHome = sync.exitCode() == 0
                    && HomeSyncSupport.read(TicketLifecycleSupport
                            .unitDir(Path.of(projectRaw), TicketLifecycleSupport.UNIT_A)
                            .resolve("references/planted.md")).equals(PLANTED);

            ProcessRecord removeA = TicketLifecycleSupport.script(ctx, "close-change-remove-a",
                    checkout, closeChange, ambient, TicketLifecycleSupport.TICKET_A);
            ProcessRecord removeB = TicketLifecycleSupport.script(ctx, "close-change-remove-b",
                    checkout, closeChange, ambient, TicketLifecycleSupport.TICKET_B);
            boolean bothRemovalsSucceeded = removeA.exitCode() == 0 && removeB.exitCode() == 0;
            boolean theGateRanBeforeEachRemoval =
                    HomeSyncSupport.log(ctx, "close-change-remove-a").contains("gate:      clean")
                    && HomeSyncSupport.log(ctx, "close-change-remove-b")
                            .contains("gate:      clean");
            boolean bothWorktreesAreGone =
                    !Files.exists(worktreeA) && !Files.exists(worktreeB);
            boolean bothHomesWentWithThem =
                    !Files.exists(homeA) && !Files.exists(worktreeB.resolve(".skill-manager"));
            String registered = HomeSyncSupport
                    .git(checkout, "worktree", "list", "--porcelain").trimmed();
            boolean gitNoLongerRegistersThem = !registered.contains(worktreeARaw)
                    && !registered.contains(worktreeBRaw);

            boolean pass = theDryRunRanTheGateAndRemovedNothing && theGateRefusesWhileWorkRemains
                    && nothingWasRemovedByTheRefusal && theRefusalNamesTheUnitAtRisk
                    && thePlantedWorkReachedTheProjectHome && bothRemovalsSucceeded
                    && theGateRanBeforeEachRemoval && bothWorktreesAreGone
                    && bothHomesWentWithThem && gitNoLongerRegistersThem;

            return (pass
                    ? NodeResult.pass("ticket.lifecycle.teardown")
                    : NodeResult.fail("ticket.lifecycle.teardown",
                            "dry=" + dry.exitCode() + " refused=" + refused.exitCode()
                                    + " nothingRemoved=" + nothingWasRemovedByTheRefusal
                                    + " namesUnit=" + theRefusalNamesTheUnitAtRisk
                                    + " syncExit=" + sync.exitCode()
                                    + " plantedReached=" + thePlantedWorkReachedTheProjectHome
                                    + " removes=" + removeA.exitCode() + "/" + removeB.exitCode()
                                    + " gateRan=" + theGateRanBeforeEachRemoval
                                    + " worktreesGone=" + bothWorktreesAreGone
                                    + " homesGone=" + bothHomesWentWithThem
                                    + " registered=[" + registered + "]"))
                    .process(dry).process(refused).process(sync).process(removeA).process(removeB)
                    .assertion("a_dry_run_runs_the_gate_and_removes_nothing",
                            theDryRunRanTheGateAndRemovedNothing)
                    .assertion("the_gate_refuses_while_the_home_still_holds_work",
                            theGateRefusesWhileWorkRemains)
                    .assertion("a_refused_teardown_destroys_nothing", nothingWasRemovedByTheRefusal)
                    .assertion("the_refusal_names_the_unit_whose_work_would_be_lost",
                            theRefusalNamesTheUnitAtRisk)
                    .assertion("clearing_the_blocker_moves_the_work_to_the_project_home",
                            thePlantedWorkReachedTheProjectHome)
                    .assertion("both_worktrees_are_removed_once_the_gate_is_clear",
                            bothRemovalsSucceeded)
                    .assertion("the_gate_ran_before_each_removal", theGateRanBeforeEachRemoval)
                    .assertion("both_worktree_directories_are_gone", bothWorktreesAreGone)
                    .assertion("each_worktrees_home_went_with_it", bothHomesWentWithThem)
                    .assertion("git_no_longer_registers_either_worktree", gitNoLongerRegistersThem);
        });
    }
}
