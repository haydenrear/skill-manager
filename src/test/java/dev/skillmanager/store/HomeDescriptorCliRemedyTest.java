package dev.skillmanager.store;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.launch.RunningCli;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Issue #161 and DEF-002 — <b>a remedy is an instruction, and these ones used
 * to edit a different home than the one you were in.</b>
 *
 * <p>Three properties, each asserted rather than described:
 *
 * <ol>
 *   <li><b>The stated precedence is the real precedence.</b>
 *       {@link HomeDescriptor#locateCli} is walked step by step with every
 *       candidate present and then removed one at a time, and the javadoc's own
 *       {@code <ol>} is parsed out of the source file and checked against the
 *       order the walk observed. The step this replaced — "the running
 *       process's own command, when it really is a skill-manager launcher" —
 *       could not fire in ANY shipped launcher, because every distribution
 *       {@code exec}s a JVM and the process basename is {@code java} or
 *       {@code jbang}; that is asserted too, against the predicate the old step
 *       used, so the reason it was replaced stays measured rather than
 *       remembered.</li>
 *   <li><b>A remedy about home X names X.</b> DEF-002's measured shape: a sync
 *       run against the PROJECT home printed a remedy naming the ROOT home's
 *       pinned entrypoint, and following it verbatim edits the root home. A
 *       home's {@code bin/cli/skill-manager} exports its own location as
 *       {@code SKILL_MANAGER_HOME} and lets that win, so a FOREIGN one cannot
 *       be redirected by any prefix — it is refused as a candidate, at every
 *       step.</li>
 *   <li><b>A PATH-resolved remedy says so.</b> #142 made remedies absolute,
 *       which made the PATH case READ authoritative while leaving it exactly as
 *       wrong. The caveat is what distinguishes them.</li>
 * </ol>
 *
 * <p>Every candidate here is a real executable file in a temp directory and
 * every lookup is injected, because {@code System.getenv} cannot be set from
 * inside a JVM — which is precisely how a resolution step nobody could drive
 * stayed dead across three releases with a javadoc describing it.
 */
public final class HomeDescriptorCliRemedyTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomeDescriptorCliRemedyTest");

        // ------------------------------------------------------- precedence

        suite.test("locateCli walks all four steps, in the order it documents", () -> {
            Fixture f = Fixture.create("cli-precedence-");

            HomeDescriptor.ResolvedCli all = f.locate(f.pin, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.PINNED_ENV, all.source(),
                    "step 1: an explicit SKILL_MANAGER_CLI wins over everything");
            assertEquals(f.pin, all.path(), "and it is the pinned path that is returned");

            HomeDescriptor.ResolvedCli noPin = f.locate(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT, noPin.source(),
                    "step 2: the home's own bin/cli entrypoint, before anything global");
            assertEquals(f.ownEntrypoint, noPin.path(), "and it is this home's own file");

            Files.delete(f.ownEntrypoint);
            HomeDescriptor.ResolvedCli noOwn = f.locate(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.RUNNING_BUILD, noOwn.source(),
                    "step 3: the build that is running right now");
            assertEquals(f.running, noOwn.path(), "and it is the running launcher");

            HomeDescriptor.ResolvedCli noRunning = f.locate(null, null, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.PATH_FALLBACK, noRunning.source(),
                    "step 4: a raw PATH walk, and it is reported AS a fallback");
            assertEquals(f.onPath, noRunning.path(), "and it is what PATH found");

            HomeDescriptor.ResolvedCli nothing = f.locate(null, null, null);
            assertEquals(HomeDescriptor.CliSource.UNRESOLVED, nothing.source(),
                    "nothing resolves rather than something being invented");
            assertTrue(nothing.path() == null, "and the path is null, not a plausible guess");
        });

        suite.test("the javadoc's stated precedence is the precedence the walk observed", () -> {
            List<String> steps = documentedPrecedence();
            assertEquals(4, steps.size(),
                    "locateCli's javadoc states exactly four steps: " + steps);
            assertContains(steps.get(0), "SKILL_MANAGER_CLI", "step 1 is the explicit pin");
            assertContains(steps.get(1), "bin/cli/skill-manager",
                    "step 2 is the home's own entrypoint");
            assertContains(steps.get(2), "RunningCli",
                    "step 3 is the running build, located by RunningCli");
            assertContains(steps.get(3), "PATH", "step 4 is the PATH fallback");
            // The step the javadoc used to claim second. It is not merely
            // absent from the list: the code no longer consults ProcessHandle
            // at all, which is what makes the list true rather than tidy.
            String source = Files.readString(homeDescriptorSource());
            int cliSection = source.indexOf(
                    "// ------------------------------------------------------- cli discovery");
            assertTrue(cliSection > 0, "the cli-discovery section is findable");
            String code = stripComments(source.substring(cliSection));
            assertContains(source.substring(cliSection), "ProcessHandle",
                    "the javadoc still explains WHY the step was replaced");
            assertFalse(code.contains("ProcessHandle"),
                    "but no code consults it: the dead step's implementation is gone, "
                            + "not just its documentation");
        });

        suite.test("the step that was replaced could not fire in any shipped launcher", () -> {
            // The old step 2 was `ProcessHandle.current().info().command()`
            // filtered on the basename `skill-manager`. Every distribution of
            // this CLI execs a JVM, so that command is java or jbang. Asserted
            // through RunningCli, which applies the identical basename filter
            // to the identical input.
            Path tmp = Files.createTempDirectory("cli-dead-step-");
            for (String launcher : List.of("java", "jbang")) {
                Path fake = executable(tmp.resolve(launcher));
                Exception refused = null;
                try {
                    RunningCli.locate(key -> null, fake.toString(), null);
                } catch (RunningCli.UnknownLocationException e) {
                    refused = e;
                }
                assertTrue(refused != null,
                        "a process named " + launcher + " is not a skill-manager launcher");
                assertContains(refused.getMessage(), "not named skill-manager",
                        "and the reason is the basename filter the dead step used");
            }
            // The replacement IS live for exactly that process, because a
            // shipped launcher exports SKILL_MANAGER_INSTALL_DIR before it
            // execs the JVM.
            Path install = Files.createDirectories(tmp.resolve("install"));
            Path launcher = executable(install.resolve("skill-manager"));
            Path java = executable(tmp.resolve("java2"));
            assertEquals(launcher,
                    RunningCli.locate(key -> RunningCli.INSTALL_DIR.equals(key)
                            ? install.toString() : null, java.toString(), null),
                    "the same JVM process resolves to its launcher through INSTALL_DIR");
        });

        // -------------------------------------------- a remedy names its home

        suite.test("a remedy about home X names X, whichever step resolved the CLI", () -> {
            Fixture f = Fixture.create("cli-names-home-");
            record Case(String label, Path pin, Path running, Path pathDir) {}
            List<Case> cases = List.of(
                    new Case("pinned", f.pin, f.running, f.pathDir),
                    new Case("own entrypoint", null, f.running, f.pathDir),
                    new Case("running build", null, f.running, f.pathDir),
                    new Case("path fallback", null, null, f.pathDir),
                    new Case("unresolved", null, null, null));
            for (Case c : cases) {
                if ("running build".equals(c.label())) Files.deleteIfExists(f.ownEntrypoint);
                HomeDescriptor.CliSpelling spelling = f.spell(c.pin(), c.running(), c.pathDir());
                assertContains(spelling.command(), f.home.toString(),
                        "the " + c.label() + " remedy names the home it is about");
            }
        });

        suite.test("DEF-002: another home's entrypoint is never the remedy", () -> {
            Fixture f = Fixture.create("cli-foreign-");
            // The measured shape: the ROOT home's bin/cli on the operator's
            // PATH, while the command runs against the PROJECT home.
            Path foreign = Fixture.home(Files.createTempDirectory("cli-foreign-root-"));
            Path foreignEntrypoint = executable(foreign.resolve("bin/cli/skill-manager"));
            Files.delete(f.ownEntrypoint);

            assertTrue(HomeDescriptor.isForeignHomeEntrypoint(f.home, foreignEntrypoint),
                    "the root home's entrypoint is foreign to the project home");
            assertFalse(HomeDescriptor.isForeignHomeEntrypoint(foreign, foreignEntrypoint),
                    "and it is NOT foreign to the home it belongs to");

            HomeDescriptor.ResolvedCli viaPath =
                    f.locate(null, null, foreignEntrypoint.getParent());
            assertEquals(HomeDescriptor.CliSource.UNRESOLVED, viaPath.source(),
                    "a foreign entrypoint on PATH is skipped rather than returned");

            HomeDescriptor.ResolvedCli viaPin = f.locate(foreignEntrypoint, null, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.PATH_FALLBACK, viaPin.source(),
                    "and it is skipped as an explicit pin too — no prefix can redirect it");

            // Its own home still gets it: the rule is about WHOSE home, not
            // about the shape of the path.
            HomeDescriptor.CliSpelling ownSide = HomeDescriptor.cliSpelling(
                    foreign, key -> null, () -> null);
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT, ownSide.source(),
                    "the foreign home resolves its own entrypoint normally");
            assertEquals(foreignEntrypoint.toString(), ownSide.command(),
                    "and needs no binding prefix, because that file binds itself");
        });

        suite.test("a home's own entrypoint binds itself; every other spelling is bound", () -> {
            Fixture f = Fixture.create("cli-binding-");

            HomeDescriptor.CliSpelling own = f.spell(null, f.running, f.pathDir);
            assertEquals(HomeDescriptor.CliSource.HOME_ENTRYPOINT, own.source(), "own entrypoint");
            assertEquals(f.ownEntrypoint.toString(), own.command(),
                    "no prefix: bin/cli/skill-manager exports its own location as the home");

            Files.delete(f.ownEntrypoint);
            HomeDescriptor.CliSpelling running = f.spell(null, f.running, f.pathDir);
            assertContains(running.command(), "SKILL_MANAGER_HOME=" + f.home,
                    "a build that is not this home's own carries the home explicitly");
            assertTrue(running.command().startsWith("/"),
                    "and the remedy still begins with an absolute path a shell can run: "
                            + running.command());
        });

        // ------------------------------------------- the fallback admits it

        suite.test("a PATH-resolved remedy says so; a verified one has nothing to admit", () -> {
            Fixture f = Fixture.create("cli-caveat-");

            assertTrue(f.spell(f.pin, f.running, f.pathDir).caveat() == null,
                    "an explicit pin has nothing to admit");
            assertTrue(f.spell(null, f.running, f.pathDir).caveat() == null,
                    "the home's own entrypoint has nothing to admit");
            Files.delete(f.ownEntrypoint);
            assertTrue(f.spell(null, f.running, f.pathDir).caveat() == null,
                    "the running build has nothing to admit");

            HomeDescriptor.CliSpelling fallback = f.spell(null, null, f.pathDir);
            assertFalse(fallback.verified(), "a PATH walk is not a verified answer");
            String caveat = fallback.caveat();
            assertTrue(caveat != null, "and it must say so");
            assertContains(caveat, "PATH", "the caveat names where the build came from");
            assertContains(caveat, f.onPath.toString(), "and which build it is");
            assertContains(caveat, "home shims", "and how to stop guessing");

            HomeDescriptor.CliSpelling none = f.spell(null, null, null);
            assertContains(none.command(), "skill-manager", "the bare token remains the floor");
            assertContains(none.caveat(), "no skill-manager could be located",
                    "and an unresolved remedy admits that too");
        });

        return suite.runAll();
    }

    // ----------------------------------------------------------------- setup

    /**
     * One project home with every candidate CLI present as a real executable,
     * plus injectable {@code SKILL_MANAGER_CLI} / {@code PATH} / running-build
     * lookups.
     */
    private static final class Fixture {
        final Path home;
        final Path ownEntrypoint;
        final Path pin;
        final Path running;
        final Path pathDir;
        final Path onPath;

        private Fixture(Path home, Path ownEntrypoint, Path pin, Path running,
                        Path pathDir, Path onPath) {
            this.home = home;
            this.ownEntrypoint = ownEntrypoint;
            this.pin = pin;
            this.running = running;
            this.pathDir = pathDir;
            this.onPath = onPath;
        }

        static Fixture create(String prefix) throws Exception {
            Path tmp = Files.createTempDirectory(prefix);
            Path home = home(tmp.resolve("project/.skill-manager"));
            Path pathDir = Files.createDirectories(tmp.resolve("usr-local-bin"));
            return new Fixture(
                    home,
                    executable(home.resolve("bin/cli/skill-manager")),
                    executable(tmp.resolve("pinned/skill-manager")),
                    executable(tmp.resolve("running/skill-manager")),
                    pathDir,
                    executable(pathDir.resolve("skill-manager")));
        }

        /** A directory that {@code LaunchEnv.looksLikeStoreRoot} recognises. */
        static Path home(Path root) throws Exception {
            Files.createDirectories(root.resolve("installed"));
            Files.createDirectories(root.resolve("skills"));
            return root;
        }

        HomeDescriptor.ResolvedCli locate(Path pinned, Path runningBuild, Path path) {
            return HomeDescriptor.locateCli(home, env(pinned, path), supplier(runningBuild));
        }

        HomeDescriptor.CliSpelling spell(Path pinned, Path runningBuild, Path path) {
            return HomeDescriptor.cliSpelling(home, env(pinned, path), supplier(runningBuild));
        }

        private static Function<String, String> env(Path pinned, Path path) {
            Map<String, String> values = new LinkedHashMap<>();
            if (pinned != null) values.put("SKILL_MANAGER_CLI", pinned.toString());
            if (path != null) values.put("PATH", path.toString());
            return values::get;
        }

        private static Supplier<Path> supplier(Path runningBuild) {
            return () -> runningBuild;
        }
    }

    private static Path executable(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\nexit 0\n");
        Fs.makeExecutable(file);
        return file.toAbsolutePath().normalize();
    }

    // ---------------------------------------------- the javadoc as an oracle

    /**
     * The {@code <li>} bodies of the {@code <ol>} in {@code locateCli}'s
     * javadoc, in order, with markup and whitespace flattened.
     *
     * <p>Read from the source file on purpose. The whole defect this test
     * covers is a javadoc that stated a precedence the code did not have, and
     * an oracle that paraphrased the javadoc into the test would have agreed
     * with the wrong one.
     */
    private static List<String> documentedPrecedence() throws Exception {
        String source = Files.readString(homeDescriptorSource());
        int at = source.indexOf("public static ResolvedCli locateCli(Path storeRoot)");
        if (at < 0) throw new AssertionError("locateCli(Path) not found in HomeDescriptor");
        String javadoc = source.substring(0, at);
        int ol = javadoc.lastIndexOf("<ol>");
        int close = javadoc.indexOf("</ol>", ol);
        if (ol < 0 || close < 0) throw new AssertionError("locateCli's javadoc has no <ol>");
        String list = javadoc.substring(ol, close);
        List<String> steps = new ArrayList<>();
        Matcher m = Pattern.compile("<li>(.*?)</li>", Pattern.DOTALL).matcher(list);
        while (m.find()) {
            steps.add(m.group(1)
                    .replaceAll("(?m)^\\s*\\*\\s?", " ")
                    .replaceAll("\\s+", " ")
                    .strip());
        }
        return steps;
    }

    /**
     * {@code java} with every block and line comment blanked out.
     *
     * <p>Needed because this file's javadoc deliberately NAMES the dead step's
     * implementation in order to explain why it went. Scanning the raw text
     * would find the explanation and call it the defect — an oracle that fires
     * on its own documentation.
     */
    private static String stripComments(String java) {
        StringBuilder out = new StringBuilder(java.length());
        int i = 0;
        while (i < java.length()) {
            if (java.startsWith("/*", i)) {
                int end = java.indexOf("*/", i + 2);
                i = end < 0 ? java.length() : end + 2;
            } else if (java.startsWith("//", i)) {
                int end = java.indexOf('\n', i);
                i = end < 0 ? java.length() : end;
            } else {
                out.append(java.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static Path homeDescriptorSource() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/java/dev/skillmanager/store/HomeDescriptor.java");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        // Loud rather than vacuous: a scan that reads nothing proves nothing.
        throw new AssertionError("cannot find HomeDescriptor.java from " + dir
                + " — run the suite from the repository root");
    }
}
