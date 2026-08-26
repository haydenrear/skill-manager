package dev.skillmanager.commands;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.SkillManagerCli;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A remedy that does not run is not a remedy.</b>
 *
 * <p>Two findings from the onboarding walk, one class:
 *
 * <ol>
 *   <li>{@code home clone} and {@code bootstrap-home.sh} both print
 *       "{@code skill-manager home verify} refuses this home until you do",
 *       naming no arguments. An agent holding only the home path ran
 *       {@code home verify --root <home>} and got exit <b>2</b>,
 *       {@code Missing required options: '--home=<home>', '--against=<against>'}.
 *       {@code --against} was mandatory with no default, so the check the tool
 *       told the reader to run could not be run by a reader who had only been
 *       told about one home.</li>
 *   <li>{@code install} accepts {@code --yes}; {@code uninstall} did not
 *       ({@code Unknown option: '--yes'}, exit 2) — an asymmetry learnable only
 *       by failing.</li>
 * </ol>
 *
 * <h2>The companion — why "exit != 2" is not enough on its own</h2>
 *
 * <p>"The command did not produce a usage error" would also hold for a command
 * that quietly ignored the flag, or for a {@code home verify} that accepted
 * {@code --root} and then checked nothing. So each case here asserts what the
 * run actually DID: the no-{@code --against} verify must state, in its own
 * output, which half of the check it did not perform, and it must still catch
 * a planted unresolvable reference — the very failure the printed remedy
 * exists to repair. And the suite keeps a case where a usage error is still
 * the right answer ({@code home verify} with no home at all), so "no usage
 * errors anywhere" cannot pass by the parser having been made permissive.
 */
public final class RemediesAreRunnableTest {

    public static int run() throws Exception {
        return Tests.suite("RemediesAreRunnableTest")

                .test("`home verify --root <home>` runs, rather than being a usage error", () -> {
                    Path home = newHome("verify-root");

                    Result r = verify("--root", home.toString());

                    assertTrue(r.rc != 2,
                            "the spelling every printed remedy implies is not a usage error, got rc="
                                    + r.rc + "\n" + r.err);
                    assertTrue(!r.err.contains("Missing required options"),
                            "and specifically not THE usage error: " + r.err);
                    assertEquals(0, r.rc, "a clean home verifies clean: " + r.err);
                })

                .test("without --against the run states which half it did not check", () -> {
                    Path home = newHome("verify-scope");

                    Result r = verify("--home", home.toString());

                    assertEquals(0, r.rc, "a clean home still passes: " + r.err);
                    assertContains(r.out, "NOT CHECKED",
                            "the narrowed scope is stated BEFORE the findings");
                    assertContains(r.out, "--against",
                            "and the run names the flag that would widen it");
                    assertContains(r.out, "source-reference check not run",
                            "the verdict repeats its own scope rather than over-claiming");
                })

                .test("COMPANION: the no---against run still catches what the remedy repairs",
                        () -> {
                            // The printed remedy is about provisioning that never
                            // completed. If `--root` parsed but checked nothing,
                            // this home would still verify clean.
                            Path home = newHome("verify-unresolved");
                            Files.createDirectories(home.resolve("bin/cli"));
                            Files.createSymbolicLink(home.resolve("bin/cli/ob-shim"),
                                    home.resolve("venvs/ob/bin/ob-shim"));

                            Result r = verify("--root", home.toString());

                            assertEquals(1, r.rc, "an unresolvable reference still refuses");
                            assertContains(r.err, "do not resolve",
                                    "and names the class of failure");
                            // ARTI-06: the remedy is the per-artifact repair the
                            // per-instance diagnosis always implied, not the
                            // whole-home `sync --force-scripts` it used to be.
                            // This home has no cli-lock row behind its planted
                            // shim, so the artifact join finds nothing and this
                            // case exercises the FALLBACK spelling — still a
                            // runnable command rather than a bare sentence.
                            // ArtifactBuildTest covers the scoped spelling,
                            // where the artifacts are named one by one.
                            assertContains(r.err, "build --stale",
                                    "and prints the remedy that repairs it");
                        })

                .test("COMPANION: no home at all is still refused, and names the home it wants",
                        () -> {
                            Result r = verify("--strict");

                            assertEquals(2, r.rc, "a genuinely missing argument is still exit 2");
                            assertContains(r.err, "home verify --home <home>",
                                    "but the refusal spells a runnable command");
                            assertTrue(!r.err.contains("Missing required options"),
                                    "and does not name --against as required: " + r.err);
                        })

                .test("`uninstall --yes` parses, the way `install --yes` does", () -> {
                    CommandLine cli = new CommandLine(new SkillManagerCli());
                    // parseArgs, not execute: the property is that the CLI
                    // SURFACE agrees on the flag. Executing would remove a unit.
                    cli.parseArgs("install", "file:///nowhere", "--yes");
                    cli.parseArgs("uninstall", "ob-alpha", "--yes", "--dry-run");
                    cli.parseArgs("uninstall", "ob-alpha", "-y", "--dry-run");
                })

                .test("COMPANION: a flag neither half has is still rejected", () -> {
                    // Proves the parse assertions above measure something: the
                    // fix added one option, it did not stop picocli checking.
                    CommandLine cli = new CommandLine(new SkillManagerCli());
                    boolean rejected = false;
                    try {
                        cli.parseArgs("uninstall", "ob-alpha", "--certainly-not-an-option");
                    } catch (CommandLine.ParameterException expected) {
                        rejected = true;
                    }
                    assertTrue(rejected, "an unknown option is still an unknown option");
                })

                .runAll();
    }

    // --------------------------------------------------------------- plumbing

    private record Result(int rc, String out, String err) {}

    private static Path newHome(String label) throws Exception {
        Path root = Files.createTempDirectory("remedy-runnable-" + label + "-");
        Path home = root.resolve("home");
        new SkillStore(home).init();
        return home;
    }

    private static Result verify(String... args) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.VerifyCmd()).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }
}
