package dev.skillmanager.cli;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.observability.CliObservability;
import dev.skillmanager.util.Log;
import io.opentelemetry.context.Scope;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * <b>A refusal is a message, not a crash.</b>
 *
 * <p>Regression guard for the onboarding walk's finding that expected
 * refusals reached the operator as raw Java stack traces — 33 frames across
 * two refusals in one graph run:
 *
 * <pre>
 * $ skill-manager uninstall ob-alpha --dry-run
 * java.io.IOException: unit ob-alpha is claimed by skill project(s): … (remove the project lock/binding first)
 *     at dev.skillmanager.app.RemoveUseCase.buildProgram(RemoveUseCase.java:77)
 *     … 14 more …
 * </pre>
 *
 * <p>Every exception that was not one of the four banner types was rethrown
 * into picocli's default handler, which prints the whole stack.
 *
 * <h2>The companion — this is the assertion that could pass by not looking</h2>
 *
 * <p>"No stack frames were printed" is trivially true of a run that printed
 * nothing, and of a check whose frame pattern is wrong. So every case here
 * asserts the MESSAGE reached stderr and the exit code is non-zero before it
 * asserts the frame count, and two cases in the same suite prove the frame
 * detector can fire: an exception carrying no message of its own still prints
 * its trace, and so does any failure under {@code --verbose}. A pattern that
 * matched nothing would fail those two.
 */
public final class CliRefusalsAreMessagesTest {

    /**
     * The same structural pattern the {@code onboarding} graph uses —
     * {@code at <qualified.name>(<File>.java:<line>)}. Deliberately not a
     * search for the word "Exception": the useful half of a refusal
     * legitimately names exception types.
     */
    private static final Pattern STACK_FRAME =
            Pattern.compile("^\\s*at [\\w.$]+\\(.*\\.java:\\d+\\)\\s*$");

    /** The exact shape of the refusal the walk found first. */
    private static final String PROJECT_CLAIM_REFUSAL =
            "unit ob-alpha is claimed by skill project(s): acme-widgets "
                    + "(remove the project lock/binding first)";

    public static int run() {
        return Tests.suite("CliRefusalsAreMessagesTest")
                .test("a refusal reaches stderr as its message, with no stack frames", () -> {
                    Rendered r = render(new IOException(PROJECT_CLAIM_REFUSAL), false);

                    assertEquals(1, r.exitCode, "a refusal still exits non-zero (1)");
                    assertContains(r.stderr, PROJECT_CLAIM_REFUSAL,
                            "the refusal message itself survives");
                    assertEquals(0, frames(r.stderr).size(),
                            "a refusal prints zero stack frames, was 15");
                    assertContains(r.stderr, "--verbose",
                            "the trace is still reachable, and the message says how");
                })
                .test("a library exception with a message is a refusal too", () -> {
                    // `project register` on a `[[skills]]` manifest surfaced
                    // org.tomlj.TomlInvalidTypeException, 18 frames, thrown
                    // from a library — which is why the fix cannot be a marker
                    // interface on our own throw sites.
                    Rendered r = render(new IllegalStateException(
                            "Value of array element 0 is a table, expected string"), false);

                    assertEquals(1, r.exitCode, "still exits non-zero");
                    assertContains(r.stderr, "expected string", "the library message survives");
                    assertEquals(0, frames(r.stderr).size(),
                            "a third-party refusal prints zero stack frames, was 18");
                })
                .test("a wrapped refusal reports the cause that carries the message", () -> {
                    Rendered r = render(new java.io.UncheckedIOException(
                            new IOException(PROJECT_CLAIM_REFUSAL)), false);

                    assertContains(r.stderr, PROJECT_CLAIM_REFUSAL,
                            "the message one wrap down is the one printed");
                    assertEquals(0, frames(r.stderr).size(), "still no frames");
                })
                .test("COMPANION: a failure with nothing to say still prints its trace", () -> {
                    // The check must be able to fire. An NPE with no message
                    // is a bug, not a refusal, and for it the trace IS the
                    // diagnostic — printed whether or not --verbose was given.
                    Rendered r = render(new NullPointerException(), false);

                    assertEquals(1, r.exitCode, "a bug also exits non-zero");
                    assertContains(r.stderr, "NullPointerException",
                            "the type is named when there is no message");
                    assertTrue(frames(r.stderr).size() > 0,
                            "a failure with no message still prints frames — "
                                    + "so 'zero frames' above is a measurement, not a blind spot");
                })
                .test("COMPANION: --verbose prints the message AND the trace", () -> {
                    Rendered r = render(new IOException(PROJECT_CLAIM_REFUSAL), true);

                    assertContains(r.stderr, PROJECT_CLAIM_REFUSAL, "message still printed");
                    assertTrue(frames(r.stderr).size() > 0,
                            "--verbose restores the frames the default hid");
                })
                .runAll();
    }

    // ------------------------------------------------------------- harness

    private record Rendered(int exitCode, String stderr) {}

    /**
     * Drive {@link SkillManagerCli#handleExecutionException} exactly as picocli
     * does, with {@code user.home} redirected so nothing consults — or creates
     * — the operator's real home.
     */
    private static Rendered render(Exception ex, boolean verbose) throws Exception {
        CommandLine cmd = new CommandLine(new SkillManagerCli());
        CommandLine.ParseResult pr = cmd.parseArgs("uninstall", "ob-alpha", "--dry-run");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        String originalUserHome = System.getProperty("user.home");
        boolean originalVerbose = Log.isVerbose();
        Path tmpHome = Files.createTempDirectory("sm-refusal-home-");
        CliObservability telemetry = CliObservability.configure(disabledExporters());
        int rc;
        try {
            System.setProperty("user.home", tmpHome.toString());
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Log.setVerbose(verbose);
            try (Scope ignored = telemetry.makeCurrent()) {
                rc = SkillManagerCli.handleExecutionException(ex, cmd, pr);
            }
        } finally {
            Log.setVerbose(originalVerbose);
            System.setErr(originalErr);
            System.setProperty("user.home", originalUserHome);
            telemetry.flushAndClose(500);
        }
        return new Rendered(rc, err.toString(StandardCharsets.UTF_8));
    }

    private static List<String> frames(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (STACK_FRAME.matcher(line).matches()) out.add(line.strip());
        }
        return out;
    }

    private static Map<String, String> disabledExporters() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_TRACES_EXPORTER", "none");
        env.put("OTEL_METRICS_EXPORTER", "none");
        env.put("OTEL_LOGS_EXPORTER", "none");
        return env;
    }
}
