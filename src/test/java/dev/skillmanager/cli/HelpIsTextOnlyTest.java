package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.store.SkillStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;

/**
 * <b>{@code --help} is text. It scans no home and writes none.</b>
 *
 * <p>HIS-21 / DEF-102. {@code skill-manager sync --help} printed the
 * outstanding-error banner under the usage text, because
 * {@code SkillManagerCli.completeExecution} ran the closing report program on
 * every path — resolving the ambient home, calling {@code store.init()}, and
 * walking every {@code installed/<unit>.json}.
 *
 * <p>Measured on a scratch home carrying one {@code AGENT_SYNC_FAILED} record:
 * {@code --help}, {@code sync --help}, {@code install --help} and
 * {@code home describe --help} each printed
 * {@code ! skills with outstanding errors (1)}.
 *
 * <h2>Why this is not a cosmetic ticket</h2>
 *
 * <p>Three things were wrong and only the first is presentation. The banner is
 * noise on the one output whose job is to be quotable. The read is a full walk
 * of a home, on the command an agent runs when it does not yet know what a
 * command does. And {@code store.init()} in that path is a WRITE, held off only
 * by {@link CommandHomeAccess#of} classifying help as {@code READ} — a guard
 * one classification away from {@code --help} creating a home nobody named.
 * The third test below asserts that outcome directly rather than trusting the
 * classification to keep holding.
 *
 * <h2>The control is the point</h2>
 *
 * <p>An assertion that a banner is absent passes for free against a fixture
 * where the banner could never have appeared — mechanism C in this epic's
 * ledger, and it is exactly what happened while this test was being written:
 * the first fixture used an {@code installSource} the record parser rejected,
 * the record was skipped, and BOTH the help arm and the non-help arm printed
 * nothing. It read like a fix. So every "absent" assertion here is paired with
 * the SAME home under a non-help invocation, which must print it.
 */
public final class HelpIsTextOnlyTest {

    /** The sentence {@code ReportUseCase} prints, and the only thing looked for. */
    private static final String BANNER = "skills with outstanding errors";

    public static int run() throws Exception {
        return Tests.suite("HelpIsTextOnlyTest")

                .test("POSITIVE CONTROL: a non-help command against this home DOES print the banner", () -> {
                    // Not an assertion about the fix. It is the assertion that
                    // makes the next test mean anything: without it, "no banner"
                    // is satisfied by a home with no errors, a record the parser
                    // dropped, or a driver that never reached the home at all.
                    Path home = homeWithAnOutstandingError("control");

                    Run r = run("home", "describe", "--home", home.toString());

                    assertEquals(0, r.rc, "the control command succeeds");
                    assertContains(r.all(), BANNER,
                            "the fixture really does have something for the closing report to "
                                    + "find; got:\n" + r.all());
                })

                .test("DEF-102: `--help` for a verb performs no project scan and exits 0", () -> {
                    // BRANCH: completeExecution's new
                    // `!helpOrVersionRequested(pr)` guard.
                    // MUTATION THAT REDDENS IT: restore the unconditional
                    // `tryPrintOutstandingErrors()`. All four arms below then
                    // fail while the control above stays green.
                    Path home = homeWithAnOutstandingError("help");

                    for (String[] argv : new String[][] {
                            { "sync", "--home", null, "--help" },
                            { "home", "describe", "--home", null, "--help" },
                            { "home", "verify", "--home", null, "--help" } }) {
                        argv[argv.length - 2] = home.toString();
                        Run r = run(argv);
                        assertEquals(0, r.rc, String.join(" ", argv) + " exits 0");
                        assertContains(r.all(), "Usage:",
                                String.join(" ", argv) + " printed its usage — otherwise the "
                                        + "absence below is the absence of everything");
                        assertFalse(r.all().contains(BANNER),
                                String.join(" ", argv) + " printed the project banner:\n"
                                        + r.all());
                    }
                })

                .test("DEF-102: `--help` creates nothing at a home path that does not exist", () -> {
                    // The half that is not about output. `store.init()` sits in
                    // the closing report and lays out a full home; today
                    // HomeScaffold refuses it for a READ invocation, so this
                    // asserts the OUTCOME rather than the classification, and
                    // stays true whichever of the two guards is holding.
                    Path absent = Files.createTempDirectory("help-no-write-")
                            .resolve("never-created").resolve(".skill-manager");
                    assertFalse(Files.exists(absent), "precondition: nothing is there");

                    Run r = run("sync", "--home", absent.toString(), "--help");

                    assertEquals(0, r.rc, "help against a non-existent home is still help");
                    assertFalse(Files.exists(absent),
                            "and it did not lay a home out at a path nobody asked for");
                    assertFalse(Files.exists(absent.getParent()),
                            "nor its parent");
                })

                .test("DEF-102: `--version` is text too", () -> {
                    // helpOrVersionRequested covers both, and the confinement
                    // gate and the scaffold gate already exempt both. Asserted
                    // so a narrowing of the predicate to usage-help alone goes
                    // red here rather than in an agent's transcript.
                    //
                    // THE HOME IS BOUND, NOT PASSED. The first version of this
                    // ran `--version --home <H>`; the root command declares no
                    // `--home`, so bindNamedHome never saw one, the closing
                    // report read the AMBIENT home, and the assertion passed
                    // under the V2 mutation as well as without it. Mechanism D
                    // — the probe exercised a different home than the fixture —
                    // caught by running the mutation rather than by reading the
                    // test. AgentHomes.bind is the same override the `--home`
                    // flag applies, so this is the flag's effect without the
                    // flag.
                    Path home = homeWithAnOutstandingError("version");
                    Run control = bound(home, "home", "describe", "--home", home.toString());
                    Run r = bound(home, "--version");
                    assertContains(control.all(), BANNER,
                            "POSITIVE CONTROL: with this home bound, a non-help invocation "
                                    + "still finds the error; got:\n" + control.all());
                    assertEquals(0, r.rc, "--version prints the version and exits 0");
                    assertFalse(r.all().contains(BANNER),
                            "and it printed no project banner:\n" + r.all());
                })

                .runAll();
    }

    /**
     * A laid-out home holding one unit whose {@code installed/} record carries
     * an error the closing report will find.
     *
     * <p>The record is written in the shape production writes — a real
     * {@code kind}/{@code installSource} pair — because the first version of
     * this fixture used {@code "LOCAL"}, which is not one of them: the record
     * failed to parse, the unit was listed from disk with no source, and the
     * closing report had nothing to say about it. Both arms printed nothing and
     * the defect looked fixed.
     */
    private static Path homeWithAnOutstandingError(String label) throws Exception {
        Path tmp = Files.createTempDirectory("help-is-text-" + label + "-");
        Path store = tmp.resolve("home").resolve(".skill-manager");
        new SkillStore(store).init();
        Path unit = Files.createDirectories(store.resolve("skills").resolve("probe-unit"));
        Files.writeString(unit.resolve("SKILL.md"),
                "---\nname: probe-unit\ndescription: fixture\n---\nbody\n",
                StandardCharsets.UTF_8);
        Files.writeString(store.resolve("installed").resolve("probe-unit.json"), """
                {
                  "name" : "probe-unit",
                  "version" : "0.1.0",
                  "kind" : "GIT",
                  "installSource" : "GIT",
                  "origin" : "https://example.invalid/probe-unit.git",
                  "gitHash" : "0000000000000000000000000000000000000000",
                  "gitRef" : "main",
                  "installedAt" : "2026-01-01T00:00:00Z",
                  "errors" : [ {
                    "kind" : "AGENT_SYNC_FAILED",
                    "message" : "fixture: this unit has an outstanding error",
                    "firstSeenAt" : "2026-01-01T00:00:00Z"
                  } ],
                  "unitKind" : "SKILL"
                }
                """, StandardCharsets.UTF_8);
        return store;
    }

    // ---------------------------------------------------------------- driver

    private record Run(int rc, String out, String err) {
        String all() { return out + err; }
    }

    /**
     * One invocation through the REAL entry point.
     *
     * <p>{@link SkillManagerCli#execute} rather than a reconstruction, because
     * the thing under test lives in the execution strategy — the closing report
     * runs from {@code completeExecution}, not from any command's
     * {@code call()}, so a test that drove the subcommand directly could not
     * see the defect at all.
     */
    /**
     * {@link #run} with {@code home} bound as the AMBIENT home for exactly one
     * invocation.
     *
     * <p>Needed because {@link #run} clears the agent-home overrides on its way
     * out, so a binding established once and used for two calls covers only the
     * first. That is precisely what happened here: the {@code --version} arm
     * was written with one {@code bind} around both calls, ran against the
     * ambient home, and passed under the mutation as well as without it. The
     * bind is per-invocation now, and the arm was re-run red to prove it.
     */
    private static Run bound(Path home, String... argv) {
        var displaced = AgentHomes.bind(home);
        try {
            return run(argv);
        } finally {
            AgentHomes.restoreOverrides(displaced);
        }
    }

    private static Run run(String... argv) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int rc = SkillManagerCli.execute(argv);
            System.out.flush();
            System.err.flush();
            return new Run(rc, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
            // execute() restores what it displaced, but a refused parse never
            // reaches the strategy that displaced anything. Cleared here so one
            // case in this suite cannot bind the home for the next.
            AgentHomes.clearOverrides();
        }
    }
}
