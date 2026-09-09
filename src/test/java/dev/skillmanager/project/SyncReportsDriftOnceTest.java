package dev.skillmanager.project;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.bindings.ChildHomeTally;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A sync says a thing once.
 *
 * <h2>The defect, reported from a real root home</h2>
 *
 * <p>{@link ProjectSyncUseCase} measures drift on the home it RUNS IN, and the
 * unit-sync fan-out calls it once per project claiming the unit. On a root home
 * with four registered projects, one {@code skill-manager sync} printed the
 * identical block
 *
 * <pre>
 * ! this sync changed 15 unit(s) in ~/.skill-manager — a launch will refuse …
 * !   modified  skill:debugging  dd42a316  (1 file)
 * !   … fifteen more rows …
 * !   15 units, 622 files changed
 * </pre>
 *
 * <p><b>four times</b>, each naming the same home and asking for the same
 * acknowledgement — about seventy lines carrying one fact. The operator read it
 * as the command looping while it waited for an ack.
 *
 * <p>Alongside it, twenty-five {@code child home … left as-is} warnings, which
 * are the mechanism WORKING (the alternative is deleting an agent's edits) and
 * so are not something to act on at all.
 *
 * <p>What is asserted here is the contract that keeps both from coming back:
 * the fan-out reconciles QUIETLY, and the per-item chatter is counted rather
 * than narrated. The rows are not lost — {@code Log.detail} records every one
 * in the run log and prints them under {@code --verbose}.
 */
public final class SyncReportsDriftOnceTest {

    public static int run() throws Exception {
        return Tests.suite("SyncReportsDriftOnceTest")

                .test("the fan-out's options do NOT report drift", () -> {
                    // The fan-out calls this one; it must stay silent, because
                    // the drift it would print is the parent home's and it is
                    // the same drift on every iteration.
                    assertFalse(ProjectSyncUseCase.Options.reconcileQuietly().reportDrift(),
                            "reconcileQuietly must suppress the per-project drift report — "
                                    + "this is the whole fix for the four-times block");
                })

                .test("every OTHER caller still reports it", () -> {
                    // `project sync` and `project resolve` have ONE project as
                    // their subject, so printing the home's drift there is
                    // correct and is not what was duplicated. A fix that
                    // silenced those would trade a noisy sync for a silent one.
                    assertTrue(ProjectSyncUseCase.Options.defaults().reportDrift(),
                            "defaults() must still report");
                    assertTrue(ProjectSyncUseCase.Options.reconcileOnly().reportDrift(),
                            "reconcileOnly() must still report");
                    assertTrue(ProjectSyncUseCase.Options.rebuildOnly().reportDrift(),
                            "rebuildOnly() must still report");
                })

                .test("the child-home tally counts instead of narrating", () -> {
                    ChildHomeTally.reset();
                    assertTrue(ChildHomeTally.isEmpty(), "a fresh invocation counts nothing");

                    ChildHomeTally.heldBack("/a/.skill-manager/skills/one");
                    ChildHomeTally.heldBack("/a/.skill-manager/skills/two");
                    ChildHomeTally.selfProvisionedCli("/b/.skill-manager/bin/cli/x");
                    ChildHomeTally.keptAfterUndeclared("/b/.skill-manager/skills/three");

                    assertFalse(ChildHomeTally.isEmpty(),
                            "four events must be visible to the summary line");
                })

                .test("reset really resets — tests share a JVM", () -> {
                    // The CLI is one invocation per process and would not need
                    // this; the suite is not, and a count leaking between cases
                    // would attribute one case's child homes to another.
                    ChildHomeTally.heldBack("/c/.skill-manager/skills/leak");
                    ChildHomeTally.reset();
                    assertTrue(ChildHomeTally.isEmpty(), "reset must clear the tally");
                })

                .test("the drift fact carries the ack command, not just the count", () -> {
                    // The one line that reaches the console has to be
                    // ACTIONABLE on its own: a count with no command sends the
                    // reader to the log to find out what to type, which is the
                    // trip the summary exists to save.
                    var fact = new dev.skillmanager.effects.ContextFact.HomeDriftPending(
                            "/home/.skill-manager", 15, 622,
                            "skill-manager home drift --ack --home /home/.skill-manager",
                            java.util.List.of("modified skill:one", "modified skill:two"));
                    assertEquals(15, fact.units(), "unit count");
                    assertEquals(622, fact.files(), "file count");
                    assertTrue(fact.ackCommand().contains("--ack"),
                            "the console line must name the command that clears the gate");
                    assertEquals(2, fact.rows().size(),
                            "the rows travel with the fact for the log, not for the console");
                })

                .runAll();
    }
}
