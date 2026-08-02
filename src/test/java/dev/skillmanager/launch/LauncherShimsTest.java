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

        suite.test("another home's AGENT-dir plugin bins are dropped from the launch PATH", () -> {
            // D8. The walk looked for a STORE root above each PATH entry, and
            // `<root>/.claude` is not one — no descriptor, no installed/ +
            // skills/ pair — so a foreign home's
            // `.claude/plugins/cache/<marketplace>/<plugin>/<v>/bin` survived
            // every launch. Measured at PATH position 4, ahead of /usr/bin,
            // with the foreign STORE bin beside it correctly stripped: the
            // isolation this class exists to provide, silently half-absent.
            //
            // The predicate that decides it already existed
            // (agentDirOwnedByAHome, written for #145) and was not called —
            // which is the "enumeration correct for imagined shapes" failure
            // LaunchEnv's own comment names.
            Home foreign = Home.create("launch-agentbin-foreign-");
            Home project = Home.create("launch-agentbin-project-");
            Path foreignPluginBin = foreign.root.resolve(
                    ".claude/plugins/cache/claude-plugins-official/jdtls-lsp/1.0.0/bin");
            Fs.ensureDir(foreignPluginBin);
            Path ownPluginBin = project.root.resolve(
                    ".claude/plugins/cache/claude-plugins-official/jdtls-lsp/1.0.0/bin");
            Fs.ensureDir(ownPluginBin);
            String inherited = foreign.store.cliBinDir() + File.pathSeparator
                    + foreignPluginBin + File.pathSeparator
                    + ownPluginBin + File.pathSeparator + "/usr/bin";

            // Companion 1, mandatory: the planted entries really are on the
            // PATH handed to the sanitizer. Without this the assertion below
            // measures nothing.
            List<Path> inheritedEntries = new java.util.ArrayList<>();
            for (String raw : inherited.split(File.pathSeparator, -1)) {
                inheritedEntries.add(Path.of(raw).toAbsolutePath().normalize());
            }
            assertTrue(inheritedEntries.contains(foreignPluginBin.toAbsolutePath().normalize()),
                    "precondition: the foreign agent-home plugin bin is on the inherited PATH");
            assertTrue(inheritedEntries.contains(
                            foreign.store.cliBinDir().toAbsolutePath().normalize()),
                    "precondition: the foreign store bin is on it too");

            LaunchEnv launch = LaunchEnv.of(project.store, null, inherited, false);
            List<Path> entries = launch.pathEntries();

            // Companion 2: the store-bin entry IS stripped in the same run.
            // That proves the sanitizer ran and recognised the foreign home,
            // which is what makes the agent-dir entry's survival meaningful.
            assertFalse(entries.contains(foreign.store.cliBinDir().toAbsolutePath().normalize()),
                    "the foreign home's store bin is stripped — the sanitizer ran");
            assertFalse(entries.contains(foreignPluginBin.toAbsolutePath().normalize()),
                    "and so is its agent-home plugin bin");
            // The can-fail control: this home's OWN agent-dir plugin bin is not
            // foreign and must survive, or the fix is just "strip .claude".
            assertTrue(entries.contains(ownPluginBin.toAbsolutePath().normalize()),
                    "the ACTIVE home's own agent-home plugin bin survives");
            assertTrue(entries.contains(Path.of("/usr/bin")), "unrelated entries survive");
        });

        suite.test("a .claude no home owns is left on PATH", () -> {
            // The negative half of the structural predicate, and the reason it
            // is two conditions rather than a name test: there are many
            // `.claude` directories on a machine and no home manages most of
            // them. A rule that fires on those is a rule somebody switches off.
            Home project = Home.create("launch-unowned-project-");
            Path unowned = Files.createTempDirectory("launch-unowned-")
                    .resolve(".claude/plugins/cache/x/y/1.0.0/bin");
            Fs.ensureDir(unowned);

            LaunchEnv launch = LaunchEnv.of(project.store, null, unowned.toString(), false);

            assertTrue(launch.pathEntries().contains(unowned.toAbsolutePath().normalize()),
                    "a .claude with no Skill Manager store beside it is not a foreign home");
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

        suite.test("the pin another tool reads back out of the body is a real executable", () -> {
            // Two failures at once, and neither is caught by asserting that the
            // body CONTAINS the pin.
            //
            // 1. The pin has to be a path that resolves. A body carrying
            //    `@SKILL_MANAGER_CLI_PIN@`, or `$pin`, or any other four
            //    characters satisfies "contains the pin" vacuously — the string
            //    is there, it just does not name a file. This is the same class
            //    of defect as a printed remedy that does not parse, and this
            //    epic has now shipped that class twice.
            //
            // 2. The shape is a CONTRACT WITH A SECOND READER. bootstrap-home.sh
            //    finds this file by PIN_MARKER and then extracts the pinned path
            //    by string prefix, exactly as reproduced below, so it can assert
            //    the pinned build still exists. Hoisting the path into a `pin=`
            //    variable left the shim working perfectly and handed that reader
            //    `$pin`, which refused every home it checked. The extraction is
            //    duplicated here on purpose: it is the only way a change to the
            //    shape fails in THIS repository rather than in the other one.
            Home home = Home.create("cli-entrypoint-pin-resolves-");
            Path pin = stubCli(home.root.resolve("pin"));
            LauncherShims.write(home.store, pin);
            String body = Files.readString(home.store.cliBinDir().resolve("skill-manager"));

            assertContains(body, LauncherShims.PIN_MARKER,
                    "the marker bootstrap-home.sh keys on to decide this file is not its own");
            // GENERATED_PIN_PREFIX, verbatim, then the trailing `}` and `"`.
            // FIRST match in the whole file, comments included — that is
            // `grep -m1 -F`, and reproducing it faithfully is the point. A
            // filtered version of this passed while a COMMENT quoting the prefix
            // sat above the real assignment and would have won the grep.
            String prefix = "cli=\"${SKILL_MANAGER_CLI:-";
            String line = body.lines().filter(l -> l.contains(prefix)).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no `" + prefix + "…` line — bootstrap-home.sh reads the pin out of"
                                    + " this exact shape and dies when it cannot find it"));
            String extracted = line.substring(line.indexOf(prefix) + prefix.length());
            extracted = extracted.substring(0, extracted.length() - 2);

            assertEquals(pin.toString(), extracted, "the extracted pin is the absolute path");
            assertTrue(Files.isExecutable(Path.of(extracted)),
                    "and it names an executable that exists — a pin is only a pin if it resolves");
            assertFalse(extracted.contains("$"),
                    "no shell variable survived into it: another tool reads this line as a PATH,"
                            + " not as bash, so nothing in it gets expanded");
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

        // ------------------------------------------- exec'ing itself, forever
        //
        // Every case here is BOUNDED and asserts the bound was not hit. The
        // defect is a shim that re-enters itself with the same environment and
        // does it again: no output, no timeout, no non-zero exit, and it
        // outlives the parent that started it. An unbounded runner would not
        // fail on it — it would hang the suite, which reports nothing at all.
        //
        // It is reached by construction, not by a typo:
        // git-integration-repo's close-change.sh picks the home's own
        // bin/cli/skill-manager and then runs it as
        // SKILL_MANAGER_CLI="$CLI" "$CLI" …. One orphan from that path burned
        // 7:03 of CPU over 13:06 elapsed, silently.

        suite.test("the cli entrypoint refuses to exec itself and uses its pin instead", () -> {
            // The measured reproduction, as an assertion. Two things have to
            // hold and neither implies the other: it TERMINATED (the bound was
            // not hit), and the pinned CLI is what actually ran — a guard that
            // exited cleanly without launching would satisfy the first alone,
            // which is the failure mode this whole file exists to reject.
            Home home = Home.create("cli-entrypoint-self-exec-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Path shim = home.store.cliBinDir().resolve("skill-manager");

            Result result = runBounded(List.of(shim.toString(), "--version"),
                    Map.of("SKILL_MANAGER_CLI", shim.toString()));

            assertFalse(result.out.contains(SHIM_TIMED_OUT),
                    "the shim TERMINATED — before the guard this exec'd itself forever, and the"
                            + " only symptom was a process that never returned");
            assertContains(result.out, STUB_SENTINEL,
                    "and the pinned CLI ran: falling back is the point, not merely not hanging");
            assertContains(result.out, "args=--version", "arguments handed over unchanged");
            assertContains(result.out, "resolves to this shim",
                    "and the ignored value is reported rather than silently dropped");
            assertEquals(0, result.rc, "so it exits with the exec'd CLI's status");
        });

        suite.test("a symlink to the cli entrypoint is still the cli entrypoint", () -> {
            // Physical paths, not strings. The same file reached under another
            // name is the same file, and a string compare would exec it.
            Home home = Home.create("cli-entrypoint-self-link-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            Path alias = home.root.resolve("aliased-skill-manager");
            Files.createSymbolicLink(alias, shim);

            Result result = runBounded(List.of(shim.toString(), "--version"),
                    Map.of("SKILL_MANAGER_CLI", alias.toString()));

            assertFalse(result.out.contains(SHIM_TIMED_OUT), "terminated");
            assertContains(result.out, STUB_SENTINEL, "the pin ran, not the alias");
            assertEquals(0, result.rc, "rc");
        });

        suite.test("a relative spelling of the cli entrypoint is still the cli entrypoint", () -> {
            Home home = Home.create("cli-entrypoint-self-relative-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Path shim = home.store.cliBinDir().resolve("skill-manager");

            ProcessBuilder pb = new ProcessBuilder(shim.toString(), "--version")
                    .directory(home.store.cliBinDir().toFile())
                    .redirectErrorStream(true);
            pb.environment().put("SKILL_MANAGER_CLI", "./skill-manager");
            Result result = bounded(pb);

            assertFalse(result.out.contains(SHIM_TIMED_OUT), "terminated");
            assertContains(result.out, STUB_SENTINEL, "`./skill-manager` did not slip past");
            assertEquals(0, result.rc, "rc");
        });

        suite.test("a pin that is the entrypoint itself refuses loudly rather than looping", () -> {
            // The one case with nothing to fall back to. Refusing is right here
            // and falling back is right above, and the difference is which of
            // the two inputs is the mistake: an environment variable is a
            // request a caller made, the pin is a fact `home shims` recorded.
            Home home = Home.create("cli-entrypoint-self-pin-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Path shim = home.store.cliBinDir().resolve("skill-manager");
            // A home shims run that pinned the home's own entrypoint — which is
            // exactly what an unguarded SKILL_MANAGER_CLI self-reference reaching
            // RunningCli.locate would have written.
            Files.writeString(shim, LauncherShims.cliScript(shim));
            Fs.makeExecutable(shim);

            Result result = runBounded(List.of(shim.toString(), "--version"), Map.of());

            assertFalse(result.out.contains(SHIM_TIMED_OUT), "terminated");
            assertEquals(LauncherShims.SELF_EXEC_EXIT_CODE, result.rc,
                    "refused with the misconfiguration status, not 0 and not a hang");
            assertContains(result.out, "is this shim itself", "and says what it found");
            assertContains(result.out, "home shims", "and names the command that repairs it");
        });

        suite.test("an agent launcher refuses to exec itself and uses the home's entrypoint", () -> {
            // Same defect in the other generated shim, and worse in one way:
            // its last line appends `exec --home … -- claude` to the argument
            // list every time round, so the argv grows without bound too.
            Home home = Home.create("shims-self-exec-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));
            Path launcher = LauncherShims.dir(home.store).resolve("claude");

            Result result = runBounded(List.of(launcher.toString(), "--model", "opus"),
                    Map.of("SKILL_MANAGER_CLI", launcher.toString()));

            assertFalse(result.out.contains(SHIM_TIMED_OUT), "terminated");
            assertContains(result.out, STUB_SENTINEL,
                    "it fell back to <home>/bin/cli/skill-manager, which exec'd the pin");
            assertContains(result.out,
                    "exec --home " + home.store.root().toRealPath() + " -- claude --model opus",
                    "with the launch arguments unchanged — the fallback is a real launch");
            assertEquals(0, result.rc, "rc");
        });

        suite.test("both generated shims carry the guard, and neither carries a bare exec", () -> {
            // The bytes, so a regeneration that drops the guard is caught even
            // where no process is run. Keyed on the stable marker rather than on
            // prose, for the reason PIN_MARKER exists.
            Home home = Home.create("shims-guard-marker-");
            LauncherShims.write(home.store, stubCli(home.root.resolve("pin")));

            List<Path> shims = new java.util.ArrayList<>();
            shims.add(home.store.cliBinDir().resolve("skill-manager"));
            for (String agent : LauncherShims.AGENTS) {
                shims.add(LauncherShims.dir(home.store).resolve(agent));
            }
            for (Path shim : shims) {
                String body = Files.readString(shim);
                assertContains(body, LauncherShims.SELF_GUARD_MARKER,
                        shim.getFileName() + " carries the self-exec guard");
                // `cli="$sm_cli"` exists only at a CALL site. Asserting on the
                // name `sm_refuse_self_exec` would not do: the definition
                // contains it too, so a body that carried the function and
                // never invoked it — which is exactly what deleting the call
                // leaves behind, and which still hangs — passed that version of
                // this assertion while the four process-level cases below
                // failed. Assert on the line only a caller has.
                assertContains(body, "cli=\"$sm_cli\"",
                        shim.getFileName() + " actually CALLS the guard rather than merely"
                                + " defining it");
                assertFalse(body.contains("%%"),
                        shim.getFileName() + " has no doubled percent: neither generated body"
                                + " runs through .formatted() any more, so a doubled one would"
                                + " reach bash literally");
            }
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

    /**
     * As {@link #runProcess}, but bounded, and it reads the child's output on
     * another thread.
     *
     * <p>Both halves are needed for the self-exec cases and neither is
     * hygiene. {@code runProcess} blocks in {@code readAllBytes()} until the
     * child's stdout closes, so a child that never exits parks the test thread
     * <em>before</em> any {@code waitFor} bound could apply — the suite hangs
     * and reports nothing, which is the same non-answer as the defect. And the
     * descendants are destroyed rather than just the child: a self-exec'ing
     * shim replaces its own process image, so the thing still running when the
     * bound expires need not be the process that was started.
     */
    private static Result runBounded(List<String> argv, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.environment().putAll(env);
        return bounded(pb);
    }

    private static Result bounded(ProcessBuilder pb) throws Exception {
        Process p = pb.start();
        java.util.concurrent.CompletableFuture<String> output =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return new String(p.getInputStream().readAllBytes());
                    } catch (java.io.IOException e) {
                        return "";
                    }
                });
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor();
            return new Result(-1, SHIM_TIMED_OUT, SHIM_TIMED_OUT);
        }
        String out = output.get(30, java.util.concurrent.TimeUnit.SECONDS);
        return new Result(p.exitValue(), out, out);
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
