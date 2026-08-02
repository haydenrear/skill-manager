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
import java.util.regex.Pattern;

/**
 * <b>Step 11 — a printed violation must reach the exit code.</b>
 *
 * <h2>The defect</h2>
 *
 * <p>{@code install} exits <b>0</b> while reporting markdown skill-import
 * violations. {@code LiveInterpreter.validateMarkdownImports} returns
 * {@code EffectReceipt.partial} and the install command never maps that to a
 * non-zero exit. Worse, the block prints AFTER the success banner and after
 * {@code ACTION_REQUIRED}, at the very bottom of a long install — so an agent
 * that tails the output concludes success, which is the failure mode this
 * whole class of defect keeps taking.
 *
 * <h2>Why this node builds its own home</h2>
 *
 * <p>The assertion is "the non-zero exit came FROM the violations". In the main
 * project home every unit is {@code file:}-installed and therefore carries a
 * permanent {@code NEEDS_GIT_MIGRATION} record, so any command there exits
 * non-zero for a reason that has nothing to do with imports — and reading that
 * as evidence is exactly the mistake the hand walk nearly made with
 * {@code sync}'s exit 7. So this node builds a home whose units are git-tracked
 * (a remote, no network needed) and asserts it is error-free FIRST.
 *
 * <h2>Vacuous-pass risks and companions</h2>
 *
 * <ol>
 *   <li><b>Reading a non-zero exit that came from something else.</b>
 *       <br><b>Companion — mandatory:</b> the home must show NO
 *       {@code ⚠ skills with outstanding errors} banner before the step, so the
 *       only possible cause of a non-zero exit is the violations. Asserted, and
 *       the node fails if the home is not clean.</li>
 *   <li><b>The inverted "can it fail" control.</b> A unit with VALID imports
 *       must exit 0 in the same home and emit no violation block. Without it,
 *       "install exits non-zero" could be satisfied by an install command that
 *       simply always fails.</li>
 *   <li><b>Grepping for the word "violation"</b> in output that also contains
 *       the remedy text of an unrelated error record.
 *       <br><b>Companion:</b> the exact count {@code violations (2)} is
 *       asserted, plus both distinct messages — a missing UNIT and a missing
 *       PATH, which are two different code paths. Two, not one, because the
 *       count is the assertion.</li>
 * </ol>
 */
public class OnboardingImportViolationsAreFatal {

    static final NodeSpec SPEC = NodeSpec.of("onboarding.import.violations.are.fatal")
            .kind(NodeSpec.Kind.ASSERTION)
            .dependsOn("onboarding.error.records.coherent")
            .tags("onboarding", "imports", "exit-codes")
            .timeout("900s");

    static final String BANNER = "skills with outstanding errors";
    /** {@code MarkdownImportValidator.EXIT_CODE} — the typed code for this class. */
    static final int IMPORT_VIOLATION_EXIT = 11;

    static final Pattern VIOLATION_COUNT =
            Pattern.compile("markdown skill-import violations \\((\\d+)\\)");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            Path workspace = path(ctx, "onboarding.fixture.built", "workspace");
            if (workspace == null) {
                return NodeResult.fail("onboarding.import.violations.are.fatal",
                        "missing upstream context");
            }
            Path base = workspace.resolve("violations");
            Path units = base.resolve("units");
            Path home = base.resolve("home");
            Path agents = base.resolve("agents");
            Files.createDirectories(units);
            Files.createDirectories(agents);

            // --- a home whose units are git-tracked, so it starts error-free ----
            //
            // A remote is declared but never contacted: the install copies from
            // the local path. What the remote buys is the absence of
            // NEEDS_GIT_MIGRATION, which is what makes the exit code readable.
            Path host = gitUnit(units, "ob-host", "the unit the bad import points into", null);
            Path good = gitUnit(units, "ob-good", "a unit whose imports are all valid",
                    OnboardingSupport.imports(OnboardingSupport.entry(
                            "ob-host", "SKILL.md", "a unit and a path that both exist")));
            Path bad = gitUnit(units, "ob-bad", "a unit with exactly two invalid imports",
                    OnboardingSupport.imports(
                            OnboardingSupport.entry("ob-nowhere", "SKILL.md",
                                    "names a unit that is not installed"),
                            OnboardingSupport.entry("ob-host",
                                    "references/definitely-missing.md",
                                    "names an installed unit and a path it lacks")));

            ProcessRecord installHost = OnboardingSupport.sm(ctx, "viol-install-host", home,
                    agents, "install", host.toString(), "--yes");

            ProcessRecord listBefore = OnboardingSupport.sm(ctx, "viol-list-before", home, agents,
                    "list");
            String listBeforeLog = OnboardingSupport.log(ctx, listBefore);
            boolean theHomeHasNoOutstandingErrorRecordsBeforeTheStep =
                    installHost.exitCode() == 0 && !listBeforeLog.contains(BANNER);

            // --- the inverted control: valid imports, exit 0, no block -----------
            ProcessRecord installGood = OnboardingSupport.sm(ctx, "viol-install-good", home,
                    agents, "install", good.toString(), "--yes");
            String goodLog = OnboardingSupport.log(ctx, installGood);
            boolean aUnitWithValidImportsInstallsCleanly = installGood.exitCode() == 0
                    && !goodLog.contains("skill-import violations");

            // --- the step under test ----------------------------------------------
            ProcessRecord installBad = OnboardingSupport.sm(ctx, "viol-install-bad", home, agents,
                    "install", bad.toString(), "--yes");
            String badLog = OnboardingSupport.log(ctx, installBad);
            String countRaw = OnboardingSupport.firstGroup(badLog, VIOLATION_COUNT);
            boolean bothViolationsWereReported = "2".equals(countRaw)
                    && badLog.contains("ob-nowhere")
                    && badLog.contains("definitely-missing.md");
            // Exactly 11 (MarkdownImportValidator.EXIT_CODE), not merely
            // non-zero. A typed code is what makes the exit EVIDENCE: in this
            // same walk, `sync` exits non-zero over a NEEDS_GIT_MIGRATION record
            // that has nothing to do with imports, and reading that as "so
            // violations are fatal" is the mistake this node is built to avoid.
            // The clean-home precondition above rules out the other causes; the
            // typed code says which one fired.
            boolean anInstallThatReportsViolationsExitsNonZero =
                    installBad.exitCode() == IMPORT_VIOLATION_EXIT;

            // Where the block sits relative to the terminal summary. An agent
            // that tails the output has to be able to see it.
            int violationsAt = badLog.indexOf("skill-import violations");
            int actionRequiredAt = badLog.indexOf("ACTION_REQUIRED");
            // The ordering half. `|| actionRequiredAt < 0` is a real hole — an
            // install that never printed ACTION_REQUIRED satisfies it for free —
            // so it is only tolerated alongside the assertion that the violation
            // block was printed at all, which the count assertion above makes.
            // The run reports both offsets so a reader can see which case it was.
            boolean theViolationBlockIsNotBuriedAfterActionRequired = violationsAt >= 0
                    && (actionRequiredAt < 0 || violationsAt < actionRequiredAt);

            boolean pass = theHomeHasNoOutstandingErrorRecordsBeforeTheStep
                    && aUnitWithValidImportsInstallsCleanly
                    && bothViolationsWereReported
                    && anInstallThatReportsViolationsExitsNonZero
                    && theViolationBlockIsNotBuriedAfterActionRequired;

            return (pass
                    ? NodeResult.pass("onboarding.import.violations.are.fatal")
                    : NodeResult.fail("onboarding.import.violations.are.fatal",
                            "cleanBefore=" + theHomeHasNoOutstandingErrorRecordsBeforeTheStep
                                    + " goodExit=" + installGood.exitCode()
                                    + " violationCount=" + countRaw
                                    + " badExit=" + installBad.exitCode()
                                    + " (expected " + IMPORT_VIOLATION_EXIT + ")"
                                    + " violationsAt=" + violationsAt
                                    + " actionRequiredAt=" + actionRequiredAt))
                    .process(installHost).process(listBefore)
                    .process(installGood).process(installBad)
                    .assertion("the_home_has_no_outstanding_error_records_before_the_step",
                            theHomeHasNoOutstandingErrorRecordsBeforeTheStep)
                    .assertion("a_unit_with_valid_imports_installs_cleanly_and_silently",
                            aUnitWithValidImportsInstallsCleanly)
                    .assertion("both_violations_were_reported_a_missing_unit_and_a_missing_path",
                            bothViolationsWereReported)
                    .assertion("an_install_that_reports_violations_exits_non_zero",
                            anInstallThatReportsViolationsExitsNonZero)
                    .assertion("the_violation_block_is_not_buried_after_action_required",
                            theViolationBlockIsNotBuriedAfterActionRequired)
                    .metric("violationsReported", countRaw == null ? -1
                            : Integer.parseInt(countRaw))
                    .metric("installExit", installBad.exitCode())
                    .log("valid-import control exit: " + installGood.exitCode())
                    .log("violation block offset " + violationsAt
                            + ", ACTION_REQUIRED offset " + actionRequiredAt);
        });
    }

    /**
     * A unit source directory that is a git repository with a remote.
     *
     * <p>The remote is never contacted. Its only job is to keep the install off
     * the {@code NEEDS_GIT_MIGRATION} path, so this home starts with zero
     * persisted error records and the exit code of the step under test means
     * exactly one thing.
     */
    private static Path gitUnit(Path parent, String name, String description, String imports)
            throws Exception {
        Path dir = OnboardingSupport.mkUnit(parent, name, description, imports);
        List<String> failures = new ArrayList<>();
        OnboardingSupport.git(failures, dir, "init", "-q", "-b", "main");
        OnboardingSupport.git(failures, dir, "config", "user.email", "graph@localhost");
        OnboardingSupport.git(failures, dir, "config", "user.name", "graph");
        OnboardingSupport.git(failures, dir, "remote", "add", "origin",
                "https://example.invalid/" + name + ".git");
        OnboardingSupport.git(failures, dir, "add", "-A");
        OnboardingSupport.git(failures, dir, "commit", "-qm", "unit fixture");
        if (!failures.isEmpty()) {
            throw new IllegalStateException("could not build git unit " + name + ": " + failures);
        }
        return dir;
    }

    private static Path path(NodeContext ctx, String node, String key) {
        return ctx.get(node, key).map(Path::of).orElse(null);
    }
}
