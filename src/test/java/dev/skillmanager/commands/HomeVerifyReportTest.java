package dev.skillmanager.commands;

import dev.skillmanager._lib.test.Tests;
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
 * What {@code home verify} SAYS, as opposed to what it finds.
 *
 * <h2>Why a presentation test, and why it is not a soft one</h2>
 *
 * <p>The check has always separated a live {@code SYMLINK_TARGET} from an
 * authored {@code CONTENT_REFERENCE}; {@link dev.skillmanager.store.HomeCloneTest}
 * covers that. What was never covered is the sentence the operator reads, and
 * the sentence is the whole product here — this command exists to be believed
 * by a person deciding whether a checkout is isolated.
 *
 * <p>Measured on a constituent checkout: 169 findings, 163 of them append-only
 * history files that merely quote a path, 6 of them live symlinks into the
 * operator's global home. The report printed the 163 first and in full, then
 * all 169 in one alphabetically sorted list. Every number was right and the
 * reader came away with the wrong one. So these assertions are about ORDER,
 * SEPARATION and VOLUME, not about the finding — an instrument whose signal is
 * 96% noise measures nothing that anybody acts on. Issue #133.
 */
public final class HomeVerifyReportTest {

    /** Enough authored mentions that enumerating them all would bury anything. */
    private static final int MENTIONS = 40;

    public static int run() throws Exception {
        return Tests.suite("HomeVerifyReportTest")

                .test("the default mode fails, and names the paths that resolve into another home", () -> {
                    Leaky fx = Leaky.build("default");

                    Result r = verify(fx, false);

                    assertEquals(1, r.rc, "a live path into another home is never tolerable");
                    assertContains(r.err, "resolve into another Skill Manager home",
                            "the default mode states the class of failure");
                    assertContains(r.err, "SYMLINK_TARGET skills/demo/test_graph/sdk",
                            "the absolute symlink is named in the DEFAULT mode, not only under --strict");
                    assertContains(r.err, "FOREIGN_HOME skills/demo/test_graph/standard-nodes",
                            "and so is the relative path that resolves into one");
                    assertTrue(!r.err.contains("CONTENT_REFERENCE"),
                            "authored mentions are not failures by default; got:\n" + r.err);
                })

                .test("the tolerated mentions are summarised, so they cannot bury the failures", () -> {
                    Leaky fx = Leaky.build("volume");

                    Result r = verify(fx, false);

                    assertContains(r.out, MENTIONS + " unit-content file(s) mention",
                            "the count of tolerated mentions is still reported in full");
                    assertContains(r.out, "… " + (MENTIONS - 3) + " more",
                            "and the enumeration is capped rather than printed entire");
                    assertEquals(3, countLines(r.out, "EVIDENCE-"),
                            "at most a sample of the tolerated mentions is listed, got:\n" + r.out);
                    // The point of the cap: the two lines that matter must be
                    // within reach of a reader who does not scroll.
                    assertTrue(lineIndex(r.err, "SYMLINK_TARGET") >= 0
                                    && lineIndex(r.err, "SYMLINK_TARGET") < 4,
                            "the isolation failures lead the error stream, got:\n" + r.err);
                })

                .test("--strict adds the mentions as failures and keeps the two counts apart", () -> {
                    Leaky fx = Leaky.build("strict");

                    Result r = verify(fx, true);

                    assertEquals(1, r.rc, "--strict still fails");
                    assertContains(r.err, "2 path(s) in",
                            "the count that must be repaired is stated on its own");
                    assertContains(r.err, "plus " + MENTIONS + " authored mention(s)",
                            "and the tolerated count is stated as a separate quantity, not summed in");
                    assertTrue(!r.err.contains((MENTIONS + 2) + " reference(s)"),
                            "the two kinds are never merged into one number; got:\n" + r.err);
                })

                .test("a home with only authored mentions passes by default and fails under --strict", () -> {
                    // The companion that shows the above can distinguish: same
                    // fixture minus the two live paths.
                    Leaky fx = Leaky.build("mentions-only");
                    Files.delete(fx.home.resolve("skills/demo/test_graph/standard-nodes"));
                    Files.delete(fx.home.resolve("skills/demo/test_graph/sdk"));

                    Result lenient = verify(fx, false);
                    assertEquals(0, lenient.rc, "authored mentions alone are tolerated: " + lenient.err);
                    assertContains(lenient.out, "no repairable reference to",
                            "and the verdict says which kind of clean it is");

                    Result strict = verify(fx, true);
                    assertEquals(1, strict.rc, "--strict promotes them to failures");
                    assertContains(strict.err, "fatal under --strict", "and says why it failed");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    /**
     * A home carrying all three reference kinds at once, which is the only
     * shape that can show one burying another.
     */
    private record Leaky(Path home, Path other) {

        static Leaky build(String label) throws Exception {
            Path root = Files.createTempDirectory("verify-report-" + label + "-");
            Path other = newHome(root.resolve("global"));
            Path home = newHome(root.resolve("child"));

            // CONTENT_REFERENCE: append-only records that merely quote a path.
            Path history = Files.createDirectories(
                    home.resolve("skills/demo/specs/.history"));
            for (int i = 0; i < MENTIONS; i++) {
                Files.writeString(history.resolve("EVIDENCE-%03d.md".formatted(i)),
                        "validated against " + other + "/skills/test-graph on 2026-01-01\n");
            }

            // SYMLINK_TARGET: an absolute link into the other home.
            Path testGraph = Files.createDirectories(home.resolve("skills/demo/test_graph"));
            Files.createDirectories(other.resolve("skills/test-graph/project_sdk_sources/sdk"));
            Files.createDirectories(
                    other.resolve("skills/test-graph/project_sdk_sources/standard-nodes"));
            Files.createSymbolicLink(testGraph.resolve("sdk"),
                    other.resolve("skills/test-graph/project_sdk_sources/sdk"));
            // FOREIGN_HOME: a RELATIVE target whose parent is the link above,
            // so nothing that reads link text can see where it lands.
            Files.createSymbolicLink(testGraph.resolve("standard-nodes"),
                    Path.of("sdk/../standard-nodes"));
            return new Leaky(home, other);
        }
    }

    private static Path newHome(Path root) throws Exception {
        SkillStore store = new SkillStore(root);
        store.init();
        return root;
    }

    // --------------------------------------------------------------- plumbing

    private record Result(int rc, String out, String err) {}

    private static Result verify(Leaky fx, boolean strict) throws Exception {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            String[] args = strict
                    ? new String[] { "--home", fx.home.toString(),
                            "--against", fx.other.toString(), "--strict" }
                    : new String[] { "--home", fx.home.toString(),
                            "--against", fx.other.toString() };
            int rc = new CommandLine(new HomeCommand.VerifyCmd()).execute(args);
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }

    private static int countLines(String text, String needle) {
        int n = 0;
        for (String line : text.split("\n")) if (line.contains(needle)) n++;
        return n;
    }

    /** Index of the first line containing {@code needle}, or -1. */
    private static int lineIndex(String text, String needle) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(needle)) return i;
        return -1;
    }
}
