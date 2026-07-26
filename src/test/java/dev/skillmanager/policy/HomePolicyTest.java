package dev.skillmanager.policy;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.commands.HomeCommand;
import dev.skillmanager.commands.SyncCommand;
import dev.skillmanager.commands.UpgradeCommand;
import dev.skillmanager.lock.UnitsLockReader;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.project.ProjectDependencyResolver;
import dev.skillmanager.project.ProjectSyncUseCase;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * {@code home.policy.toml} turns "do not sync or upgrade agent homes in
 * place" from prose asking humans to remember into a gate the tool
 * enforces.
 *
 * <p>The tests that matter are the refusals, and each one asserts that the
 * mutation <em>did not happen</em> — not merely that the exit code was
 * non-zero. A guard that reports refusal after doing the work is worse
 * than no guard, because it reads as safe.
 */
public final class HomePolicyTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("HomePolicyTest");

        // ------------------------------------------------------------ parsing

        suite.test("a home with no policy file is live", () -> {
            SkillStore store = newStore("policy-default-");
            assertEquals(HomePolicy.LIVE, HomePolicy.load(store), "absent file means live");
            assertFalse(HomePolicy.load(store).frozen(), "and not frozen");
            // requireLive must be a no-op here or every existing command breaks.
            HomePolicy.requireLive(store, "sync");
        });

        suite.test("a declared policy round-trips through the file", () -> {
            SkillStore store = newStore("policy-roundtrip-");
            HomePolicy.write(store, HomePolicy.FROZEN);
            assertEquals(HomePolicy.FROZEN, HomePolicy.load(store), "frozen read back");
            assertContains(Files.readString(HomePolicy.file(store)), "policy = \"frozen\"",
                    "written in the documented shape");
            HomePolicy.write(store, HomePolicy.LIVE);
            assertEquals(HomePolicy.LIVE, HomePolicy.load(store), "thawed read back");
        });

        suite.test("an unrecognized policy value fails loudly instead of defaulting to live", () -> {
            SkillStore store = newStore("policy-unknown-");
            Files.writeString(HomePolicy.file(store), "policy = \"read-only\"\n");
            try {
                HomePolicy.load(store);
                throw new AssertionError("expected an unknown policy value to be rejected");
            } catch (java.io.IOException expected) {
                assertContains(expected.getMessage(), "unknown home policy",
                        "the message names the problem");
            }
            // The asymmetry: reading `frozen` as `live` destroys the
            // guarantee silently, so anything unreadable must not resolve
            // to the permissive value.
            Files.writeString(HomePolicy.file(store), "policy =\n");
            try {
                HomePolicy.load(store);
                throw new AssertionError("expected a malformed policy file to be rejected");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage() != null, "malformed file reported");
            }
        });

        // ---------------------------------------------------------- refusals

        suite.test("frozen home refuses `sync` and writes nothing", () -> {
            SkillStore store = seededStore("policy-sync-");
            HomePolicy.write(store, HomePolicy.FROZEN);

            Result result = captureErr(() -> new CommandLine(new SyncCommand(store))
                    .execute("alpha", "--skip-mcp", "--skip-agents"));

            assertEquals(FrozenHomeException.EXIT_CODE, result.rc, "sync refused with the frozen code");
            assertContains(result.err, "home is frozen", "the refusal names the policy");
            assertContains(result.err, HomePolicy.FILENAME, "and the file that declared it");
            assertFalse(Files.exists(UnitsLockReader.defaultPath(store)),
                    "nothing was written — the refusal came before the pipeline");
        });

        suite.test("the sync program itself refuses to build against a frozen home", () -> {
            // Guarding the shared program rather than only its callers is
            // what makes the gate hard to route around: `sync`, `upgrade`,
            // and `sync --lock` are all this one program, and so is whatever
            // entry point gets added next.
            SkillStore store = seededStore("policy-program-");
            HomePolicy.write(store, HomePolicy.FROZEN);
            try {
                dev.skillmanager.app.SyncUseCase.buildProgram(
                        store,
                        GatewayConfig.of(java.net.URI.create("http://127.0.0.1:1")),
                        new dev.skillmanager.app.SyncUseCase.Options(
                                null, false, false, false, false, true),
                        List.of(new dev.skillmanager.app.SyncUseCase.Target.Git("alpha")),
                        List.of());
                throw new AssertionError("expected buildProgram to refuse on a frozen home");
            } catch (FrozenHomeException expected) {
                assertEquals("sync", expected.operation(), "refused operation named");
            }
        });

        suite.test("frozen home refuses `sync --refresh` — the lockfile is not rewritten", () -> {
            // --refresh bypasses the sync pipeline entirely, so it needs the
            // gate at the command boundary rather than in SyncUseCase. It is
            // how a frozen home would otherwise quietly adopt out-of-band
            // drift into its lock.
            SkillStore store = seededStore("policy-refresh-");
            HomePolicy.write(store, HomePolicy.FROZEN);

            Result result = captureErr(() -> new CommandLine(new SyncCommand(store))
                    .execute("--refresh"));

            assertEquals(FrozenHomeException.EXIT_CODE, result.rc, "refresh refused");
            assertFalse(Files.exists(UnitsLockReader.defaultPath(store)),
                    "units.lock.toml was NOT rewritten");
        });

        suite.test("frozen home refuses `upgrade`", () -> {
            SkillStore store = seededStore("policy-upgrade-");
            HomePolicy.write(store, HomePolicy.FROZEN);

            Result named = captureErr(() -> new CommandLine(new UpgradeCommand(store))
                    .execute("alpha"));
            assertEquals(FrozenHomeException.EXIT_CODE, named.rc, "named upgrade refused");
            assertContains(named.err, "upgrade refused", "the refusal names the operation");

            Result all = captureErr(() -> new CommandLine(new UpgradeCommand(store))
                    .execute("--all"));
            assertEquals(FrozenHomeException.EXIT_CODE, all.rc, "upgrade --all refused");
        });

        suite.test("frozen home refuses `project sync` before tearing anything down", () -> {
            SkillStore store = seededStore("policy-project-sync-");
            HomePolicy.write(store, HomePolicy.FROZEN);
            Path repo = Files.createTempDirectory("policy-project-repo-");
            Files.writeString(repo.resolve("skill-project.toml"), """
                    [project]
                    name = "frozen-demo"
                    version = "0.1.0"
                    """);
            SkillProject project = SkillProjectParser.load(repo);

            try {
                new ProjectSyncUseCase(store, GatewayConfig.of(java.net.URI.create(
                        "http://127.0.0.1:1")))
                        .sync(project, ProjectDependencyResolver.Options.defaults());
                throw new AssertionError("expected project sync to refuse on a frozen home");
            } catch (FrozenHomeException expected) {
                assertEquals("project sync", expected.operation(), "refused operation named");
                assertEquals(store.root(), expected.homeRoot(), "and the home that refused");
            }
            assertFalse(Files.exists(repo.resolve(".skill-manager")),
                    "no child home was scaffolded — the refusal came first");
        });

        suite.test("a live home is unaffected: sync runs and writes its lock", () -> {
            SkillStore store = seededStore("policy-live-");
            HomePolicy.write(store, HomePolicy.LIVE);

            int rc = new CommandLine(new SyncCommand(store)).execute("--refresh");

            assertEquals(0, rc, "live refresh succeeds");
            assertTrue(Files.exists(UnitsLockReader.defaultPath(store)),
                    "live home rewrote units.lock.toml");
        });

        // ----------------------------------------------------------- command

        suite.test("`home policy` shows and sets the declared policy", () -> {
            SkillStore store = newStore("policy-cmd-");

            Result shown = captureOut(() -> new CommandLine(new HomeCommand.PolicyCmd(store))
                    .execute());
            assertEquals(0, shown.rc, "show rc");
            assertContains(shown.out, "policy: live", "defaults reported as live");
            assertContains(shown.out, "absent", "and the absence of the file is stated");

            int setRc = new CommandLine(new HomeCommand.PolicyCmd(store)).execute("frozen");
            assertEquals(0, setRc, "set rc");
            assertEquals(HomePolicy.FROZEN, HomePolicy.load(store), "policy declared frozen");

            Result again = captureOut(() -> new CommandLine(new HomeCommand.PolicyCmd(store))
                    .execute());
            assertContains(again.out, "policy: frozen", "frozen reported back");
        });

        suite.test("`home policy nonsense` is rejected without touching the file", () -> {
            SkillStore store = newStore("policy-cmd-bad-");
            HomePolicy.write(store, HomePolicy.FROZEN);
            int rc = new CommandLine(new HomeCommand.PolicyCmd(store)).execute("mostly-frozen");
            assertTrue(rc != 0, "an unknown policy name is refused, got rc " + rc);
            assertEquals(HomePolicy.FROZEN, HomePolicy.load(store),
                    "the previously declared policy is intact");
        });

        return suite.runAll();
    }

    private static SkillStore newStore(String prefix) throws Exception {
        SkillStore store = new SkillStore(
                Files.createTempDirectory(prefix).resolve(".skill-manager"));
        store.init();
        return store;
    }

    /** A store holding one installed skill, enough for sync/upgrade to have a target. */
    private static SkillStore seededStore(String prefix) throws Exception {
        SkillStore store = newStore(prefix);
        Path unit = store.skillDir("alpha");
        Fs.ensureDir(unit);
        Files.writeString(unit.resolve("SKILL.md"),
                "---\nname: alpha\ndescription: policy fixture\n---\nbody\n");
        new UnitStore(store).write(new InstalledUnit(
                "alpha", "0.1.0", InstalledUnit.Kind.LOCAL_DIR,
                InstalledUnit.InstallSource.LOCAL_FILE, "fixture", null, null,
                UnitStore.nowIso(), List.of(), UnitKind.SKILL));
        return store;
    }

    private static Result captureOut(ThrowingInt op) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            int rc = op.run();
            return new Result(rc, out.toString(), "");
        } finally {
            System.setOut(original);
        }
    }

    private static Result captureErr(ThrowingInt op) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err));
            int rc = op.run();
            return new Result(rc, "", err.toString());
        } finally {
            System.setErr(original);
        }
    }

    @FunctionalInterface
    private interface ThrowingInt { int run() throws Exception; }

    private record Result(int rc, String out, String err) {}
}
