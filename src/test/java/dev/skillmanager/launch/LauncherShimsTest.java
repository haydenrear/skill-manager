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
                LauncherShims.write(home.store, stubCli(home.root.resolve("bin")));
                throw new AssertionError("expected a frozen home to refuse shim generation");
            } catch (FrozenHomeException expected) {
                assertEquals("home shims", expected.operation(), "operation named");
            }
            assertFalse(Files.exists(LauncherShims.dir(home.store).resolve("claude")),
                    "no launcher was written");
        });

        // ------------------------------------------------------------ shims

        suite.test("agent launchers are self-locating and name no absolute home", () -> {
            // The relocatability that survived issue #61, asserted as such: the
            // HOME is still derived from the shim's own location. Only the CLI
            // is pinned, and it is pinned in bin/cli/skill-manager, not here.
            Home home = Home.create("shims-relocatable-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));

            for (String agent : LauncherShims.AGENTS) {
                Path shim = LauncherShims.dir(home.store).resolve(agent);
                assertTrue(Files.isExecutable(shim), agent + " shim is executable");
                String body = Files.readString(shim);
                assertFalse(body.contains(home.store.root().toString()),
                        agent + " shim hardcodes no path into the home it was written for");
                assertContains(body, "exec \"$cli\" exec --home \"$home\" -- " + agent,
                        agent + " shim delegates to skill-manager exec");
                assertFalse(body.contains("command -v"),
                        agent + " shim has no PATH fallback — its last line is `exec ... exec`,"
                                + " and an older skill-manager on PATH has no `exec` subcommand");
            }
        });

        suite.test("running a generated shim binds the home it lives in", () -> {
            // Runs the real bash, with a stub CLI in place of skill-manager, so
            // the assertion is about what the shim actually does rather than
            // about the string it contains.
            Home home = Home.create("shims-run-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
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

        suite.test("an agent launcher whose home has no CLI entrypoint refuses, loudly", () -> {
            // The branch that used to read `command -v skill-manager`. With a
            // WORKING skill-manager first on PATH, so the assertion is that the
            // shim declined to use it rather than that none was there.
            Home home = Home.create("shims-no-entrypoint-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Files.delete(home.store.cliBinDir().resolve("skill-manager"));
            Path decoy = decoyOnPath(home.root.resolve("decoy"));

            Result result = runProcess(
                    List.of(LauncherShims.dir(home.store).resolve("claude").toString()),
                    Map.of("PATH", decoy + File.pathSeparator + "/usr/bin" + File.pathSeparator
                            + "/bin", "SKILL_MANAGER_CLI", ""));

            assertFalse(result.out.contains(DECOY_SENTINEL),
                    "the skill-manager on PATH was NOT used — a silent downgrade is the defect");
            assertContains(result.out, "has no CLI entrypoint", "it says what is missing");
            assertContains(result.out, "home shims", "and names the command that repairs it");
            assertEquals(127, result.rc, "with the not-found status");
        });

        suite.test("a shim in a copied home binds the copy, not the original", () -> {
            Home original = Home.create("shims-copy-src-");
            LauncherShims.write(original.store, stubCli(original.root.resolve("pin")));
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
        // Two-sided on purpose, and now decoy-based rather than absence-based.
        //
        // History, because it is the reason for the shape of every case below.
        // `cliScript()` first emitted `printf '%%s'` — correct in
        // script(String), which ends in .formatted(agent), and wrong there,
        // where nothing unescapes it: bash printed the two characters %s, the
        // filtered PATH became the single entry `%s`, and the shim took its
        // refusal branch on EVERY home including ones with a working CLI on
        // PATH. The only assertion covering it checked that a shim with nothing
        // reachable exits non-zero, which a permanently dead shim satisfies.
        //
        // Then issue #61: the PATH search itself was the defect. It found the
        // globally installed release, which on the reporting machine was a build
        // with no `exec` subcommand, so every launcher died at its last line —
        // and both builds answered `--version` identically, so nothing said
        // which one had run. So "nothing is reachable" is no longer the
        // interesting arrangement. A WORKING decoy first on PATH is: every case
        // below asserts the shim ignored it.

        suite.test("the cli entrypoint's body carries no unformatted percent", () -> {
            // Kept green from the earlier defect. cliScript(Path) interpolates
            // with String.replace rather than .formatted(), so percents in the
            // template stay literal and a doubled one can never be intended.
            String body = LauncherShims.cliScript(Path.of("/opt/build/bin/skill-manager"));
            assertFalse(body.contains("%%"),
                    "a doubled percent in a body with no .formatted() reaches bash as `%%`");
            assertFalse(body.contains(LauncherShims.PIN_PLACEHOLDER),
                    "the pin placeholder was substituted, not shipped");
        });

        suite.test("the cli entrypoint pins an absolute CLI and searches no PATH", () -> {
            Home home = Home.create("cli-entrypoint-pins-");
            Path pin = stubCli(home.root.resolve("pin"));
            LauncherShims.write(home.store, pin);

            String body = Files.readString(home.store.cliBinDir().resolve("skill-manager"));
            assertContains(body, pin.toString(), "the body names the CLI that wrote it");
            assertFalse(body.contains("command -v"),
                    "and does NOT ask PATH — that question answers 'which build is installed"
                            + " globally', not 'which build provisioned this home'");
            assertContains(body, "export SKILL_MANAGER_HOME=\"$home\"",
                    "a command reached through this file is a command about THIS home");
            assertContains(body, LauncherShims.PIN_MARKER,
                    "and carries the stable token another tool can key on — `ensure_cli_pin`"
                            + " grepped for the words `home shims`, which every version of this"
                            + " file contains, and overwrote correct pins on 17 of 25 homes");
        });

        suite.test("the cli entrypoint execs its pin with a working decoy first on PATH", () -> {
            // The reproduction of #61, inverted into an assertion: the decoy is
            // a perfectly good executable named skill-manager, first on PATH,
            // exactly as /opt/homebrew/bin/skill-manager was. Asserted on the
            // exec'd process's OUTPUT — a shim that exits 0 without launching is
            // the defect this whole surface exists to prevent.
            Home home = Home.create("cli-entrypoint-decoy-");
            Path pin = stubCli(home.root.resolve("pin"));
            LauncherShims.write(home.store, pin);
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            Path decoy = decoyOnPath(home.root.resolve("decoy"));

            Result result = runShim(shim, decoy + File.pathSeparator
                    + home.store.cliBinDir() + File.pathSeparator + hermeticTools(home.root));

            assertContains(result.out, STUB_SENTINEL, "the pinned CLI ran");
            assertFalse(result.out.contains(DECOY_SENTINEL),
                    "and the skill-manager first on PATH did not");
            assertContains(result.out, "args=--version", "arguments handed over unchanged");
            assertEquals(0, result.rc, "so it exits with the exec'd CLI's status");
        });

        suite.test("the cli entrypoint exports the home it lives in", () -> {
            // Load-bearing, and measurable: the child is told a DIFFERENT home
            // in its inherited environment, and must still see this one. Unset,
            // SKILL_MANAGER_HOME means the operator's global home.
            Home home = Home.create("cli-entrypoint-exports-");
            Path pin = writeEnvEcho(home.root.resolve("pin-env"), "SKILL_MANAGER_HOME");
            LauncherShims.write(home.store, pin);
            Path shim = home.store.cliBinDir().resolve("skill-manager");

            Result result = runProcess(List.of(shim.toString(), "--version"),
                    Map.of("SKILL_MANAGER_HOME", "/somewhere/else/.skill-manager"));

            assertContains(result.out, "SKILL_MANAGER_HOME=" + home.store.root().toRealPath(),
                    "the shim rebound the home to the one it lives in");
            assertFalse(result.out.contains("/somewhere/else"),
                    "the caller's home did not survive");
        });

        suite.test("the cli entrypoint refuses when its pin is gone, and still ignores PATH", () -> {
            // The refusal half, with a WORKING skill-manager first on PATH. The
            // old version of this case ran with nothing reachable, which is why
            // a permanently dead shim passed it for so long. Asserted on the
            // diagnostic as well as the status, because `set -e` tripping over a
            // missing `dirname` is also a non-zero exit and is a different thing.
            Home home = Home.create("cli-entrypoint-stale-pin-");
            Path pin = stubCli(home.root.resolve("pin"));
            LauncherShims.write(home.store, pin);
            Files.delete(pin);
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            Path decoy = decoyOnPath(home.root.resolve("decoy"));

            Result result = runShim(shim, decoy + File.pathSeparator + hermeticTools(home.root));

            assertFalse(result.out.contains(DECOY_SENTINEL),
                    "a stale pin does NOT silently become 'whatever is installed'");
            assertContains(result.out, "the CLI pinned for the home at",
                    "it refuses with the diagnostic, not with an incidental failure");
            assertContains(result.out, pin.toString(), "and names the path that went missing");
            assertContains(result.out, "home shims", "and the command that repairs it");
            assertEquals(127, result.rc, "with the not-found status");
        });

        suite.test("SKILL_MANAGER_CLI still overrides the pin", () -> {
            Home home = Home.create("cli-entrypoint-override-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            Path override = writeArgvDump(home.root.resolve("override-cli"));

            Result result = runProcess(List.of(shim.toString(), "--version"),
                    Map.of("SKILL_MANAGER_CLI", override.toString()));

            assertEquals(0, result.rc, "override rc");
            assertContains(result.out, "--version", "the override ran");
            assertFalse(result.out.contains(STUB_SENTINEL), "the pin did not");
        });

        // ------------------------------------------- finding the running CLI
        //
        // Driven through the package-private seam rather than the ambient
        // process, because System.getenv cannot be set from inside a JVM and a
        // resolution rule nobody can drive is a rule nobody can test — which is
        // how the PATH fallback survived from the first version of this file.

        suite.test("an explicit SKILL_MANAGER_CLI wins and need not be named skill-manager", () -> {
            Path dir = Files.createTempDirectory("running-cli-env-");
            Path built = writeArgvDump(dir.resolve("my-own-build"));

            Path found = RunningCli.locate(
                    Map.of(RunningCli.CLI_ENV, built.toString())::get, "/usr/bin/java", null);

            assertEquals(built.toRealPath(), found.toRealPath(), "the explicit pin is honoured");
        });

        suite.test("a source checkout is found beside SKILL_MANAGER_INSTALL_DIR", () -> {
            // `<repo>/skill-manager` exports the repo root, and the launcher
            // sits in it. This is how the CLI runs in this repo and in the
            // checkout-home graph.
            Path repo = Files.createTempDirectory("running-cli-source-");
            Path launcher = writeArgvDump(repo.resolve("skill-manager"));

            Path found = RunningCli.locate(
                    Map.of(RunningCli.INSTALL_DIR, repo.toString())::get, "/usr/bin/java", null);

            assertEquals(launcher.toRealPath(), found.toRealPath(), "found beside the install dir");
        });

        suite.test("a release tarball is found one level up from its share/ dir", () -> {
            // `<prefix>/bin/skill-manager` exports `<prefix>/share`. This is the
            // Homebrew layout, and the shape the first version of this
            // resolution missed.
            Path prefix = Files.createTempDirectory("running-cli-tarball-");
            Path launcher = writeArgvDump(prefix.resolve("bin/skill-manager"));
            Fs.ensureDir(prefix.resolve("share"));

            Path found = RunningCli.locate(
                    Map.of(RunningCli.INSTALL_DIR, prefix.resolve("share").toString())::get,
                    "/usr/bin/java", null);

            assertEquals(launcher.toRealPath(), found.toRealPath(), "found via ../bin");
        });

        suite.test("an install dir that names something else is rejected, not stretched", () -> {
            Path dir = Files.createTempDirectory("running-cli-wrongname-");
            writeArgvDump(dir.resolve("skill-manager-server"));

            try {
                RunningCli.locate(Map.of(RunningCli.INSTALL_DIR, dir.toString())::get,
                        "/usr/bin/java", null);
                throw new AssertionError("expected a refusal");
            } catch (RunningCli.UnknownLocationException expected) {
                assertContains(expected.getMessage(), "cannot determine which skill-manager build",
                        "the refusal says what it could not do");
            }
        });

        suite.test("with nothing to go on it refuses, and never falls back to PATH", () -> {
            // The load-bearing negative. A perfectly good skill-manager is on
            // the PATH handed in; the resolution must still refuse, because
            // "what is installed globally" is not "what provisioned this home".
            Path onPath = Files.createTempDirectory("running-cli-refuses-");
            Path decoy = decoyOnPath(onPath.resolve("decoy"));

            try {
                RunningCli.locate(Map.of("PATH", decoy.toString())::get, "/usr/bin/java", null);
                throw new AssertionError("expected a refusal rather than a PATH fallback");
            } catch (RunningCli.UnknownLocationException expected) {
                assertFalse(expected.getMessage().contains(decoy.toString()),
                        "the skill-manager on PATH was never even considered");
                assertContains(expected.getMessage(), RunningCli.CLI_ENV,
                        "and the diagnostic says how to fix it");
            }
        });

        return suite.runAll();
    }

    // ------------------------------------------------- cli entrypoint fixtures

    /** Printed by the stub CLI; finding it proves the shim really exec'd. */
    private static final String STUB_SENTINEL = "STUB-CLI-WAS-EXECED";

    /**
     * Printed by the DECOY: a working, executable {@code skill-manager} placed
     * first on PATH. Finding it in any output is issue #61 reproducing.
     */
    private static final String DECOY_SENTINEL = "DECOY-ON-PATH-WAS-EXECED";

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

    /**
     * A working CLI to pin a home to, at {@code <dir>/skill-manager}. Returns
     * the FILE, because a pin is a path to a binary rather than a directory to
     * search — the difference this whole ticket is about.
     */
    private static Path stubCli(Path dir) throws Exception {
        Fs.ensureDir(dir);
        Path stub = dir.resolve("skill-manager");
        Files.writeString(stub, """
                #!/bin/sh
                echo "%s"
                echo "args=$*"
                exit 0
                """.formatted(STUB_SENTINEL));
        stub.toFile().setExecutable(true);
        return stub;
    }

    /**
     * A PATH directory holding a working {@code skill-manager} that is NOT the
     * one anything was pinned to — the stand-in for
     * {@code /opt/homebrew/bin/skill-manager}. Returns the DIRECTORY, to be put
     * on PATH.
     *
     * <p>It succeeds on purpose. A decoy that failed would let a shim pass by
     * accident; this one makes "the shim ignored PATH" the only explanation for
     * its sentinel being absent.
     */
    private static Path decoyOnPath(Path dir) throws Exception {
        Fs.ensureDir(dir);
        Path decoy = dir.resolve("skill-manager");
        Files.writeString(decoy, """
                #!/bin/sh
                echo "%s"
                exit 0
                """.formatted(DECOY_SENTINEL));
        decoy.toFile().setExecutable(true);
        return dir;
    }

    /** A CLI stand-in that reports what {@code var} was set to when it ran. */
    private static Path writeEnvEcho(Path file, String var) throws Exception {
        Fs.ensureDir(file.getParent());
        Files.writeString(file,
                "#!/usr/bin/env bash\nprintf '" + var + "=%s\\n' \"${" + var + "-unset}\"\n");
        file.toFile().setExecutable(true);
        return file;
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
