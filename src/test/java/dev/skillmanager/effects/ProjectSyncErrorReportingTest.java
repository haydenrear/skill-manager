package dev.skillmanager.effects;

import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.ReportUseCase;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.source.InstalledUnit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;

/**
 * How a project-sync failure READS, and how it goes away. Issue #144.
 *
 * <h2>The measurement this exists to hold</h2>
 *
 * <p>A {@code project sync} failure is a property of the project, and it is
 * recorded on every unit the project claims. On the reporting operator's home
 * that was <b>10 units carrying an {@code errors[]} entry with exactly ONE
 * distinct message between them</b> — and {@code tryPrintOutstandingErrors}
 * re-printed all ten on every subsequent {@code ls}, {@code show},
 * {@code exec} and {@code --print-env}. One collision presented as a wall of
 * identical text that reads as "everything is broken".
 *
 * <p>The per-unit RECORD is kept: a unit whose realization in that project
 * failed genuinely is degraded, and {@code show <unit>} is the only place that
 * says so. What is fixed is that one cause is now reported once. The
 * assertions below are therefore about VOLUME and IDENTITY — how many times
 * one message appears — not about whether it appears.
 */
public final class ProjectSyncErrorReportingTest {

    private static final String ONE_CAUSE =
            "commit-diff-context-parent: child home path already exists: "
                    + "/elsewhere/.skill-manager/bin/cli/computeq";

    private static final List<String> TEN = List.of(
            "deploy-helm", "git-epic-workflow", "git-integration-repo", "git-issue",
            "git-issue-workflow", "skill-manager", "skill-publisher",
            "spec-double-compiler", "test-graph", "tracing-observability");

    public static int run() throws Exception {
        return Tests.suite("ProjectSyncErrorReportingTest")

                .test("one project sync failure on ten units is reported once, not ten times", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        for (String unit : TEN) {
                            h.scaffoldUnitDir(unit, UnitKind.SKILL);
                            h.seedUnit(unit, UnitKind.SKILL);
                            h.context().addError(unit,
                                    InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, ONE_CAUSE);
                        }

                        String err = renderReport(h);

                        assertEquals(1, occurrences(err, ONE_CAUSE),
                                "one distinct message is printed exactly once; got:\n" + err);
                        assertEquals(1, occurrences(err, "PROJECT_SYNC_FAILED"),
                                "and so is its kind; got:\n" + err);
                        assertContains(err, "outstanding errors (10)",
                                "the number of degraded units is still stated");
                        assertContains(err, "1 distinct cause",
                                "beside the number the operator actually has to act on");
                        for (String unit : TEN) {
                            assertContains(err, unit, "every affected unit is still named");
                        }
                        // The remedy is the project's, not one unit's: naming
                        // any single one of the ten would send the operator
                        // after the wrong thing.
                        assertFalse(err.contains("skill-manager sync deploy-helm"),
                                "a shared cause does not get a per-unit remedy; got:\n" + err);
                    }
                })

                .test("units degraded differently are still reported separately", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        for (String unit : List.of("alpha", "beta", "gamma")) {
                            h.scaffoldUnitDir(unit, UnitKind.SKILL);
                            h.seedUnit(unit, UnitKind.SKILL);
                        }
                        h.context().addError("alpha",
                                InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, ONE_CAUSE);
                        h.context().addError("beta",
                                InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, ONE_CAUSE);
                        h.context().addError("gamma",
                                InstalledUnit.ErrorKind.NO_GIT_REMOTE, "no origin configured");

                        String err = renderReport(h);

                        assertContains(err, "outstanding errors (3)",
                                "three units are degraded");
                        assertContains(err, "2 distinct cause",
                                "but only two things are wrong");
                        assertContains(err, "  alpha, beta:",
                                "the two that share a cause share a block; got:\n" + err);
                        assertContains(err, "  gamma:",
                                "and the one that does not keeps its own; got:\n" + err);
                        assertContains(err, "skills/gamma && git remote add origin",
                                "a block of one still gets its per-unit remedy, naming that "
                                        + "unit's own store directory; got:\n" + err);
                    }
                })

                .test("a project sync failure nothing can produce again clears itself", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        h.seedUnit("orphaned-unit", UnitKind.SKILL);
                        h.context().addError("orphaned-unit",
                                InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED, ONE_CAUSE);

                        // No project's lock claims this unit, so no
                        // `syncClaimingProjects` pass will ever revisit it:
                        // the record is unfalsifiable and permanent. The
                        // reconciler runs on every command, so this is what
                        // makes it clearable from inside the affected project
                        // without first making the failing sync succeed.
                        h.run(new SkillEffect.ValidateAndClearError(
                                "orphaned-unit", InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED));

                        assertFalse(h.sourceOf("orphaned-unit").orElseThrow()
                                        .hasError(InstalledUnit.ErrorKind.PROJECT_SYNC_FAILED),
                                "an error no project can produce again is stale, and is dropped");
                    }
                })
                .runAll();
    }

    // ------------------------------------------------------------- helpers

    /** The closing report every command runs, with stderr captured. */
    private static String renderReport(TestHarness h) {
        PrintStream realErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true));
            new LiveInterpreter(h.store(), null).run(ReportUseCase.buildProgram());
        } finally {
            System.setErr(realErr);
        }
        return err.toString();
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
             at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }
}
