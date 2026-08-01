package dev.skillmanager.commands;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.store.HomeCloner;
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
 * Provisioning a clone reported but never completed.
 *
 * <h2>The state, not the sentence</h2>
 *
 * <p>{@code home clone} skips {@code cache/}, {@code venvs/}, {@code tools/}
 * and {@code npm/} — 1.6 GB of re-derivable toolchain — and the CLI shims that
 * pointed into them are left naming paths the copy does not hold. It said so,
 * once, in the middle of a successful clone, and named
 * {@code sync --force-scripts} as the repair. Across a 24-repository fan-out
 * that is one manual step per checkout that nobody performs, and the failure it
 * prevents does not surface until some later tool execs a shim pointing at
 * nothing.
 *
 * <p>So the state is now something {@code home verify} refuses on. Two
 * properties matter and both are asserted here, because either alone is a bad
 * gate: it must FIRE while the provisioning is incomplete, and it must CLEAR
 * itself the moment the provisioning is done — re-derived from the home on
 * every run rather than remembered, so it can neither go stale nor become a
 * permanent failure that operators route around. Issue #133.
 */
public final class HomeUnresolvedGateTest {

    public static int run() throws Exception {
        return Tests.suite("HomeUnresolvedGateTest")

                .test("`home verify` refuses a home whose CLI shims point at nothing", () -> {
                    Homes fx = Homes.build("gate");
                    // Exactly the shape a clone leaves: a generated shim whose
                    // exec target lives under the skipped `cache/`.
                    shim(fx.home, "computeq", fx.home + "/cache/skill-script-x/venv/bin/computeq");

                    Result r = verify(fx);

                    assertEquals(1, r.rc, "an incomplete provisioning is refused, not reported");
                    assertContains(r.err, "do not resolve", "the state is named");
                    assertContains(r.err, "bin/cli/computeq -> " + fx.home + "/cache",
                            "and the exact reference is named");
                    assertContains(r.err, "sync --force-scripts",
                            "with the command that completes it");
                })

                .test("and clears itself once the provisioning is completed", () -> {
                    Homes fx = Homes.build("clear");
                    Path target = fx.home.resolve("cache/skill-script-x/venv/bin/computeq");
                    shim(fx.home, "computeq", target.toString());
                    assertEquals(1, verify(fx).rc, "fires while the target is absent");

                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "#!/bin/sh\n");

                    Result r = verify(fx);
                    assertEquals(0, r.rc, "and passes once it exists: " + r.err);
                })

                .test("a missing logs/ or tmp/ path is not an incomplete provisioning", () -> {
                    // The gate has to survive a fresh home nobody has run yet.
                    // A rule that fires on every clone is a rule nobody keeps.
                    Homes fx = Homes.build("noise");
                    shim(fx.home, "noisy", fx.home + "/logs/gateway.log");

                    Result r = verify(fx);
                    assertEquals(0, r.rc, "a log file that does not exist yet is normal: " + r.err);
                })

                .test("the clone reports the same set the gate will later refuse on", () -> {
                    Homes fx = Homes.build("clone");
                    shim(fx.other, "computeq", fx.other + "/cache/skill-script-x/venv/bin/computeq");
                    Path dest = Files.createTempDirectory("unresolved-dest-").resolve("home");

                    HomeCloner.Report report = HomeCloner.cloneHome(fx.other, dest);

                    assertTrue(!report.danglingReferences().isEmpty(),
                            "the clone sees the shim it is about to hand over broken");
                    HomeCloner.Verification after = HomeCloner.verify(fx.other, dest, false);
                    assertEquals(report.danglingReferences().size(), after.unresolved().size(),
                            "and the gate re-derives the same count from the copy alone");
                })

                .runAll();
    }

    // ------------------------------------------------------------- fixture

    private record Homes(Path home, Path other) {
        static Homes build(String label) throws Exception {
            Path root = Files.createTempDirectory("unresolved-" + label + "-");
            return new Homes(newHome(root.resolve("child")), newHome(root.resolve("global")));
        }
    }

    private static Path newHome(Path root) throws Exception {
        new SkillStore(root).init();
        return root;
    }

    /** A generated CLI shim, which is what {@code bin/cli/} holds. */
    private static void shim(Path home, String name, String target) throws Exception {
        Path dir = Files.createDirectories(home.resolve("bin/cli"));
        Files.writeString(dir.resolve(name), "#!/bin/sh\nexec \"" + target + "\" \"$@\"\n");
    }

    // --------------------------------------------------------------- plumbing

    private record Result(int rc, String out, String err) {}

    private static Result verify(Homes fx) {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = new CommandLine(new HomeCommand.VerifyCmd()).execute(
                    "--home", fx.home.toString(), "--against", fx.other.toString());
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
    }
}
