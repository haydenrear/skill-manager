package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * DEF-OHV-190: the home anchor {@link ShimHomeContract#selfDerivingRewrite}
 * writes has to PARSE and RESOLVE under every shell its shebang check accepts.
 *
 * <p>The anchor used to be {@code ${BASH_SOURCE[0]:-$0}}. dash rejects the
 * array subscript as "Bad substitution", the command substitution yields
 * nothing, {@code cd /../..} lands on {@code /}, and the exec goes to
 * {@code //cache/...}: rc 127. macOS's {@code /bin/sh} is bash, so nothing on
 * the development machine showed it; Debian's {@code /bin/sh} is dash.
 *
 * <p>So these tests do not read the bytes for a string. They RUN the written
 * shim, under the interpreter its shebang names, from a COPY of the home whose
 * original has been deleted, and assert it reached the copy's tool. A shim that
 * still resolved the original, or {@code /}, fails.
 *
 * <p>A shell that is not installed is skipped with a message naming it, rather
 * than passing silently: {@code /bin/dash} ships with macOS and most Linux, and
 * the dash row is the one this defect is about.
 */
public final class ShimAnchorRunsUnderEveryShellTest {

    /** Shebang interpreter → the binary that runs it. */
    private static final List<String[]> SHELLS = List.of(
            new String[] {"/bin/dash", "/bin/dash"},
            new String[] {"/bin/sh", "/bin/sh"},
            new String[] {"/usr/bin/env bash", "bash"},
            new String[] {"/usr/bin/env zsh", "zsh"},
            new String[] {"/bin/ksh", "/bin/ksh"});

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("ShimAnchorRunsUnderEveryShellTest");

        for (String[] shell : SHELLS) {
            String shebang = shell[0];
            String binary = shell[1];
            suite.test("a frozen #!" + shebang + " shim, rewritten, runs its tool from a COPY of the home", () -> {
                if (!available(binary)) {
                    System.out.println("  [SKIP] " + binary + " is not installed; the #!" + shebang
                            + " row was NOT exercised");
                    return;
                }
                Path home = newHome();
                Path shim = home.resolve("bin/cli/acme");
                Files.writeString(shim, "#!" + shebang + "\n"
                        + "exec \"" + home + "/cache/acme/bin/acme\" \"$@\"\n", StandardCharsets.UTF_8);
                shim.toFile().setExecutable(true);
                tool(home);

                String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
                assertTrue(rewritten != null, "the frozen shim is rewritable");
                Files.writeString(shim, rewritten, StandardCharsets.UTF_8);

                Path copy = relocate(home);
                Path copied = copy.resolve("bin/cli/acme");

                // By path (the kernel reads the shebang) and through the named
                // interpreter explicitly (`dash shim`, which is how a Debian
                // `/bin/sh` shim runs even on a host whose /bin/sh is bash).
                Ran direct = exec(List.of(copied.toString(), "arg"));
                assertEquals(0, direct.exit, "#!" + shebang + " by path exited " + direct.exit
                        + ": " + direct.out + "\n--- shim ---\n" + rewritten);
                assertContains(direct.out, "acme-ran:arg", "and reached the COPY's tool");

                Ran viaInterpreter = exec(List.of(which(binary), copied.toString(), "arg"));
                assertEquals(0, viaInterpreter.exit, binary + " " + copied + " exited "
                        + viaInterpreter.exit + ": " + viaInterpreter.out + "\n--- shim ---\n" + rewritten);
                assertContains(viaInterpreter.out, "acme-ran:arg", "and reached the COPY's tool");
            });
        }

        // ------------------------------------------------ shims already on disk

        suite.test("an EXISTING #!/bin/sh shim carrying the bash-only anchor is reported, repaired, and then runs under dash", () -> {
            for (String shebang : List.of("/bin/sh", "/bin/dash", "/usr/bin/env sh", "/usr/bin/env dash")) {
                Path home = newHome();
                Path shim = legacyAnchoredShim(home, shebang);
                tool(home);

                assertFalse(ShimHomeContract.bashOnlyAnchorLines(shim).isEmpty(),
                        "#!" + shebang + ": the [0] anchor is named");
                HomeRepair.Finding finding = findingFor(home, "bin/cli/acme");
                assertTrue(finding != null && finding.kind() == HomeRepair.Kind.FROZEN_HOME_PATH_IN_SHIM
                                && finding.repairable(),
                        "#!" + shebang + ": reported as a repairable FROZEN_HOME_PATH_IN_SHIM; got " + finding);

                HomeRepair.repair(home);
                String healed = Files.readString(shim, StandardCharsets.UTF_8);
                assertFalse(healed.contains("BASH_SOURCE["), "the subscript is gone: " + healed);
                assertEquals(1, (int) healed.lines()
                                .filter(l -> l.startsWith(ShimHomeContract.SHIM_HOME_VAR + "=")).count(),
                        "one assignment, replaced in place, no second preamble: " + healed);
                assertTrue(ShimHomeContract.bashOnlyAnchorLines(shim).isEmpty(), "nothing left to name");
                assertEquals(null, findingFor(home, "bin/cli/acme"), "a second detection is clean");
                HomeRepair.repair(home);
                assertEquals(healed, Files.readString(shim, StandardCharsets.UTF_8),
                        "a second --fix changes no byte");

                if (available("/bin/dash")) {
                    Path copy = relocate(home);
                    Ran ran = exec(List.of("/bin/dash", copy.resolve("bin/cli/acme").toString(), "arg"));
                    assertEquals(0, ran.exit, "#!" + shebang + " repaired, under dash, exited "
                            + ran.exit + ": " + ran.out + "\n--- shim ---\n" + healed);
                    assertContains(ran.out, "acme-ran:arg", "and reached the copy's tool");
                } else {
                    System.out.println("  [SKIP] /bin/dash is not installed; the repaired #!" + shebang
                            + " shim was NOT run under dash");
                }
            }
        });

        suite.test("CONTROL: an EXISTING bash/zsh/ksh shim carrying the [0] anchor is NOT reported and is left byte-identical", () -> {
            // bash expands it; zsh and ksh parse the subscript on an unset name
            // and fall back to $0 (measured, see the shell matrix in the PR).
            for (String shebang : List.of("/usr/bin/env bash", "/bin/bash", "/bin/zsh", "/bin/ksh")) {
                Path home = newHome();
                Path shim = legacyAnchoredShim(home, shebang);
                tool(home);
                String before = Files.readString(shim, StandardCharsets.UTF_8);

                assertTrue(ShimHomeContract.bashOnlyAnchorLines(shim).isEmpty(),
                        "#!" + shebang + " runs the [0] anchor correctly");
                assertEquals(null, ShimHomeContract.selfDerivingRewrite(home, shim),
                        "#!" + shebang + ": no rewrite on offer");
                assertEquals(null, findingFor(home, "bin/cli/acme"), "#!" + shebang + ": no finding");
                HomeRepair.repair(home);
                assertEquals(before, Files.readString(shim, StandardCharsets.UTF_8),
                        "#!" + shebang + ": --fix leaves it byte-identical");
            }
        });

        suite.test("CONTROL: a half-rewritten BASH shim keeps its [0] anchor line; only the literal exec is re-anchored", () -> {
            Path home = newHome();
            Path shim = legacyAnchoredShim(home, "/usr/bin/env bash");
            String body = Files.readString(shim, StandardCharsets.UTF_8)
                    .replace("exec \"${" + ShimHomeContract.SHIM_HOME_VAR + "}", "exec \"" + home);
            Files.writeString(shim, body, StandardCharsets.UTF_8);
            String rewritten = ShimHomeContract.selfDerivingRewrite(home, shim);
            assertTrue(rewritten != null, "the literal exec is still re-anchored");
            assertContains(rewritten, LEGACY_ANCHOR, "and the working bash anchor is not churned");
        });

        suite.test("the anchor the rewrite writes carries no array subscript", () -> {
            assertFalse(ShimHomeContract.SHIM_HOME_ANCHOR.contains("["),
                    "dash cannot parse ${NAME[i]}: " + ShimHomeContract.SHIM_HOME_ANCHOR);
            assertTrue(ShimHomeContract.SHIM_HOME_ANCHOR.startsWith(ShimHomeContract.SHIM_HOME_VAR + "="),
                    "and it is an assignment of the token");
        });

        return suite.runAll();
    }

    /** The line 43e5fb99 introduced, as it sits in shims already written. */
    static final String LEGACY_ANCHOR = ShimHomeContract.SHIM_HOME_VAR
            + "=\"$(cd \"$(dirname \"${BASH_SOURCE[0]:-$0}\")/../..\" && pwd)\"";

    private static Path legacyAnchoredShim(Path home, String shebang) throws Exception {
        Path shim = home.resolve("bin/cli/acme");
        Files.writeString(shim, "#!" + shebang + "\n"
                + "# Rewritten by skill-manager: resolve the home this shim is standing in\n"
                + "# rather than the one it was written into, so a copy of the home works.\n"
                + LEGACY_ANCHOR + "\n"
                + "exec \"${" + ShimHomeContract.SHIM_HOME_VAR + "}/cache/acme/bin/acme\" \"$@\"\n",
                StandardCharsets.UTF_8);
        shim.toFile().setExecutable(true);
        return shim;
    }

    private static HomeRepair.Finding findingFor(Path home, String subject) {
        return HomeRepair.detect(home).findings().stream()
                .filter(f -> f.subject().equals(subject)).findFirst().orElse(null);
    }

    private static Path newHome() throws Exception {
        Path home = Files.createTempDirectory("shim-anchor-").resolve("home");
        Files.createDirectories(home.resolve("bin/cli"));
        return home;
    }

    private static void tool(Path home) throws Exception {
        Path tool = home.resolve("cache/acme/bin/acme");
        Files.createDirectories(tool.getParent());
        Files.writeString(tool, "#!/bin/sh\necho \"acme-ran:$1\"\n", StandardCharsets.UTF_8);
        tool.toFile().setExecutable(true);
    }

    /** Copy the home somewhere else and DELETE the original, so only a self-derived path can work. */
    private static Path relocate(Path home) throws Exception {
        Path copy = Files.createTempDirectory("shim-anchor-copy-").resolve("home");
        List<Path> all;
        try (var walk = Files.walk(home)) {
            all = walk.toList();
        }
        for (Path p : all) {
            Path target = copy.resolve(home.relativize(p).toString());
            if (Files.isDirectory(p)) Files.createDirectories(target);
            else {
                Files.createDirectories(target.getParent());
                Files.copy(p, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        List<Path> reversed = new ArrayList<>(all);
        java.util.Collections.reverse(reversed);
        for (Path p : reversed) Files.deleteIfExists(p);
        return copy;
    }

    private static boolean available(String binary) {
        return binary.startsWith("/") ? Files.isExecutable(Path.of(binary)) : which(binary) != null;
    }

    private static String which(String binary) {
        if (binary.startsWith("/")) return binary;
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path p = Path.of(dir).resolve(binary);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    private record Ran(int exit, String out) {}

    private static Ran exec(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Ran(-2, out);
        }
        return new Ran(p.exitValue(), out);
    }
}
