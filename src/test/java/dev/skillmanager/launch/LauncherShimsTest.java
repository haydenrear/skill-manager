package dev.skillmanager.launch;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.agent.AgentHomes;
import dev.skillmanager.commands.ExecCommand;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.policy.FrozenHomeException;
import dev.skillmanager.policy.HomePolicy;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.HomeDescriptor;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * Launcher shims and {@code skill-manager exec} — the surface that makes a
 * per-home launch the default rather than something a human has to remember.
 *
 * <p>The happy path (env gets exported) is the least interesting thing here.
 * Two failures are:
 *
 * <ol>
 *   <li><b>PATH, not env, is how a CLI dependency is found.</b> A
 *       {@code skill-script:} shim is a shell script with the home's absolute
 *       path in its body, so no variable redirects it. These tests run two real
 *       homes with the same tool name in each and assert which one executes —
 *       the only way to prove precedence rather than assert on a string.</li>
 *   <li><b>An unredirected {@code CLAUDE_CONFIG_DIR} must stop the launch.</b>
 *       Asserted by the absence of a sentinel the child would have written, not
 *       by an exit code — a gate that refuses after spawning has already lost.</li>
 * </ol>
 */
public final class LauncherShimsTest {

    /**
     * Appended to the inherited PATH the tests hand {@code exec}. The fixture
     * scripts start {@code #!/usr/bin/env bash}, and {@code env} looks
     * {@code bash} up on the <em>child's</em> PATH — which, correctly, does not
     * inherit the system directories unless they were there to begin with.
     */
    private static final String SYSTEM_BINS = File.pathSeparator + "/usr/bin"
            + File.pathSeparator + "/bin";

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("LauncherShimsTest");

        // ------------------------------------------------------- PATH shape

        suite.test("the active home's bin comes first on the launch PATH", () -> {
            Home home = Home.create("launch-prefix-");
            LaunchEnv launch = LaunchEnv.of(home.store, null, "/usr/bin:/bin", false);

            List<Path> entries = launch.pathEntries();
            assertEquals(home.store.cliBinDir().toAbsolutePath().normalize(), entries.get(0),
                    "bin/cli leads — that is where skill-script shims live");
            assertEquals(home.store.mcpBinDir().toAbsolutePath().normalize(), entries.get(1),
                    "bin/mcp next");
            assertEquals(LaunchEnv.launcherDir(home.store), entries.get(2), "bin/launch next");
            assertTrue(entries.containsAll(List.of(Path.of("/usr/bin"), Path.of("/bin"))),
                    "the inherited PATH is kept behind the home's own");
        });

        suite.test("another home's bin directories are dropped from the launch PATH", () -> {
            Home global = Home.create("launch-foreign-global-");
            Home project = Home.create("launch-foreign-project-");
            String inherited = global.store.cliBinDir() + File.pathSeparator
                    + global.store.mcpBinDir() + File.pathSeparator + "/usr/bin";

            LaunchEnv launch = LaunchEnv.of(project.store, null, inherited, false);

            assertFalse(launch.pathEntries().contains(
                            global.store.cliBinDir().toAbsolutePath().normalize()),
                    "the global home's bin/cli is not merely demoted, it is gone");
            assertFalse(launch.pathEntries().contains(
                            global.store.mcpBinDir().toAbsolutePath().normalize()),
                    "same for bin/mcp");
            assertTrue(launch.pathEntries().contains(Path.of("/usr/bin")),
                    "unrelated PATH entries survive");
        });

        suite.test("a directory that is not a home's bin is left on PATH", () -> {
            Home home = Home.create("launch-notahome-");
            Path ordinary = Files.createTempDirectory("launch-ordinary-").resolve("bin/cli");
            Fs.ensureDir(ordinary);

            LaunchEnv launch = LaunchEnv.of(home.store, null, ordinary.toString(), false);

            assertTrue(launch.pathEntries().contains(ordinary.toAbsolutePath().normalize()),
                    "bin/cli under a directory that is not a store is not a foreign home");
        });

        // --------------------------------------------- precedence, for real

        suite.test("exec runs the active home's skill-script shim, not the global home's", () -> {
            // The observed failure: `tla-spec-dev` on PATH resolved to
            // ~/.skill-manager/bin/cli/tla-spec-dev, whose body names the global
            // home's script by absolute path, and it wrote into that home while
            // the caller believed it was isolated.
            Home global = Home.create("exec-prec-global-");
            Home project = Home.create("exec-prec-project-");
            Path ran = project.root.resolve("which-ran");
            writeShim(global.store.cliBinDir().resolve("tla-spec-dev"), "global-home", ran);
            writeShim(project.store.cliBinDir().resolve("tla-spec-dev"), "project-home", ran);
            project.writeDescriptor();

            int rc = new CommandLine(
                    new ExecCommand(project.store, global.store.cliBinDir() + SYSTEM_BINS))
                    .execute("--no-reconcile", "tla-spec-dev");

            assertEquals(0, rc, "exec rc");
            String executed = Files.readString(ran);
            assertContains(executed, "project-home", "the project home's shim ran");
            assertFalse(executed.contains("global-home"),
                    "the global home's shim did NOT run");
        });

        suite.test("a tool only the other home has is unreachable, not silently borrowed", () -> {
            Home global = Home.create("exec-only-global-");
            Home project = Home.create("exec-only-project-");
            Path ran = project.root.resolve("which-ran");
            writeShim(global.store.cliBinDir().resolve("only-in-global"), "global-home", ran);
            project.writeDescriptor();

            Result result = captureBoth(() -> new CommandLine(
                    new ExecCommand(project.store, global.store.cliBinDir() + SYSTEM_BINS))
                    .execute("--no-reconcile", "only-in-global"));

            assertEquals(127, result.rc, "the launch fails rather than running the other home's copy");
            assertFalse(Files.exists(ran), "nothing from the other home ran");
            assertContains(result.err, "not on the launch PATH", "and the refusal says why");
        });

        // ----------------------------------------------------- env exported

        suite.test("exec exports the descriptor env block to the child process", () -> {
            Home home = Home.create("exec-env-");
            home.writeDescriptor();
            Path seen = home.root.resolve("child-env");
            Path dump = writeEnvDump(home.root.resolve("dump-env"), seen);

            int rc = new CommandLine(new ExecCommand(home.store, "/usr/bin:/bin"))
                    .execute("--no-reconcile", dump.toString());

            assertEquals(0, rc, "exec rc");
            String env = Files.readString(seen);
            assertContains(env, "SKILL_MANAGER_HOME=" + home.store.root(), "the store is exported");
            assertContains(env, "CLAUDE_CONFIG_DIR=" + home.root.resolve(".claude"),
                    "and Claude is redirected into the home");
            assertContains(env, "CLAUDE_HOME=" + home.root.resolve(".claude"),
                    "both spellings carry the one value");
            assertContains(env, "CODEX_HOME=" + home.root.resolve(".codex"), "codex too");
            assertContains(env, "GEMINI_HOME=" + home.root.resolve(".gemini"), "gemini too");
            assertContains(env, "PATH=" + home.store.cliBinDir(),
                    "and the child's PATH starts at this home");
        });

        suite.test("declared env contributions reach the child", () -> {
            Home home = Home.create("exec-contrib-");
            home.writeDescriptor(Map.of("EPIC_TICKET", "T3"));
            Path seen = home.root.resolve("child-env");
            Path dump = writeEnvDump(home.root.resolve("dump-env"), seen);

            new CommandLine(new ExecCommand(home.store, "/usr/bin:/bin"))
                    .execute("--no-reconcile", dump.toString());

            assertContains(Files.readString(seen), "EPIC_TICKET=T3", "contribution exported");
        });

        // ------------------------------------------------------------- gate

        suite.test("a launch that would still read the real ~/.claude is refused, and nothing runs", () -> {
            Home home = Home.create("exec-gate-");
            // A descriptor whose env block never got the Claude variables — the
            // shape a hand-edited or older descriptor has. AgentHomes.claude()
            // then falls through to user.home, which is the operator's real
            // config directory and where every skill would be loaded from.
            new HomeDescriptor(
                    home.root, HomePolicy.LIVE.wire(),
                    new HomeDescriptor.Env(home.store.root(), null, null, null, null),
                    null, new HomeDescriptor.Gateway("http://127.0.0.1:51717", true),
                    List.of(), Map.of())
                    .write(home.store.root());
            Path sentinel = home.root.resolve("child-ran");
            Path touch = writeTouch(home.root.resolve("touch"), sentinel);

            // The system bins are on the inherited PATH on purpose: the sentinel
            // assertion below is only load-bearing if the child *could* have run.
            Result result = captureBoth(() -> new CommandLine(
                    new ExecCommand(home.store, "/usr/bin" + SYSTEM_BINS))
                    .execute("--no-reconcile", touch.toString()));

            // Absence of the sentinel first: it is the assertion that a
            // refusal-after-spawn would fail. An exit code alone would be
            // satisfied by a gate that reports after doing the damage.
            assertFalse(Files.exists(sentinel),
                    "the child process was never started — the file it would create is absent");
            assertEquals(UnredirectedLaunchException.EXIT_CODE, result.rc,
                    "refused with the unredirected-launch code");
            assertContains(result.err, AgentHomes.CLAUDE_CONFIG_DIR, "the refusal names the variable");
            assertContains(result.err, "Skills load from that directory",
                    "and says why it is fatal rather than a warning");
        });

        suite.test("an env contribution pointing Claude back at the real home is refused too", () -> {
            Home home = Home.create("exec-gate-contrib-");
            Path realClaude = Path.of(System.getProperty("user.home"), ".claude");
            home.writeDescriptor(Map.of(AgentHomes.CLAUDE_CONFIG_DIR, realClaude.toString()));
            Path sentinel = home.root.resolve("child-ran");
            Path touch = writeTouch(home.root.resolve("touch"), sentinel);

            // The system bins are on the inherited PATH on purpose: the sentinel
            // assertion below is only load-bearing if the child *could* have run.
            Result result = captureBoth(() -> new CommandLine(
                    new ExecCommand(home.store, "/usr/bin" + SYSTEM_BINS))
                    .execute("--no-reconcile", touch.toString()));

            assertFalse(Files.exists(sentinel), "nothing ran");
            assertEquals(UnredirectedLaunchException.EXIT_CODE, result.rc, "refused");
            assertContains(result.err, realClaude.toString(), "the offending value is named");
        });

        suite.test("the global home launches against ~/.claude without being refused", () -> {
            // The gate is "inside this home", not "never ~/.claude". For a home
            // at <root>/.skill-manager the correct Claude directory is
            // <root>/.claude, and for the global home that is the real one.
            Path fakeUserHome = Files.createTempDirectory("exec-gate-global-");
            SkillStore store = new SkillStore(fakeUserHome.resolve(".skill-manager"));
            store.init();
            HomeDescriptor.envFor(fakeUserHome, store.root());
            new HomeDescriptor(
                    fakeUserHome, HomePolicy.LIVE.wire(),
                    HomeDescriptor.envFor(fakeUserHome, store.root()), null,
                    new HomeDescriptor.Gateway("http://127.0.0.1:51717", true), List.of(), Map.of())
                    .write(store.root());

            LaunchEnv launch = LaunchEnv.of(store, null, "/usr/bin", false);
            launch.requireClaudeRedirected();
            assertEquals(fakeUserHome.resolve(".claude"), launch.claudeHome().configDir(),
                    "the home root's own .claude is accepted");
        });

        // ----------------------------------------------------------- policy

        suite.test("exec bootstraps a missing descriptor on a live home", () -> {
            Home home = Home.create("exec-bootstrap-live-");
            assertFalse(Files.exists(HomeDescriptor.file(home.store.root())),
                    "no descriptor to begin with");

            int rc = new CommandLine(new ExecCommand(home.store, "/usr/bin"))
                    .execute("--no-reconcile", "--print-env");

            assertEquals(0, rc, "print-env rc");
            assertTrue(Files.exists(HomeDescriptor.file(home.store.root())),
                    "the live home got a descriptor written for it");
        });

        suite.test("exec writes nothing into a frozen home", () -> {
            Home home = Home.create("exec-bootstrap-frozen-");
            HomePolicy.write(home.store, HomePolicy.FROZEN);

            Result result = captureOut(() -> new CommandLine(
                    new ExecCommand(home.store, "/usr/bin")).execute("--no-reconcile", "--print-env"));

            assertEquals(0, result.rc, "a frozen home may still be launched — reading is fine");
            assertFalse(Files.exists(HomeDescriptor.file(home.store.root())),
                    "but no descriptor was bootstrapped into it");
            assertContains(result.out, "CLAUDE_CONFIG_DIR=" + home.root.resolve(".claude"),
                    "and the env was still derived correctly in memory");
        });

        suite.test("`home shims` refuses on a frozen home and writes no launcher", () -> {
            Home home = Home.create("shims-frozen-");
            HomePolicy.write(home.store, HomePolicy.FROZEN);
            try {
                LauncherShims.write(home.store);
                throw new AssertionError("expected a frozen home to refuse shim generation");
            } catch (FrozenHomeException expected) {
                assertEquals("home shims", expected.operation(), "operation named");
            }
            assertFalse(Files.exists(LauncherShims.dir(home.store).resolve("claude")),
                    "no launcher was written");
        });

        // ------------------------------------------------------------ shims

        suite.test("generated shims are self-locating and name no absolute home", () -> {
            Home home = Home.create("shims-relocatable-");
            int rc = new CommandLine(new HomeCommand.ShimsCmd(home.store)).execute();
            assertEquals(0, rc, "home shims rc");

            for (String agent : LauncherShims.AGENTS) {
                Path shim = LauncherShims.dir(home.store).resolve(agent);
                assertTrue(Files.isExecutable(shim), agent + " shim is executable");
                String body = Files.readString(shim);
                assertFalse(body.contains(home.store.root().toString()),
                        agent + " shim hardcodes no path into the home it was written for");
                assertContains(body, "exec \"$cli\" exec --home \"$home\" -- " + agent,
                        agent + " shim delegates to skill-manager exec");
            }
        });

        suite.test("running a generated shim binds the home it lives in", () -> {
            // Runs the real bash, with a stub CLI in place of skill-manager, so
            // the assertion is about what the shim actually does rather than
            // about the string it contains.
            Home home = Home.create("shims-run-");
            LauncherShims.write(home.store);
            Path stub = writeArgvDump(home.root.resolve("stub-cli"));

            Result result = runProcess(
                    List.of(LauncherShims.dir(home.store).resolve("claude").toString(),
                            "--model", "opus"),
                    Map.of("SKILL_MANAGER_CLI", stub.toString()));

            assertEquals(0, result.rc, "shim rc");
            // The shim uses `pwd -P`, so it reports the real path — on macOS
            // /var is a symlink to /private/var and a temp dir spells it both ways.
            assertContains(result.out,
                    "exec --home " + home.store.root().toRealPath() + " -- claude --model opus",
                    "the shim resolved its own home and forwarded the arguments");
        });

        suite.test("a shim in a copied home binds the copy, not the original", () -> {
            Home original = Home.create("shims-copy-src-");
            LauncherShims.write(original.store);
            Path copyRoot = Files.createTempDirectory("shims-copy-dst-");
            Fs.copyRecursive(original.root, copyRoot.resolve("home"));
            Path copiedShim = copyRoot.resolve("home/.skill-manager/bin/launch/claude");
            copiedShim.toFile().setExecutable(true);
            Path stub = writeArgvDump(copyRoot.resolve("stub-cli"));

            Result result = runProcess(List.of(copiedShim.toString()),
                    Map.of("SKILL_MANAGER_CLI", stub.toString()));

            assertEquals(0, result.rc, "copied shim rc");
            assertContains(result.out,
                    "--home " + copyRoot.toRealPath().resolve("home/.skill-manager"),
                    "the copy launches against itself");
            assertFalse(result.out.contains(original.store.root().toRealPath().toString()),
                    "and never mentions the home it was copied from");
        });

        // ------------------------------------------------- the CLI entrypoint
        //
        // Two-sided on purpose. `cliScript()` emitted `printf '%%s'` — correct
        // in script(String), which ends in .formatted(agent), and wrong here,
        // where nothing unescapes it: bash printed the two characters %s, the
        // filtered PATH became the single entry `%s`, `command -v` found
        // nothing, and the shim took the "no CLI provisioned" branch on EVERY
        // home including ones with a working CLI on PATH. The only assertion
        // covering it checked that a shim with nothing reachable exits
        // non-zero, which a permanently dead shim satisfies perfectly.

        suite.test("the cli entrypoint's body carries no unformatted percent", () -> {
            // The cause, named. cliScript() has no .formatted() call, so a
            // doubled percent is never unescaped and reaches bash literally.
            assertFalse(LauncherShims.cliScript().contains("%%"),
                    "a doubled percent in a text block with no .formatted() reaches bash as `%%`");
            assertContains(LauncherShims.cliScript(), "printf '%s' \"${PATH:-}\"",
                    "the PATH split prints its argument rather than the characters %s");
        });

        suite.test("the cli entrypoint execs the skill-manager it finds on PATH", () -> {
            Home home = Home.create("cli-entrypoint-finds-");
            LauncherShims.write(home.store);
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            Path stub = stubCliDir(home.root.resolve("stub-bin"));

            // Output, not exit code: a shim that exits 0 without launching is
            // the exact defect this whole surface exists to prevent.
            Result result = runShim(shim, stub + File.pathSeparator + hermeticTools(home.root));
            assertContains(result.out, STUB_SENTINEL,
                    "the shim exec'd the CLI it found rather than refusing");
            assertContains(result.out, "args=--version",
                    "and handed the arguments over unchanged");
            assertEquals(0, result.rc, "so it exits with the exec'd CLI's status");
        });

        suite.test("the cli entrypoint never resolves to itself", () -> {
            // LaunchEnv puts <home>/bin/cli FIRST on the launch PATH, so this
            // is the real arrangement, not a contrived one. Excluding its own
            // directory is the only way the shim reaches the stub behind it;
            // failing to, it exec's itself forever. Bounded, so the failure is
            // a named assertion rather than a hung suite.
            Home home = Home.create("cli-entrypoint-self-");
            LauncherShims.write(home.store);
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            Path stub = stubCliDir(home.root.resolve("stub-bin"));

            Result result = runShim(shim, home.store.cliBinDir() + File.pathSeparator
                    + stub + File.pathSeparator + hermeticTools(home.root));

            assertFalse(result.out.contains(SHIM_TIMED_OUT),
                    "the shim terminated: it filtered its own directory out of PATH");
            assertContains(result.out, STUB_SENTINEL,
                    "and reached the stub that was behind it");
        });

        suite.test("the cli entrypoint refuses when no skill-manager is reachable", () -> {
            // The half that was already covered, kept and tightened: asserted
            // on the diagnostic rather than on a non-zero exit, because `set -e`
            // tripping over a missing `dirname` is also a non-zero exit and is
            // a different thing entirely.
            Home home = Home.create("cli-entrypoint-refuses-");
            LauncherShims.write(home.store);
            Path shim = home.store.cliBinDir().resolve("skill-manager");

            Result result = runShim(shim,
                    home.store.cliBinDir() + File.pathSeparator + hermeticTools(home.root));

            assertContains(result.out, "no CLI is provisioned",
                    "it refuses with the diagnostic, not with an incidental failure");
            assertEquals(127, result.rc, "and with the not-found status");
        });

        return suite.runAll();
    }

    // ------------------------------------------------- cli entrypoint fixtures

    /** Printed by the stub CLI; finding it proves the shim really exec'd. */
    private static final String STUB_SENTINEL = "STUB-CLI-WAS-EXECED";

    /** Injected into the captured output when a shim had to be killed. */
    private static final String SHIM_TIMED_OUT = "SHIM-DID-NOT-TERMINATE";

    /**
     * A PATH directory holding exactly the two external programs the shim needs
     * ({@code dirname} and {@code tr}) and nothing else.
     *
     * <p>Emptying PATH instead would make the shim die on a missing
     * {@code dirname} — a non-zero exit that looks like a refusal and is not
     * one — and leaving {@code /usr/bin} on it would let the refusal case find
     * a real skill-manager on some machines.
     */
    private static Path hermeticTools(Path sandbox) throws Exception {
        Path tools = sandbox.resolve("hermetic-tools");
        Fs.ensureDir(tools);
        for (String tool : List.of("bash", "dirname", "tr")) {
            for (String dir : List.of("/usr/bin", "/bin", "/usr/local/bin")) {
                Path candidate = Path.of(dir, tool);
                if (!Files.isExecutable(candidate)) continue;
                Path link = tools.resolve(tool);
                if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(link, candidate);
                }
                break;
            }
        }
        return tools;
    }

    /** A directory holding a working {@code skill-manager} that is not the shim. */
    private static Path stubCliDir(Path dir) throws Exception {
        Fs.ensureDir(dir);
        Path stub = dir.resolve("skill-manager");
        Files.writeString(stub, """
                #!/bin/sh
                echo "%s"
                echo "args=$*"
                exit 0
                """.formatted(STUB_SENTINEL));
        stub.toFile().setExecutable(true);
        return dir;
    }

    /**
     * Run a shim with {@code path} as its whole PATH and no
     * {@code SKILL_MANAGER_CLI}, bounded.
     *
     * <p>The bound is the point rather than hygiene: the failure being guarded
     * is an unbounded self-exec, and a suite that hangs reports nothing at all.
     */
    private static Result runShim(Path shim, String path) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(shim.toString(), "--version")
                .redirectErrorStream(true);
        pb.environment().remove("SKILL_MANAGER_CLI");
        pb.environment().put("PATH", path);
        Process p = pb.start();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor();
            return new Result(-1, SHIM_TIMED_OUT, SHIM_TIMED_OUT);
        }
        String out = new String(p.getInputStream().readAllBytes());
        return new Result(p.exitValue(), out, out);
    }

    // ------------------------------------------------------------- fixtures

    private record Home(Path root, SkillStore store) {

        static Home create(String prefix) throws Exception {
            Path root = Files.createTempDirectory(prefix);
            SkillStore store = new SkillStore(root.resolve(".skill-manager"));
            store.init();
            return new Home(root, store);
        }

        void writeDescriptor() throws Exception { writeDescriptor(Map.of()); }

        void writeDescriptor(Map<String, String> contributions) throws Exception {
            new HomeDescriptor(
                    root,
                    HomePolicy.LIVE.wire(),
                    HomeDescriptor.envFor(root, store.root()),
                    null,
                    new HomeDescriptor.Gateway("http://127.0.0.1:51717", true),
                    List.of(),
                    contributions).write(store.root());
        }
    }

    /**
     * A stand-in for a {@code skill-script:} CLI shim: records which home it
     * belongs to.
     *
     * <p>It writes to a file rather than stdout because {@code exec} hands the
     * child the JVM's real stdio (an agent launch must be interactive), so
     * replacing {@code System.out} would not capture it. The file is the only
     * honest way to observe what actually ran.
     */
    private static Path writeShim(Path file, String label, Path record) throws Exception {
        Fs.ensureDir(file.getParent());
        Files.writeString(file,
                "#!/usr/bin/env bash\nprintf '%s\\n' " + label + " >> '" + record + "'\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private static Path writeEnvDump(Path file, Path record) throws Exception {
        Fs.ensureDir(file.getParent());
        Files.writeString(file, """
                #!/usr/bin/env bash
                : > '%s'
                for v in SKILL_MANAGER_HOME CLAUDE_CONFIG_DIR CLAUDE_HOME CODEX_HOME GEMINI_HOME \
                PATH EPIC_TICKET; do
                  printf '%%s=%%s\\n' "$v" "${!v-}" >> '%s'
                done
                """.formatted(record, record));
        file.toFile().setExecutable(true);
        return file;
    }

    private static Path writeArgvDump(Path file) throws Exception {
        Fs.ensureDir(file.getParent());
        Files.writeString(file, "#!/usr/bin/env bash\necho \"$@\"\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private static Path writeTouch(Path file, Path sentinel) throws Exception {
        Fs.ensureDir(file.getParent());
        Files.writeString(file, "#!/usr/bin/env bash\ntouch '" + sentinel + "'\n");
        file.toFile().setExecutable(true);
        return file;
    }

    private static Result runProcess(List<String> argv, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        return new Result(p.waitFor(), out, out);
    }

    // ------------------------------------------------------------- plumbing

    private static Result captureOut(ThrowingInt op) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            int rc = op.run();
            return new Result(rc, out.toString(), "");
        } finally {
            System.setOut(original);
        }
    }

    private static Result captureBoth(ThrowingInt op) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true));
            System.setErr(new PrintStream(err, true));
            int rc = op.run();
            return new Result(rc, out.toString(), err.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @FunctionalInterface
    private interface ThrowingInt { int run() throws Exception; }

    private record Result(int rc, String out, String err) {}
}
