///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 14 — the whole worktree lifecycle, driven through {@code wt}, in the
 * order the hand walk took it.</b>
 *
 * <p>An ACTION node that runs the sequence and publishes every log; the
 * assertions over the contract lines live in
 * {@code onboarding.wt.contract.lines}. The sequence itself is the fixture for
 * three separate properties that only exist at specific points in it:
 *
 * <ol>
 *   <li><b>{@code wt new} with a dirty tree refuses</b> (exit 1,
 *       {@code FAILED}/{@code FIX}). The tree is dirty for the reason
 *       {@code onboarding.leaves.work.tree.clean} measured — the untracked
 *       {@code .claude.json} the documented ignore rules do not cover. This
 *       node does NOT pre-clean: whether the refusal happens is the finding,
 *       and it records which of the two it saw.</li>
 *   <li><b>The clone a worktree gets has empty agent homes.</b> The same
 *       defect {@code onboarding.projections.materialized} asserts for the
 *       project home, at the point where it bites hardest: every
 *       {@code wt new} launches an agent that sees zero skills, and nothing on
 *       this path prints the remedy.</li>
 *   <li><b>The close-out gate blocks while the worktree holds work.</b> Built
 *       deliberately: install a unit into the worktree home that the project
 *       home does not have, then close. That is the smallest state in which
 *       the gate has something to protect, and reaching it is asserted
 *       (exit 4, the blocker naming the exact unit) before {@code --force} is
 *       run — otherwise the {@code --force} case would be exercising the
 *       pass-through path and the contract assertion downstream would be about
 *       nothing.</li>
 * </ol>
 *
 * <p>Between the blocked close and the forced one, nothing else runs: the gate
 * state is fragile and a stray sync would clear it.
 *
 * <h2>Where the worktree PATH comes from, and why it is not the summary line</h2>
 *
 * <p>{@code wt new} succeeds in ONE line now — {@code created worktree <path>}
 * — and the keyed {@code WORKTREE} / {@code BRANCH} / {@code LAUNCH} /
 * {@code IF-EXIT-8} / {@code CLOSE} set moved to {@code wt info <TICKET>}. This
 * node reads the path from {@code wt info}, which is what {@code wt}'s own
 * header tells a programmatic caller to do: the one-line summary is prose, and
 * a node that scraped it would be the caller that "never could be relied on".
 *
 * <p>That is a change of source, so it is paid for downstream rather than
 * assumed: {@code onboarding.wt.contract.lines} asserts the summary is exactly
 * one line AND that the path on it is the same path {@code wt info} names. If
 * sourcing the path from a second command had let the two drift, that assertion
 * is where it shows up — the budget claim is not left resting on the fact that
 * nothing here reads the summary any more.
 *
 * <p>{@code wt info} is also run once MORE, after the forced close, and must
 * refuse over a worktree that is gone while leaving it gone. The verb's whole
 * claim is that it "creates and removes nothing", and a read-only verb that
 * silently re-created the worktree it was asked about would otherwise satisfy
 * every other assertion here.
 */
public class OnboardingWorktreeLifecycle {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.worktree.lifecycle")
            .kind(NodeSpec.Kind.ACTION)
            .dependsOn("onboarding.cli.projection.idempotent")
            .tags("onboarding", "worktree", "script")
            .timeout("1800s")
            .output("dirtyNewLog", "string")
            .output("newLog", "string")
            .output("infoLog", "string")
            .output("blockedCloseLog", "string")
            .output("forcedCloseLog", "string")
            .output("dirtyNewExit", "string")
            .output("newExit", "string")
            .output("infoExit", "string")
            .output("blockedCloseExit", "string")
            .output("forcedCloseExit", "string")
            .output("worktree", "string")
            .output("worktreeServable", "string")
            .output("worktreeStore", "string")
            .output("blockerUnit", "string")
            .output("closableDryRunExit", "string");

    static final String TICKET = "OB-1";

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            Path sources = path(ctx, "onboarding.fixture.built", "sourcesDir");
            Path ambient = path(ctx, "onboarding.fixture.built", "ambient");
            Path scriptsDir = path(ctx, "onboarding.fixture.built", "scriptsDir");
            Path home = path(ctx, "onboarding.bootstrapped", "projectHome");
            if (proj == null || home == null || scriptsDir == null || ambient == null
                    || sources == null) {
                return NodeResult.fail("onboarding.worktree.lifecycle",
                        "missing upstream context");
            }
            Path wt = scriptsDir.resolve("wt");

            // --- 1. wt new against whatever state the walk left the tree in ------
            String statusBefore = HomeSyncSupport.git(proj, "status", "--porcelain").trimmed();
            ProcessRecord dirtyNew = OnboardingSupport.script(ctx, "wt-new-dirty", proj, wt,
                    ambient, "new", TICKET);
            String dirtyNewLog = OnboardingSupport.log(ctx, dirtyNew);
            boolean theTreeWasDirtyWhenTheTicketWasOpened = !statusBefore.isEmpty();
            boolean theRefusalMatchedTheTreeState =
                    theTreeWasDirtyWhenTheTicketWasOpened == (dirtyNew.exitCode() != 0);

            // --- 2. clean the tree the way the workaround does, then wt new -------
            if (dirtyNew.exitCode() != 0 && theTreeWasDirtyWhenTheTicketWasOpened) {
                // The documented ignore rules plus the rule they are missing.
                Files.writeString(proj.resolve(".gitignore"),
                        String.join("\n", OnboardingSupport.DOCUMENTED_IGNORES)
                                + "\n/.claude.json\n");
                List<String> failures = new ArrayList<>();
                OnboardingSupport.git(failures, proj, "add", "-A");
                OnboardingSupport.git(failures, proj, "commit", "-qm",
                        "ignore the file the documented rules do not cover");
            }
            ProcessRecord newRun = dirtyNew.exitCode() == 0 ? dirtyNew
                    : OnboardingSupport.script(ctx, "wt-new", proj, wt, ambient, "new", TICKET);
            String newLog = OnboardingSupport.log(ctx, newRun);

            // --- 2b. where the worktree IS ----------------------------------------
            //
            // From `wt info`, not from the summary line `wt new` printed. The
            // success path is one line of prose now, and `wt`'s own header says
            // a caller that parsed it "never could be relied on"; the keyed
            // contract moved to this verb and this is the caller reading it.
            // Both texts are published, and the contract node cross-checks that
            // the summary names the same path — so the switch of source cannot
            // quietly become the reason a drift went unnoticed.
            ProcessRecord info = OnboardingSupport.script(ctx, "wt-info", proj, wt, ambient,
                    "info", TICKET);
            String infoLog = OnboardingSupport.log(ctx, info);
            Path worktree = contractValue(infoLog, "WORKTREE");
            boolean theWorktreeWasCreated = newRun.exitCode() == 0 && info.exitCode() == 0
                    && worktree != null && Files.isDirectory(worktree);

            // --- 3. what an agent launched in that worktree would see --------------
            int wtServable = 0;
            int wtStore = 0;
            Path wtHome = worktree == null ? null : worktree.resolve(".skill-manager");
            if (wtHome != null && Files.isDirectory(wtHome)) {
                wtStore = OnboardingSupport.storeUnits(wtHome).size();
                wtServable = OnboardingSupport.servableUnits(wtHome, worktree).size();
            }

            // --- 3b. a freshly projected worktree home is still CLOSABLE ------------
            //
            // THE COMPANION WITHOUT WHICH THE GATE ASSERTIONS BELOW MEAN
            // NOTHING. This node deliberately makes the worktree unclosable in
            // the next step, so "the gate refused" cannot on its own tell
            // "refused because of the unit I planted" from "refused because
            // creating the worktree already dirtied it". The dry run splits
            // them: it is taken BEFORE anything is planted, and it must come
            // back CLEAN.
            //
            // It is also the standing guard on how the missing projection gets
            // repaired. `home close-out` counts skills/<u>/.git/index as unit
            // work, and that file is stat-dependent rather than content — so a
            // repair that touches unit content at all (`sync --skip-mcp` on a
            // worktree home does) leaves `BLOCKED skill:<u> (merged) - 1
            // file(s) come from the source` and a worktree nobody can tear
            // down. A ledger-first repair, materializing projection records the
            // clone already carries, does not. This assertion is what keeps the
            // difference visible rather than a matter of opinion.
            ProcessRecord closable = OnboardingSupport.script(ctx, "wt-close-dry-run", proj,
                    wt, ambient, "close", TICKET, "--dry-run");
            String closableLog = OnboardingSupport.log(ctx, closable);
            boolean projectingAWorktreeHomeDoesNotMakeItUnclosable =
                    theWorktreeWasCreated && closable.exitCode() == 0
                            && OnboardingSupport.hasContractKey(closableLog, "CLEAN");

            // --- 4. put work in the worktree home the project home lacks -----------
            //
            // The smallest state in which the close-out gate has something to
            // protect. Without it, `wt close` takes the pass-through path and
            // the contract assertion downstream measures nothing.
            String blocker = OnboardingSupport.BROKEN;
            ProcessRecord installIntoWorktree = wtHome == null ? null
                    : OnboardingSupport.sm(ctx, "wt-install-blocker", wtHome, worktree,
                            "install", sources.resolve(blocker).toString(), "--yes");
            boolean theWorktreeHoldsAUnitTheProjectHomeDoesNot =
                    wtHome != null && OnboardingSupport.storeUnits(wtHome).contains(blocker)
                            && !OnboardingSupport.storeUnits(home).contains(blocker);

            // --- 5. the blocked close ------------------------------------------------
            ProcessRecord blockedClose = OnboardingSupport.script(ctx, "wt-close-blocked", proj,
                    wt, ambient, "close", TICKET);
            String blockedLog = OnboardingSupport.log(ctx, blockedClose);
            boolean theGateRefusedTheClose = blockedClose.exitCode() != 0;
            // FOLLOW THE LOG THE REFUSAL NAMES. `wt` no longer dumps the child's
            // stderr on a refusal — it prints three lines and puts the reasoning
            // in the file its `log:` line names. The blocker is in that file, so
            // a search of the console alone reports "the gate did not name the
            // unit" about a gate that named it perfectly. plusNamedLog THROWS if
            // the named file is missing rather than returning "", because this
            // assertion phrased over an empty string would read as a clean miss.
            boolean theGateNamedTheExactUnit =
                    OnboardingSupport.plusNamedLog(blockedLog).contains(blocker);

            // --- 6. the forced close --------------------------------------------------
            ProcessRecord forcedClose = OnboardingSupport.script(ctx, "wt-close-forced", proj,
                    wt, ambient, "close", TICKET, "--force");
            String forcedLog = OnboardingSupport.log(ctx, forcedClose);
            boolean theForcedCloseSucceeded = forcedClose.exitCode() == 0;

            // --- 7. `wt info` over a worktree that is gone --------------------------
            //
            // The verb's whole claim is that it "creates and removes nothing",
            // and this node now DEPENDS on it: the path every assertion above
            // hangs off came out of it. A read-only verb that quietly ran
            // new-change.sh's creating path would satisfy every one of them, so
            // the claim is measured where it is falsifiable — asked about a
            // worktree that no longer exists, it must refuse and leave it
            // missing. The gone-ness check is taken AFTER this run for the same
            // reason: it then covers "the forced close removed it" and "info did
            // not put it back" in one statement.
            ProcessRecord infoAfterClose = OnboardingSupport.script(ctx, "wt-info-after-close",
                    proj, wt, ambient, "info", TICKET);
            boolean theWorktreeIsGone = worktree == null || !Files.exists(worktree);
            boolean wtInfoRefusedOverAWorktreeItDidNotCreate =
                    infoAfterClose.exitCode() != 0 && theWorktreeIsGone;

            // The worktree half of the projection defect. Asserted rather than
            // logged: this is where it bites hardest — every `wt new` launches
            // an agent bound to a home whose store is full and whose agent
            // directories are empty, and nothing on this path prints the
            // remedy. The companion is the store count in the same breath: "0
            // servable" only means something alongside "N in the store", or a
            // worktree whose clone failed entirely would satisfy it.
            boolean theWorktreeHomeHasAStore = wtStore > 0;
            boolean everyUnitInTheWorktreeStoreIsServable =
                    theWorktreeHomeHasAStore && wtServable == wtStore;

            boolean pass = theRefusalMatchedTheTreeState && theWorktreeWasCreated
                    && theWorktreeHoldsAUnitTheProjectHomeDoesNot
                    && theWorktreeHomeHasAStore && everyUnitInTheWorktreeStoreIsServable
                    && projectingAWorktreeHomeDoesNotMakeItUnclosable
                    && theGateRefusedTheClose && theGateNamedTheExactUnit
                    && theForcedCloseSucceeded && theWorktreeIsGone
                    && wtInfoRefusedOverAWorktreeItDidNotCreate;

            NodeResult result = pass
                    ? NodeResult.pass("onboarding.worktree.lifecycle")
                    : NodeResult.fail("onboarding.worktree.lifecycle",
                            "treeDirtyBefore=" + theTreeWasDirtyWhenTheTicketWasOpened
                                    + " dirtyNewExit=" + dirtyNew.exitCode()
                                    + " newExit=" + newRun.exitCode()
                                    + " infoExit=" + info.exitCode()
                                    + " worktree=" + worktree
                                    + " closableDryRunExit=" + closable.exitCode()
                                    + " blockerHeld=" + theWorktreeHoldsAUnitTheProjectHomeDoesNot
                                    + " blockedCloseExit=" + blockedClose.exitCode()
                                    + " gateNamedUnit=" + theGateNamedTheExactUnit
                                    + " forcedCloseExit=" + forcedClose.exitCode()
                                    + " worktreeGone=" + theWorktreeIsGone
                                    + " infoAfterCloseExit=" + infoAfterClose.exitCode());
            result = result.process(dirtyNew).process(info).process(closable)
                    .process(infoAfterClose);
            if (newRun != dirtyNew) result = result.process(newRun);
            if (installIntoWorktree != null) result = result.process(installIntoWorktree);
            return result.process(blockedClose).process(forcedClose)
                    .assertion("the_wt_new_refusal_matched_the_work_tree_state",
                            theRefusalMatchedTheTreeState)
                    .assertion("a_worktree_was_created", theWorktreeWasCreated)
                    .assertion("the_worktree_home_holds_a_unit_the_project_home_does_not",
                            theWorktreeHoldsAUnitTheProjectHomeDoesNot)
                    .assertion("the_worktree_home_has_a_populated_store",
                            theWorktreeHomeHasAStore)
                    .assertion("every_unit_in_the_worktree_store_is_servable_to_an_agent",
                            everyUnitInTheWorktreeStoreIsServable)
                    .assertion("projecting_a_worktree_home_does_not_make_it_unclosable",
                            projectingAWorktreeHomeDoesNotMakeItUnclosable)
                    .assertion("the_close_out_gate_refused_while_the_worktree_held_work",
                            theGateRefusedTheClose)
                    .assertion("the_gate_named_the_exact_unit_that_blocked_it",
                            theGateNamedTheExactUnit)
                    .assertion("the_forced_close_succeeded", theForcedCloseSucceeded)
                    .assertion("the_worktree_directory_is_gone_afterwards", theWorktreeIsGone)
                    .assertion("wt_info_refused_over_a_worktree_it_did_not_create",
                            wtInfoRefusedOverAWorktreeItDidNotCreate)
                    .metric("worktreeStoreUnits", wtStore)
                    .metric("worktreeServableUnits", wtServable)
                    .metric("blockedCloseExit", blockedClose.exitCode())
                    .log("tree state when the ticket was opened: "
                            + (statusBefore.isEmpty() ? "clean" : statusBefore))
                    .log("an agent launched in the worktree would see " + wtServable
                            + " of the " + wtStore + " units its store holds")
                    .publish("dirtyNewLog", logPath(ctx, dirtyNew))
                    .publish("newLog", logPath(ctx, newRun))
                    .publish("infoLog", logPath(ctx, info))
                    .publish("blockedCloseLog", logPath(ctx, blockedClose))
                    .publish("forcedCloseLog", logPath(ctx, forcedClose))
                    .publish("dirtyNewExit", String.valueOf(dirtyNew.exitCode()))
                    .publish("newExit", String.valueOf(newRun.exitCode()))
                    .publish("infoExit", String.valueOf(info.exitCode()))
                    .publish("blockedCloseExit", String.valueOf(blockedClose.exitCode()))
                    .publish("forcedCloseExit", String.valueOf(forcedClose.exitCode()))
                    .publish("worktree", worktree == null ? "" : worktree.toString())
                    .publish("worktreeServable", String.valueOf(wtServable))
                    .publish("worktreeStore", String.valueOf(wtStore))
                    .publish("blockerUnit", blocker)
                    .publish("closableDryRunExit", String.valueOf(closable.exitCode()));
        });
    }

    /** The value of an anchored {@code KEY   <value>} contract line. */
    static Path contractValue(String log, String key) {
        for (String raw : log.split("\n", -1)) {
            if (!raw.startsWith(key)) continue;
            String rest = raw.substring(key.length());
            if (rest.isEmpty() || !Character.isWhitespace(rest.charAt(0))) continue;
            String value = rest.strip();
            if (!value.isEmpty()) return Path.of(value.split("\\s+")[0]);
        }
        return null;
    }

    private static String logPath(NodeContext ctx, ProcessRecord p) {
        return p.logPath() == null ? "" : ctx.reportDir().resolve(p.logPath()).toString();
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
