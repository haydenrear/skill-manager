///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES OnboardingSupport.java

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeContext;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Step 6 — after the documented onboarding sequence, is the work tree still
 * clean?</b> If it is not, the next step of the workflow refuses.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code references/skill-homes.md} "Ignoring the homes" prescribes exactly
 * four rules: {@code /.skill-manager/}, {@code /.claude/}, {@code /.codex/},
 * {@code /.gemini/}. Onboarding then creates {@code <project>/.claude.json} —
 * and {@code /.claude/} does not match {@code /.claude.json}. So a repo whose
 * {@code .gitignore} follows the documentation literally ends the documented
 * sequence with {@code ?? .claude.json}, and {@code wt new <TICKET>}
 * immediately afterwards exits 1 with
 * {@code FAILED working tree is not clean}.
 *
 * <p>The file is only there at all because of the sibling defect asserted in
 * {@code onboarding.claude.mcp.config.readable}: the Claude MCP registration is
 * written to the agent home ROOT rather than to {@code $CLAUDE_CONFIG_DIR}. Fix
 * that and this one goes away too; fix only the {@code .gitignore} and the
 * agent still gets no MCP tools. The two nodes are kept separate so a run says
 * which of the two was fixed.
 *
 * <h2>Assertion</h2>
 *
 * <p>After bootstrap → register → resolve → sync on a repo whose
 * {@code .gitignore} carries only the four documented rules,
 * {@code git status --porcelain} is empty.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>The fixture's {@code .gitignore} was written from a working setup
 *       and already carries {@code /.claude.json}.</b> Then the assertion is
 *       about nothing.
 *       <br><b>Companion:</b> the rules are re-read here and must equal the
 *       four documented ones exactly — a fifth rule fails the node. (The
 *       fixture asserts the same thing at creation; it is checked again here
 *       because four nodes have run in between and any of them could have
 *       appended.)</li>
 *   <li><b>{@code git status} run before the step that creates the file.</b>
 *       {@code .claude.json} appears at {@code install}/{@code sync}, not at
 *       bootstrap — a cleanliness check placed too early passes for the wrong
 *       reason.
 *       <br><b>Companion:</b> this node depends on {@code onboarding.synced},
 *       and it asserts the file's EXISTENCE as a precondition. That
 *       distinguishes "clean because it was ignored" from "clean because
 *       nothing ran", and the node reports which of the two the run took.</li>
 *   <li><b>Asserting only that {@code wt new} exits 0</b> — it would also exit
 *       0 if the file were never created for an unrelated reason.
 *       <br><b>Companion:</b> both halves are asserted, and the run records the
 *       untracked set so the reason is legible rather than inferred. The
 *       {@code wt new} half itself lives in
 *       {@code onboarding.worktree.lifecycle}, which needs the tree in
 *       whatever state this node found it.</li>
 * </ol>
 */
public class OnboardingLeavesWorkTreeClean {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.leaves.work.tree.clean")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.synced")
            .tags("onboarding", "git", "blocker")
            .timeout("300s")
            .output("untracked", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path proj = path(ctx, "onboarding.fixture.built", "proj");
            if (proj == null) {
                return NodeResult.fail("onboarding.leaves.work.tree.clean",
                        "missing upstream context");
            }

            // --- companion 1: the fixture did not pre-fix the gap -------------
            List<String> rules = new ArrayList<>();
            for (String line : Files.readAllLines(proj.resolve(".gitignore"))) {
                if (!line.isBlank()) rules.add(line.strip());
            }
            boolean theGitignoreIsStillExactlyTheDocumentedFour =
                    rules.equals(OnboardingSupport.DOCUMENTED_IGNORES);

            // --- companion 2: the onboarding sequence really ran ----------------
            //
            // Without this, "clean" cannot be told apart from "nothing ever
            // wrote anything".
            //
            // The proxy is the MCP registration landing where the agent reads
            // it — <root>/.claude/.claude.json — NOT the existence of
            // <root>/.claude.json. It used to be the latter, and that was a
            // proxy for the sequence having run only for as long as the sequence
            // put a file in the work tree. Writing the entry to the config
            // directory instead fixed the untracked file AND made the old
            // companion fail, on a run where the property under test had just
            // become true. A precondition that only holds while the defect does
            // is not measuring the precondition.
            Path strayInWorkTree = proj.resolve(".claude.json");
            Path whereTheAgentReads = proj.resolve(".claude").resolve(".claude.json");
            boolean theOnboardingSequenceActuallyRan =
                    Files.isRegularFile(whereTheAgentReads)
                            || Files.isRegularFile(strayInWorkTree);

            // --- which mechanism made it clean, if it is clean ------------------
            //
            // Worth naming, because the fix did NOT arrive where the spec
            // expected it. `references/skill-homes.md` prescribes four
            // .gitignore rules and the assertion was that they should cover the
            // file; what actually landed is a `/.claude.json` line in
            // .git/info/exclude, written per checkout. The work tree is clean
            // either way, but only one of those is the documented contract, and
            // a node that reported a bare green would let a reader conclude the
            // docs were now correct. They are not: the tracked .gitignore still
            // carries exactly the four rules, asserted above.
            String ignoredBy = HomeSyncSupport.git(proj,
                    "check-ignore", "-v", ".claude.json").trimmed();
            String coveredBy = ignoredBy.isEmpty() ? "nothing"
                    : ignoredBy.contains(".git/info/exclude") ? ".git/info/exclude (per checkout)"
                            : ignoredBy.contains(".gitignore") ? "the tracked .gitignore"
                                    : ignoredBy;

            // --- the assertion ------------------------------------------------
            String status = HomeSyncSupport.git(proj, "status", "--porcelain").trimmed();
            List<String> untracked = new ArrayList<>();
            for (String line : status.split("\n", -1)) {
                if (!line.isBlank()) untracked.add(line.strip());
            }
            boolean theDocumentedSequenceLeftTheWorkTreeClean = untracked.isEmpty();

            // Which of the two ways it could be clean did this run take? Stated
            // as a metric rather than inferred by a reader from the pass/fail.
            String route = !theOnboardingSequenceActuallyRan
                    ? "no MCP registration anywhere — this run did not measure the ignore rules"
                    : !Files.isRegularFile(strayInWorkTree)
                            ? "the entry went to <root>/.claude/.claude.json, so nothing"
                                    + " untracked was ever created in the work tree"
                            : theDocumentedSequenceLeftTheWorkTreeClean
                                    ? "a stray <root>/.claude.json exists AND is covered by the"
                                            + " documented rules"
                                    : "a stray <root>/.claude.json exists and the four documented"
                                            + " rules do not cover it";

            boolean pass = theGitignoreIsStillExactlyTheDocumentedFour
                    && theOnboardingSequenceActuallyRan
                    && theDocumentedSequenceLeftTheWorkTreeClean;

            return (pass
                    ? NodeResult.pass("onboarding.leaves.work.tree.clean")
                    : NodeResult.fail("onboarding.leaves.work.tree.clean",
                            "gitignore=" + rules
                                    + " sequenceRan=" + theOnboardingSequenceActuallyRan
                                    + " strayInWorkTree=" + Files.isRegularFile(strayInWorkTree)
                                    + " untracked=" + untracked))
                    .assertion("the_gitignore_still_carries_exactly_the_four_documented_rules",
                            theGitignoreIsStillExactlyTheDocumentedFour)
                    .assertion("the_onboarding_sequence_actually_ran",
                            theOnboardingSequenceActuallyRan)
                    .assertion("the_documented_onboarding_sequence_left_the_work_tree_clean",
                            theDocumentedSequenceLeftTheWorkTreeClean)
                    .metric("untrackedEntries", untracked.size())
                    .log("route: " + route)
                    .log("the stray path is covered by: " + coveredBy
                            + (ignoredBy.isEmpty() ? "" : "  [" + ignoredBy + "]"))
                    .log("git status --porcelain: " + untracked)
                    .publish("untracked", String.join(";", untracked));
        });
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
