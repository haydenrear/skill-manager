package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * OUN-10: an entry under {@code bin/cli} resolves the home it is STANDING IN.
 *
 * <p>Every assertion here is made on a COPIED home, and that is the ticket's
 * stated constraint rather than a stylistic choice. A frozen shim works
 * perfectly where it was written — which is exactly why this survived from
 * the day the first unit shipped an absolute wrapper. The warning that has
 * been in {@code SkillScriptBackend} all along says so in as many words: "it
 * goes wrong only later, in a home that does not exist yet."
 *
 * <p>The copy here is a plain recursive copy, NOT {@code home clone}. Measured
 * 2026-09-06: a clone re-anchors these shims and a clone is therefore fine; a
 * {@code cp -R} — what a container image build does — does not, so the image
 * builds green and dies at first use.
 */
public final class ShimSurvivesACopyTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ShimSurvivesACopyTest");

        suite.test("a frozen shim is rewritten to derive its own home", () -> {
            Path home = newHome();
            Path shim = frozenShim(home, "acme-tool");

            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);

            assertTrue(rewritten != null, "the shim is rewritable");
            assertFalse(rewritten.contains(home.toString()),
                    "no absolute home path survives the rewrite");
            assertContains(rewritten, ShimHomeContract.SHIM_HOME_VAR,
                    "the home is derived into a named variable");
            assertContains(rewritten, "skills/acme/run.py",
                    "and the path INSIDE the home is preserved — this relocates the "
                            + "shim, it does not repoint it");
            assertTrue(rewritten.startsWith("#!"),
                    "the shebang stays the first line, or the kernel will not run it");
        });

        suite.test("the rewritten shim resolves inside a COPY, not the original", () -> {
            Path home = newHome();
            Path shim = frozenShim(home, "acme-tool");
            Files.writeString(shim, ShimHomeContract.selfDerivingRewrite(home, shim));

            Path copy = Files.createTempDirectory("shim-copy-").resolve("home");
            copyTree(home, copy);
            Path copied = copy.resolve("bin/cli/acme-tool");

            assertEquals(0, ShimHomeContract.frozenHomePaths(copy, copied).size(),
                    "the copy's shim names no absolute home path at all");
            assertEquals(0, ShimHomeContract.frozenHomePaths(home, copied).size(),
                    "and in particular does not name the home it was written in — "
                            + "which is what a container image would have shipped");
        });

        // THE CONTROL, and without it "no frozen paths" is also what a shim
        // that was emptied, deleted, or never written would report.
        suite.test("CONTROL: the shim still names the script it is supposed to run", () -> {
            Path home = newHome();
            Path shim = frozenShim(home, "acme-tool");
            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
            Files.writeString(shim, rewritten);

            String body = Files.readString(shim);
            assertContains(body, "run.py", "the target survives");
            assertContains(body, "exec", "and it is still an exec wrapper");
            // (int), because assertEquals compares boxed values and a Long 1
            // is not an Integer 1 — they print identically, which makes the
            // failure message "expected <1> but was <1>".
            assertEquals(1, (int) body.lines().filter(l -> l.startsWith("#!")).count(),
                    "exactly one shebang — a second would mean the preamble landed wrong");
        });

        suite.test("a shim with no frozen path is left alone", () -> {
            Path home = newHome();
            Path shim = home.resolve("bin/cli/already-fine");
            Files.createDirectories(shim.getParent());
            Files.writeString(shim, "#!/usr/bin/env bash\nexec python3 ./relative.py \"$@\"\n");

            assertTrue(ShimHomeContract.selfDerivingRewrite(home, shim) == null,
                    "nothing to do, so nothing is done — the rewrite is not a reformat");
        });

        suite.test("a shim that is not a shell script is refused, not half-rewritten", () -> {
            Path home = newHome();
            Path shim = home.resolve("bin/cli/compiled");
            Files.createDirectories(shim.getParent());
            // A Python console script: frozen interpreter shebang, and a body
            // whose shape this rewrite does not understand.
            Files.writeString(shim, "#!" + home + "/venvs/x/bin/python\n"
                    + "import sys\nsys.path.insert(0, \"" + home + "/skills/acme\")\n");

            assertTrue(ShimHomeContract.selfDerivingRewrite(home, shim) == null,
                    "refused — half-rewriting a shape we do not understand is worse "
                            + "than the freeze, and frozenHomePaths still reports it");
            assertTrue(ShimHomeContract.frozenHomePaths(home, shim).size() > 0,
                    "and it IS still reported, so refusing is not the same as ignoring");
        });

        suite.test("rewriting is idempotent", () -> {
            Path home = newHome();
            Path shim = frozenShim(home, "acme-tool");
            Files.writeString(shim, ShimHomeContract.selfDerivingRewrite(home, shim));

            assertTrue(ShimHomeContract.selfDerivingRewrite(home, shim) == null,
                    "a second pass finds nothing to do — sync runs this on every install");
        });

        return suite.runAll();
    }

    private static Path newHome() throws Exception {
        Path home = Files.createTempDirectory("shim-home-").resolve("home");
        Files.createDirectories(home.resolve("bin/cli"));
        return home;
    }

    /** The exact shape measured on this repository's own tla-spec-dev shim. */
    private static Path frozenShim(Path home, String name) throws Exception {
        Path shim = home.resolve("bin/cli").resolve(name);
        Files.writeString(shim, "#!/usr/bin/env bash\n"
                + "exec python3 \"" + home + "/skills/acme/run.py\" \"$@\"\n");
        return shim;
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }
}
