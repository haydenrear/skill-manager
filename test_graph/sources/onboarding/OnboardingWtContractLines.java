///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 15 — {@code wt} prints the success contract or the failure contract,
 * never both, and the success one costs ONE line.</b>
 *
 * <h2>The defect this node was commissioned against</h2>
 *
 * <p>{@code wt --help} states the contract: "either, on failure FAILED / FIX".
 * {@code wt close <T> --force} on a worktree the gate had refused emitted BOTH —
 * exit 0, with {@code FAILED} and {@code FIX} from the gate followed by
 * {@code CLOSED}, {@code BRANCH} and {@code DELETE} from the successful forced
 * teardown. A caller parsing stdout saw {@code FAILED} on a run that succeeded,
 * which is the one thing the contract exists to prevent.
 *
 * <h2>What the contract is NOW, and what this node had to become</h2>
 *
 * <p>{@code wt} was reshaped around what a run COSTS. A successful {@code new}
 * or {@code close} prints one line of prose — {@code created worktree <path>},
 * {@code closed worktree <path> (branch <b> kept)} — and the keyed
 * {@code WORKTREE} / {@code BRANCH} / {@code LAUNCH} / {@code IF-EXIT-8} /
 * {@code CLOSE} set moved to {@code wt info <TICKET>}, which creates and removes
 * nothing. A refusal prints three short lines — {@code error …} / {@code fix: …}
 * / {@code log: …} — and puts the reasoning in the file the third names instead
 * of dumping it. {@code --verbose}, {@code close --dry-run} and
 * {@code close --force} still pass the underlying script's own contract
 * through, because none of them is a run whose answer is one line.
 *
 * <p>So the failure key set is {@code error} / {@code fix:} / {@code log:}
 * rather than {@code FAILED} / {@code FIX} — still anchored, still matched by
 * {@link OnboardingSupport#hasContractKey}'s {@code ^KEY\s}, which is exactly
 * why the key set could be replaced without a second matcher.
 *
 * <h2>Assertions</h2>
 *
 * <ul>
 *   <li>{@code new} success ⇒ EXACTLY ONE line, matching
 *       {@code created worktree <path>}, and none of the failure keys.</li>
 *   <li>{@code wt info} ⇒ {@code WORKTREE}, {@code BRANCH}, {@code LAUNCH},
 *       {@code IF-EXIT-8}, {@code CLOSE}, and no failure keys.</li>
 *   <li>The path on the one-line summary is the path {@code wt info} names.</li>
 *   <li>{@code close --force} success ⇒ {@code CLOSED}, {@code BRANCH},
 *       {@code DELETE}, and no failure keys.</li>
 *   <li>Failure ⇒ {@code error} and {@code fix:} and {@code log:}, none of the
 *       success keys, and at most three lines.</li>
 *   <li>The exit code agrees with which key set was printed.</li>
 * </ul>
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>Only exercising the clean paths,</b> where the two key sets never
 *       co-occur and the assertion cannot fail.
 *       <br><b>Companion — mandatory:</b> the {@code --force} case must be in
 *       the run, and this node asserts it REACHED the forced path — exit 0 on
 *       the forced run AND a genuine blocker on the preceding unforced one
 *       (exit non-zero, the blocker naming the exact unit).</li>
 *   <li><b>The blocker moved out of the console.</b> A refusal names a log
 *       rather than printing the reasoning, so {@code blockedLog.contains(unit)}
 *       is now a search of the half the detail was moved OUT of — and it fails
 *       claiming the gate said nothing while the gate said everything. The
 *       companion is {@link OnboardingSupport#plusNamedLog}, which follows the
 *       {@code log:} footer and THROWS rather than returning {@code ""} when the
 *       file it names is gone.</li>
 *   <li><b>Sourcing the path from a second command.</b>
 *       {@code onboarding.worktree.lifecycle} reads the worktree path from
 *       {@code wt info} now, because the summary is prose. That would quietly
 *       retire the whole point of this node — that the success path is ONE line
 *       — if nothing asserted the shape of that line any more.
 *       <br><b>Companion:</b> the one-line property is asserted HERE, directly
 *       and by count, and the path on it is cross-checked against
 *       {@code wt info}'s {@code WORKTREE}. The two commands agreeing is what
 *       makes reading from the second one safe; if they ever disagree, this is
 *       the assertion that says so.</li>
 *   <li><b>Substring matching.</b> {@code CLOSE} is a substring of
 *       {@code CLOSED}, so a contains-check literally cannot tell the
 *       {@code info} contract from the {@code close} one.
 *       <br><b>Companion:</b> every key is matched anchored, as
 *       {@code ^KEY\s}, and this node proves the anchoring discriminates by
 *       asserting that a {@code close} log carrying {@code CLOSED} does NOT
 *       satisfy the {@code CLOSE} key.</li>
 * </ol>
 */
public class OnboardingWtContractLines {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.wt.contract.lines")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.worktree.lifecycle")
            .tags("onboarding", "worktree", "contract")
            .timeout("300s");

    /** The full key set — printed by {@code wt info}, and by {@code --verbose}. */
    static final List<String> INFO_CONTRACT =
            List.of("WORKTREE", "BRANCH", "LAUNCH", "IF-EXIT-8", "CLOSE");
    static final List<String> CLOSE_SUCCESS = List.of("CLOSED", "BRANCH", "DELETE");

    /**
     * The failure contract, as {@code wt} prints it now.
     *
     * <p>Three keys, not two, and the third is load-bearing: {@code log:} is
     * where the reasoning went when the console dump was removed, so a refusal
     * that printed {@code error} and {@code fix:} and named no log would have
     * dropped the detail on the floor rather than demoted it. All three are
     * anchored {@code ^KEY\s} by the same matcher the old {@code FAILED} /
     * {@code FIX} set used — {@code error creating worktree: …} matches
     * {@code ^error\s}, and {@code die()}'s prose {@code error: …} deliberately
     * does not.
     */
    static final List<String> FAILURE = List.of("error", "fix:", "log:");

    /**
     * What a successful {@code wt new} prints, in full.
     *
     * <p>Anchored at both ends and asserted as the ONLY non-blank line, because
     * "contains a line that looks like this" is satisfied by the five-line
     * contract this replaced. The budget claim is about what is NOT there.
     */
    static final java.util.regex.Pattern NEW_SUMMARY =
            java.util.regex.Pattern.compile("^created worktree (\\S+)$");

    /** The most lines a refusal may cost. {@code error} / {@code fix:} / {@code log:}. */
    static final int REFUSAL_LINE_BUDGET = 3;

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String lifecycle = "onboarding.worktree.lifecycle";
            String dirtyNewLog = read(ctx, lifecycle, "dirtyNewLog");
            String newLog = read(ctx, lifecycle, "newLog");
            String infoLog = read(ctx, lifecycle, "infoLog");
            String blockedLog = read(ctx, lifecycle, "blockedCloseLog");
            String forcedLog = read(ctx, lifecycle, "forcedCloseLog");
            int dirtyNewExit = intOf(ctx, lifecycle, "dirtyNewExit");
            int newExit = intOf(ctx, lifecycle, "newExit");
            int infoExit = intOf(ctx, lifecycle, "infoExit");
            int blockedExit = intOf(ctx, lifecycle, "blockedCloseExit");
            int forcedExit = intOf(ctx, lifecycle, "forcedCloseExit");
            String blocker = ctx.get(lifecycle, "blockerUnit").orElse("");

            // --- the anchoring companion, first: prove the matcher discriminates.
            //     A contains-check would report CLOSE present in every CLOSED.
            boolean theAnchoredMatcherDistinguishesCloseFromClosed =
                    OnboardingSupport.hasContractKey("CLOSED   /somewhere\n", "CLOSED")
                            && !OnboardingSupport.hasContractKey("CLOSED   /somewhere\n", "CLOSE");

            // --- the mandatory companion: the forced path was actually reached ---
            //
            // Through the named log, not the console. A refusal prints three
            // lines and demotes the reasoning — including the blocker's name —
            // to the file its `log:` line points at. Searching only the console
            // reports "the gate named nothing" about a gate that named the unit
            // exactly, and plusNamedLog THROWS if the file is gone rather than
            // letting an empty string answer the question.
            String blockedDetail = OnboardingSupport.plusNamedLog(blockedLog);
            boolean theUnforcedCloseWasGenuinelyBlocked =
                    blockedExit != 0 && !blocker.isEmpty() && blockedDetail.contains(blocker);
            boolean theForcedCloseWasTheForcedPath =
                    theUnforcedCloseWasGenuinelyBlocked && forcedExit == 0;

            List<String> problems = new ArrayList<>();

            // --- new: the ONE LINE, asserted as a line count -----------------------
            //
            // THE BUDGET GUARANTEE, and the reason it is asserted here rather
            // than inferred. The lifecycle node no longer reads this text at all
            // — it takes the worktree path from `wt info`, because the summary is
            // prose — so without this assertion nothing in the graph would
            // notice `wt new` going back to printing five lines.
            List<String> newLines = nonBlankLines(newLog);
            java.util.regex.Matcher summary = newLines.size() == 1
                    ? NEW_SUMMARY.matcher(newLines.get(0)) : null;
            boolean summaryMatched = summary != null && summary.matches();
            boolean wtNewSucceededInExactlyOneLine = newExit == 0 && summaryMatched;
            if (newExit == 0 && !wtNewSucceededInExactlyOneLine) {
                problems.add("wt new exit 0 printed " + newLines.size() + " line(s): " + newLines);
            }
            // …and it says nothing that looks like a refusal.
            boolean newContractIsExclusive =
                    exclusive(problems, "wt new", newLog, newExit, List.of());

            // --- info: the full key set, on demand ---------------------------------
            boolean wtInfoPrintedTheFullKeySet =
                    exclusive(problems, "wt info", infoLog, infoExit, INFO_CONTRACT);

            // --- the two commands agree about which worktree this is ---------------
            //
            // What makes reading the path from `wt info` safe. One command
            // creates the worktree and one describes it; if they ever disagreed,
            // every assertion downstream of the lifecycle node would be about a
            // different directory than the one that was made.
            String summaryPath = summaryMatched ? summary.group(1) : "";
            String infoPath = contractValue(infoLog, "WORKTREE");
            boolean theSummaryNamesTheWorktreeInfoNames =
                    !summaryPath.isEmpty() && summaryPath.equals(infoPath);
            if (!theSummaryNamesTheWorktreeInfoNames) {
                problems.add("summary named '" + summaryPath + "', wt info named '"
                        + infoPath + "'");
            }

            // The dirty-tree run, when it happened, is a failure contract.
            boolean dirtyNewContractIsExclusive = dirtyNewExit == 0
                    || onlyFailureKeys(problems, "wt new (dirty tree)", dirtyNewLog,
                            INFO_CONTRACT);

            // --- close, blocked ---------------------------------------------------
            boolean blockedContractIsExclusive =
                    onlyFailureKeys(problems, "wt close (blocked)", blockedLog, CLOSE_SUCCESS);

            // --- close, forced: the case that emits both ---------------------------
            boolean forcedContractIsExclusive =
                    exclusive(problems, "wt close --force", forcedLog, forcedExit, CLOSE_SUCCESS);

            // --- and a refusal costs three lines, not twenty-three ------------------
            //
            // The other half of the budget claim, and the half that was measured:
            // the console dump a refused close used to print was 23 lines / 2.7 KB,
            // on the case an agent meets most often. Every refusal in this run is
            // counted, so a re-run that reinstates the dump fails here whichever
            // verb printed it.
            List<String> overBudget = new ArrayList<>();
            if (dirtyNewExit != 0) countRefusal(overBudget, "wt new (dirty tree)", dirtyNewLog);
            countRefusal(overBudget, "wt close (blocked)", blockedLog);
            boolean everyRefusalFitTheLineBudget = overBudget.isEmpty();
            problems.addAll(overBudget);

            boolean pass = theAnchoredMatcherDistinguishesCloseFromClosed
                    && theUnforcedCloseWasGenuinelyBlocked
                    && theForcedCloseWasTheForcedPath
                    && wtNewSucceededInExactlyOneLine && newContractIsExclusive
                    && wtInfoPrintedTheFullKeySet && theSummaryNamesTheWorktreeInfoNames
                    && dirtyNewContractIsExclusive
                    && blockedContractIsExclusive && forcedContractIsExclusive
                    && everyRefusalFitTheLineBudget;

            return (pass
                    ? NodeResult.pass("onboarding.wt.contract.lines")
                    : NodeResult.fail("onboarding.wt.contract.lines",
                            "problems=" + problems
                                    + " newExit=" + newExit
                                    + " infoExit=" + infoExit
                                    + " blockedExit=" + blockedExit
                                    + " forcedExit=" + forcedExit))
                    .assertion("the_anchored_matcher_distinguishes_close_from_closed",
                            theAnchoredMatcherDistinguishesCloseFromClosed)
                    .assertion("the_unforced_close_was_genuinely_blocked_by_the_gate",
                            theUnforcedCloseWasGenuinelyBlocked)
                    .assertion("the_forced_close_took_the_forced_path_not_the_pass_through",
                            theForcedCloseWasTheForcedPath)
                    .assertion("wt_new_succeeded_in_exactly_one_line",
                            wtNewSucceededInExactlyOneLine)
                    .assertion("wt_new_emitted_one_contract_not_both", newContractIsExclusive)
                    .assertion("wt_info_printed_the_full_key_set", wtInfoPrintedTheFullKeySet)
                    .assertion("the_one_line_summary_names_the_worktree_wt_info_names",
                            theSummaryNamesTheWorktreeInfoNames)
                    .assertion("wt_new_on_a_dirty_tree_emitted_only_the_failure_contract",
                            dirtyNewContractIsExclusive)
                    .assertion("wt_close_blocked_emitted_only_the_failure_contract",
                            blockedContractIsExclusive)
                    .assertion("wt_close_force_emitted_one_contract_not_both",
                            forcedContractIsExclusive)
                    .assertion("every_refusal_fit_the_three_line_budget",
                            everyRefusalFitTheLineBudget)
                    .metric("contractProblems", problems.size())
                    .metric("newSuccessLines", newLines.size())
                    .log("problems: " + problems);
        });
    }

    /**
     * Exit code and key set agree, and only one key set is present.
     *
     * <p>Exit 0 ⇒ every success key and none of {@link #FAILURE}. Non-zero ⇒
     * every {@link #FAILURE} key and none of the success keys.
     *
     * <p>An EMPTY {@code successKeys} is a legitimate call and not a no-op: it
     * is how a run whose success shape is the one-line summary rather than a key
     * set still gets asserted to carry no refusal. The one-line shape itself is
     * asserted separately, by count.
     */
    private static boolean exclusive(List<String> problems, String what, String log, int exit,
                                     List<String> successKeys) {
        if (exit == 0) {
            List<String> missing = new ArrayList<>();
            for (String key : successKeys) {
                if (!OnboardingSupport.hasContractKey(log, key)) missing.add(key);
            }
            List<String> leaked = new ArrayList<>();
            for (String key : FAILURE) {
                if (OnboardingSupport.hasContractKey(log, key)) leaked.add(key);
            }
            if (!missing.isEmpty()) problems.add(what + " exit 0 missing " + missing);
            if (!leaked.isEmpty()) {
                problems.add(what + " exit 0 ALSO emitted the failure contract " + leaked);
            }
            return missing.isEmpty() && leaked.isEmpty();
        }
        return onlyFailureKeys(problems, what, log, successKeys);
    }

    private static boolean onlyFailureKeys(List<String> problems, String what, String log,
                                           List<String> successKeys) {
        List<String> missing = new ArrayList<>();
        for (String key : FAILURE) {
            if (!OnboardingSupport.hasContractKey(log, key)) missing.add(key);
        }
        List<String> leaked = new ArrayList<>();
        for (String key : successKeys) {
            if (OnboardingSupport.hasContractKey(log, key)) leaked.add(key);
        }
        if (!missing.isEmpty()) problems.add(what + " failed without " + missing);
        if (!leaked.isEmpty()) {
            problems.add(what + " failed but ALSO emitted the success contract " + leaked);
        }
        return missing.isEmpty() && leaked.isEmpty();
    }

    /** Record {@code what} in {@code problems} when its refusal ran over budget. */
    private static void countRefusal(List<String> problems, String what, String log) {
        List<String> lines = nonBlankLines(log);
        if (lines.size() > REFUSAL_LINE_BUDGET) {
            problems.add(what + " refused in " + lines.size() + " lines (budget "
                    + REFUSAL_LINE_BUDGET + ")");
        }
    }

    /**
     * The non-blank, stripped lines of a captured console log.
     *
     * <p>Blanks are dropped rather than counted: {@code wt} prints a separating
     * newline around the child's prose on the pass-through paths, and a budget
     * assertion that failed over whitespace would be about formatting rather
     * than about what a caller has to read.
     */
    private static List<String> nonBlankLines(String log) {
        List<String> out = new ArrayList<>();
        for (String raw : log.split("\n", -1)) {
            String line = raw.strip();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** The value of an anchored {@code KEY   <value>} contract line, or {@code ""}. */
    private static String contractValue(String log, String key) {
        for (String raw : log.split("\n", -1)) {
            if (!raw.startsWith(key)) continue;
            String rest = raw.substring(key.length());
            if (rest.isEmpty() || !Character.isWhitespace(rest.charAt(0))) continue;
            String value = rest.strip();
            if (!value.isEmpty()) return value.split("\\s+")[0];
        }
        return "";
    }

    private static String read(NodeContext ctx, String node, String key) {
        String p = ctx.get(node, key).orElse("");
        return p.isEmpty() ? "" : OnboardingSupport.read(Path.of(p));
    }

    private static int intOf(NodeContext ctx, String node, String key) {
        try {
            return Integer.parseInt(ctx.get(node, key).orElse("-1"));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
