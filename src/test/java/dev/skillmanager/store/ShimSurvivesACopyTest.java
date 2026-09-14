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

        // ---------------------------------------------------------------- OHV-4

        suite.test("OHV-4: a HALF-rewritten shim is reported on content and re-anchored completely", () -> {
            Path home = newHome();
            Path shim = halfRewrittenShim(home, "computeq");

            java.util.List<String> frozen = ShimHomeContract.frozenHomeLines(home, shim);
            assertEquals(1, frozen.size(), "exactly the exec line is frozen; got " + frozen);
            assertTrue(frozen.get(0).startsWith("exec "), "and it is the exec line: " + frozen);

            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
            assertTrue(rewritten != null,
                    "the token already being in the shim is no longer 'already rewritten'");
            assertFalse(rewritten.contains(home.toString()), "no given spelling survives: " + rewritten);
            assertFalse(rewritten.contains(home.toRealPath().toString()), "no real spelling survives");
            assertContains(rewritten, "exec \"${SKILL_MANAGER_SHIM_HOME}/cache/skill-script-deploy-helm-computeq/venv/bin/computeq\" \"$@\"",
                    "the exec line derives the home");
            assertEquals(1, (int) rewritten.lines().filter(l -> l.startsWith("SKILL_MANAGER_SHIM_HOME=")).count(),
                    "no second preamble: the existing assignment precedes the rewritten line");
            assertEquals(1, (int) rewritten.lines().filter(l -> l.startsWith("#!")).count(), "one shebang");

            Files.writeString(shim, rewritten);
            assertTrue(ShimHomeContract.frozenHomeLines(home, shim).isEmpty(), "clean after the rewrite");
            assertTrue(ShimHomeContract.selfDerivingRewrite(home, shim) == null, "and a second pass is a no-op");
        });

        suite.test("OHV-4: the rewritten half shim runs its tool from a COPY of the home", () -> {
            Path home = newHome();
            Path shim = halfRewrittenShim(home, "computeq");
            Files.writeString(shim, ShimHomeContract.selfDerivingRewrite(home, shim));
            Path copy = Files.createTempDirectory("shim-half-copy-").resolve("home");
            copyTree(home, copy);
            // The source's tool is removed: only the copy's can answer.
            Files.delete(home.resolve("cache/skill-script-deploy-helm-computeq/venv/bin/computeq"));
            Process p = new ProcessBuilder("bash", copy.resolve("bin/cli/computeq").toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            assertEquals(0, p.waitFor(), "the copied shim runs the COPY's tool: " + out);
            assertContains(out, "computeq-ran", "and it is the tool, not a dangling path");
        });

        suite.test("OHV-4: the home root itself on an assignment line is frozen too", () -> {
            Path home = newHome();
            Path shim = home.resolve("bin/cli/root-assign");
            Files.writeString(shim, "#!/usr/bin/env bash\nSM_HOME=\"" + home + "\"\n"
                    + "exec cat \"$SM_HOME/skills/x/SKILL.md\"\n");
            assertEquals(1, ShimHomeContract.frozenHomeLines(home, shim).size(),
                    "SM_HOME=\"<home>\" spells the home as surely as a longer path does");
            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
            assertContains(rewritten, "SM_HOME=\"${SKILL_MANAGER_SHIM_HOME}\"", "re-anchored whole");
        });

        suite.test("OHV-4: a sibling home that shares the prefix is not this home", () -> {
            Path home = newHome();
            Path sibling = home.resolveSibling(home.getFileName() + "-other");
            Path shim = home.resolve("bin/cli/sibling");
            Files.writeString(shim, "#!/usr/bin/env bash\nexec \"" + sibling + "/venvs/x/bin/t\" \"$@\"\n");
            assertTrue(ShimHomeContract.frozenHomeLines(home, shim).isEmpty(),
                    "<home>-other is a different directory, not a longer path under <home>");
            assertTrue(ShimHomeContract.selfDerivingRewrite(home, shim) == null, "and nothing is rewritten");
        });

        // #341 "Watch for": prose about a path is not a reference to one. Since
        // OHV-2 verify fails on every repair finding, so a comment-only match
        // would turn verify red on a shim that runs correctly.
        suite.test("OHV-4: a home spelled ONLY in a # comment is not a frozen line", () -> {
            Path home = newHome();
            Path shim = home.resolve("bin/cli/commented");
            Files.writeString(shim, "#!/usr/bin/env bash\n"
                    + "# generated for " + home + "/skills/acme by its installer\n"
                    + "exec true \"$@\"\n");
            assertTrue(ShimHomeContract.frozenHomeLines(home, shim).isEmpty(),
                    "the detector does not read comment lines");
            // The REWRITE contract is unchanged from before OHV-4: it re-anchors
            // every line, comments included, when it is asked to. The installer
            // asks; `home repair --fix` does not (no finding), which
            // DamagedHomeIsRepairableTest pins as byte-identical.
            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
            assertTrue(rewritten != null, "the rewrite still offers to re-anchor the comment");
            assertFalse(rewritten.contains(home.toString()), "and would leave no spelling: " + rewritten);
        });

        suite.test("OHV-4: a venv-internal shebang is deliberately NOT reported", () -> {
            Path home = newHome();
            Path shim = home.resolve("bin/cli/console-script");
            Files.writeString(shim, "#!" + home + "/venvs/x/bin/python\n"
                    + "import sys\nfrom x.cli import main\nsys.exit(main())\n");
            assertTrue(ShimHomeContract.frozenHomeLines(home, shim).isEmpty(),
                    "the kernel reads a shebang literally, no token can live there, and venvs/ "
                            + "is re-provisioned: out of scope by design");
        });

        suite.test("OHV-4: a non-shell file spelling the home on an exec line is reported, not rewritten", () -> {
            Path home = newHome();
            Path shim = home.resolve("bin/cli/no-shebang");
            Files.writeString(shim, "exec \"" + home + "/venvs/x/bin/t\" \"$@\"\n");
            assertEquals(1, ShimHomeContract.frozenHomeLines(home, shim).size(),
                    "reported on content, with or without a rewrite on offer");
            assertTrue(ShimHomeContract.selfDerivingRewrite(home, shim) == null,
                    "no shebang: not a shape the rewrite understands");
        });

        suite.test("OHV-4: a home named by another spelling of itself is still this home", () -> {
            Path home = newHome();
            Path real = home.toRealPath();
            Path shim = home.resolve("bin/cli/other-spelling");
            // On macOS the temp home is given as /var/... and is really
            // /private/var/...; write the spelling the caller did NOT pass.
            Files.writeString(shim, "#!/usr/bin/env bash\nexec \"" + real + "/venvs/x/bin/t\" \"$@\"\n");
            assertEquals(1, ShimHomeContract.frozenHomeLines(home, shim).size(),
                    "the real spelling of a home given through a symlink is this home");
            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
            assertFalse(rewritten.contains(real.toString()), "and it is re-anchored: " + rewritten);
            assertFalse(rewritten.contains("/private${"), "longest spelling first, no /private${...} residue");
        });

        return suite.runAll();
    }

    private static Path newHome() throws Exception {
        Path home = Files.createTempDirectory("shim-home-").resolve("home");
        Files.createDirectories(home.resolve("bin/cli"));
        return home;
    }

    /**
     * DEF-OHV-001: the root home's bin/cli/computeq, as measured 2026-09-13 — the
     * token in the preamble and the export line, this home literal on the exec
     * line — plus the tool it execs, which prints a marker. Its anchor is the
     * pre-DEF-OHV-190 {@code ${BASH_SOURCE[0]:-$0}} line on purpose: that is
     * what such shims carry, and under bash it must survive the repair.
     */
    private static Path halfRewrittenShim(Path home, String name) throws Exception {
        Path tool = home.resolve("cache/skill-script-deploy-helm-" + name + "/venv/bin/" + name);
        Files.createDirectories(tool.getParent());
        Files.writeString(tool, "#!/bin/sh\necho " + name + "-ran\n");
        tool.toFile().setExecutable(true);
        Path shim = home.resolve("bin/cli").resolve(name);
        Files.writeString(shim, "#!/usr/bin/env bash\n"
                + "# Rewritten by skill-manager: resolve the home this shim is standing in\n"
                + "# rather than the one it was written into, so a copy of the home works.\n"
                + "SKILL_MANAGER_SHIM_HOME=\"$(cd \"$(dirname \"${BASH_SOURCE[0]:-$0}\")/../..\" && pwd)\"\n"
                + "export MONITORING_DEPLOY_CDC_ROOT=\"${SKILL_MANAGER_SHIM_HOME}/skills/deploy-helm\"\n"
                + "exec \"" + tool + "\" \"$@\"\n");
        return shim;
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
