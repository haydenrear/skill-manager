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
 * <b>Step 15 — {@code wt}'s stdout carries the success contract or the failure
 * contract, never both.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code wt --help} states the contract: "either, on failure FAILED / FIX".
 * {@code wt close <T> --force} on a worktree the gate had refused emits BOTH —
 * exit 0, with {@code FAILED} and {@code FIX} from the gate followed by
 * {@code CLOSED}, {@code BRANCH} and {@code DELETE} from the successful forced
 * teardown. A caller parsing stdout sees {@code FAILED} on a run that
 * succeeded, which is the one thing the contract exists to prevent — the whole
 * point of the KEY set is that callers survive a reimplementation of {@code wt}
 * by depending on the keys rather than on the prose.
 *
 * <h2>Assertions</h2>
 *
 * <ul>
 *   <li>{@code new} success ⇒ {@code WORKTREE}, {@code BRANCH}, {@code LAUNCH},
 *       {@code IF-EXIT-8}, {@code CLOSE}, and no {@code FAILED}.</li>
 *   <li>{@code close} success ⇒ {@code CLOSED}, {@code BRANCH}, {@code DELETE},
 *       and no {@code FAILED}.</li>
 *   <li>Failure ⇒ {@code FAILED} and {@code FIX} and none of the others.</li>
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
 *       (exit non-zero, the blocker naming the exact unit). A {@code --force}
 *       over a worktree that had nothing to force is the pass-through path
 *       under a different name.</li>
 *   <li><b>Substring matching.</b> {@code CLOSE} is a substring of
 *       {@code CLOSED}, so a contains-check literally cannot tell the
 *       {@code new} success contract from the {@code close} one.
 *       <br><b>Companion:</b> every key is matched anchored, as
 *       {@code ^KEY\s}, by {@link OnboardingSupport#hasContractKey} — and this
 *       node proves the anchoring discriminates by asserting that a
 *       {@code close} log carrying {@code CLOSED} does NOT satisfy the
 *       {@code CLOSE} key.</li>
 * </ol>
 */
public class OnboardingWtContractLines {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.wt.contract.lines")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.worktree.lifecycle")
            .tags("onboarding", "worktree", "contract")
            .timeout("300s");

    static final List<String> NEW_SUCCESS =
            List.of("WORKTREE", "BRANCH", "LAUNCH", "IF-EXIT-8", "CLOSE");
    static final List<String> CLOSE_SUCCESS = List.of("CLOSED", "BRANCH", "DELETE");
    static final List<String> FAILURE = List.of("FAILED", "FIX");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String lifecycle = "onboarding.worktree.lifecycle";
            String dirtyNewLog = read(ctx, lifecycle, "dirtyNewLog");
            String newLog = read(ctx, lifecycle, "newLog");
            String blockedLog = read(ctx, lifecycle, "blockedCloseLog");
            String forcedLog = read(ctx, lifecycle, "forcedCloseLog");
            int dirtyNewExit = intOf(ctx, lifecycle, "dirtyNewExit");
            int newExit = intOf(ctx, lifecycle, "newExit");
            int blockedExit = intOf(ctx, lifecycle, "blockedCloseExit");
            int forcedExit = intOf(ctx, lifecycle, "forcedCloseExit");
            String blocker = ctx.get(lifecycle, "blockerUnit").orElse("");

            // --- the anchoring companion, first: prove the matcher discriminates.
            //     A contains-check would report CLOSE present in every CLOSED.
            boolean theAnchoredMatcherDistinguishesCloseFromClosed =
                    OnboardingSupport.hasContractKey("CLOSED   /somewhere\n", "CLOSED")
                            && !OnboardingSupport.hasContractKey("CLOSED   /somewhere\n", "CLOSE");

            // --- the mandatory companion: the forced path was actually reached ---
            boolean theUnforcedCloseWasGenuinelyBlocked =
                    blockedExit != 0 && !blocker.isEmpty() && blockedLog.contains(blocker);
            boolean theForcedCloseWasTheForcedPath =
                    theUnforcedCloseWasGenuinelyBlocked && forcedExit == 0;

            // --- new -------------------------------------------------------------
            List<String> problems = new ArrayList<>();
            boolean newContractIsExclusive =
                    exclusive(problems, "wt new", newLog, newExit, NEW_SUCCESS);
            // The dirty-tree run, when it happened, is a failure contract.
            boolean dirtyNewContractIsExclusive = dirtyNewExit == 0
                    || onlyFailureKeys(problems, "wt new (dirty tree)", dirtyNewLog, NEW_SUCCESS);

            // --- close, blocked ---------------------------------------------------
            boolean blockedContractIsExclusive =
                    onlyFailureKeys(problems, "wt close (blocked)", blockedLog, CLOSE_SUCCESS);

            // --- close, forced: the case that emits both ---------------------------
            boolean forcedContractIsExclusive =
                    exclusive(problems, "wt close --force", forcedLog, forcedExit, CLOSE_SUCCESS);

            boolean pass = theAnchoredMatcherDistinguishesCloseFromClosed
                    && theUnforcedCloseWasGenuinelyBlocked
                    && theForcedCloseWasTheForcedPath
                    && newContractIsExclusive && dirtyNewContractIsExclusive
                    && blockedContractIsExclusive && forcedContractIsExclusive;

            return (pass
                    ? NodeResult.pass("onboarding.wt.contract.lines")
                    : NodeResult.fail("onboarding.wt.contract.lines",
                            "problems=" + problems
                                    + " blockedExit=" + blockedExit
                                    + " forcedExit=" + forcedExit))
                    .assertion("the_anchored_matcher_distinguishes_close_from_closed",
                            theAnchoredMatcherDistinguishesCloseFromClosed)
                    .assertion("the_unforced_close_was_genuinely_blocked_by_the_gate",
                            theUnforcedCloseWasGenuinelyBlocked)
                    .assertion("the_forced_close_took_the_forced_path_not_the_pass_through",
                            theForcedCloseWasTheForcedPath)
                    .assertion("wt_new_emitted_one_contract_not_both", newContractIsExclusive)
                    .assertion("wt_new_on_a_dirty_tree_emitted_only_the_failure_contract",
                            dirtyNewContractIsExclusive)
                    .assertion("wt_close_blocked_emitted_only_the_failure_contract",
                            blockedContractIsExclusive)
                    .assertion("wt_close_force_emitted_one_contract_not_both",
                            forcedContractIsExclusive)
                    .metric("contractProblems", problems.size())
                    .log("problems: " + problems);
        });
    }

    /**
     * Exit code and key set agree, and only one key set is present.
     *
     * <p>Exit 0 ⇒ every success key, no {@code FAILED}, no {@code FIX}.
     * Non-zero ⇒ {@code FAILED} and {@code FIX}, and none of the success keys.
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
