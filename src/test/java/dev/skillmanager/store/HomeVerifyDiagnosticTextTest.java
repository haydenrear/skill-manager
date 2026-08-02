package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.HomeCommand;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * A persisted error MESSAGE that quotes another home is not an isolation leak.
 * Issue #144.
 *
 * <h2>What the operator was sent after</h2>
 *
 * <p>A failed {@code project sync} writes its message into
 * {@code installed/<unit>.json}, and the message contains the path it failed
 * on — which was inside another home. The reference scan matched the bytes and
 * filed ten findings as {@code FILE_CONTENT installed/*.json (state)} under
 * <b>"paths that resolve into another Skill Manager home"</b>, the category
 * reserved for a live {@code SYMLINK_TARGET}. Nothing resolves through a
 * sentence; an operator chasing an isolation problem chased a message.
 *
 * <h2>What must NOT happen</h2>
 *
 * <p>The correction is one narrow downgrade and it must not become "JSON in
 * {@code installed/} is exempt". A record holding a home path in {@code origin}
 * — a live reference field — is still a hard leak, and it is still a hard leak
 * when the very same record also carries a diagnostic message. That is the
 * second case below, and it is the one that would fail on an over-correction.
 */
public final class HomeVerifyDiagnosticTextTest {

    public static int run() throws Exception {
        return Tests.suite("HomeVerifyDiagnosticTextTest")

                .test("an error message quoting another home is not an isolation failure", () -> {
                    Fixture fx = Fixture.build("diagnostic");
                    writeRecord(fx.home, "deploy-helm",
                            "\"origin\": \"github:acme/deploy-helm\"",
                            "child home path already exists: " + fx.other
                                    + "/bin/cli/computeq");

                    HomeCloner.Verification v = HomeCloner.verify(fx.other, fx.home, false);

                    assertTrue(v.isolated(),
                            "a description of another home is not a path into one; got: "
                                    + v.leaks());
                    assertEquals(java.util.List.of("installed/deploy-helm.json"),
                            v.diagnosticReferences(),
                            "and it is still reported, in its own category");

                    Result r = verify(fx, false);
                    assertEquals(0, r.rc, "so the home verifies: " + r.err);
                    assertContains(r.out, "inside a persisted error message",
                            "the report names what it actually found");
                    assertFalse(r.err.contains("resolve into another Skill Manager home"),
                            "and never under the isolation verdict; got:\n" + r.err
                                    + "\n---\n" + r.out);
                })

                .test("a live reference field in the same record is still a leak", () -> {
                    Fixture fx = Fixture.build("live-field");
                    // origin is read as a path. The record ALSO carries a
                    // diagnostic message naming the same home — the shape an
                    // over-broad exemption would wave through.
                    writeRecord(fx.home, "deploy-helm",
                            "\"origin\": \"" + fx.other + "/skills/deploy-helm\"",
                            "child home path already exists: " + fx.other
                                    + "/bin/cli/computeq");

                    HomeCloner.Verification v = HomeCloner.verify(fx.other, fx.home, false);

                    assertFalse(v.isolated(),
                            "a home path in a live field is a leak however much prose sits "
                                    + "beside it");
                    assertEquals("FILE_CONTENT", v.isolationFailures().get(0).kind(),
                            "and it is reported as one");
                    assertTrue(v.diagnosticReferences().isEmpty(),
                            "the record is not also counted as merely diagnostic");
                })

                .test("--strict still fails on the diagnostic message", () -> {
                    Fixture fx = Fixture.build("strict");
                    writeRecord(fx.home, "deploy-helm",
                            "\"origin\": \"github:acme/deploy-helm\"",
                            "child home path already exists: " + fx.other
                                    + "/bin/cli/computeq");

                    Result r = verify(fx, true);
                    assertEquals(1, r.rc, "--strict means no mention of any kind survives");
                    assertContains(r.err, "diagnostic message(s)",
                            "and it is named for what it is, not as an authored mention");
                })
                .runAll();
    }

    // ------------------------------------------------------------- fixture

    private record Fixture(Path home, Path other) {
        static Fixture build(String label) throws Exception {
            Path root = Files.createTempDirectory("verify-diagnostic-" + label + "-");
            return new Fixture(newHome(root.resolve("child")), newHome(root.resolve("global")));
        }
    }

    /** An {@code installed/<unit>.json} with one field and one error message. */
    private static void writeRecord(Path home, String unit, String originField, String message)
            throws Exception {
        Path file = home.resolve("installed").resolve(unit + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "name": "%s",
                  "version": "0.1.0",
                  "kind": "GIT",
                  "installSource": "GIT",
                  %s,
                  "installedAt": "2026-08-01T00:00:00Z",
                  "errors": [
                    {
                      "kind": "PROJECT_SYNC_FAILED",
                      "message": "%s",
                      "firstSeenAt": "2026-08-01T00:00:00Z"
                    }
                  ],
                  "unitKind": "SKILL"
                }
                """.formatted(unit, originField, message));
    }

    private static Path newHome(Path root) throws Exception {
        new SkillStore(root).init();
        return root;
    }

    private record Result(int rc, String out, String err) {}

    private static Result verify(Fixture fx, boolean strict) {
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
}
