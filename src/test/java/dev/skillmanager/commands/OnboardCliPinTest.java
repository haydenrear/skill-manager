package dev.skillmanager.commands;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.InstallUseCase;
import dev.skillmanager.launch.LauncherShims;
import dev.skillmanager.project.ProjectDependencyResolver;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * HP-5 (#376). Two of the three fresh-machine defects #337's terminal
 * evaluation found:
 *
 * <ul>
 *   <li><b>DEF-OHV-184</b> — {@code onboard} left the home with no
 *       {@code bin/cli/skill-manager}, so {@code skt sync} refused "no pinned
 *       CLI" and {@code skt check} consulted no build. The pin is now written in
 *       the shape {@code home shims} writes, and an existing one is kept.</li>
 *   <li><b>DEF-OHV-186</b> — {@code project resolve} refused with
 *       "exit=0, errors=1" while exiting 1, naming no error. The refusal now
 *       names each failed effect and its message.</li>
 * </ul>
 */
public final class OnboardCliPinTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("OnboardCliPinTest");

        suite.test("onboard pins the running CLI into a home that has no entrypoint", () -> {
            SkillStore store = home("onboard-pin-fresh-");
            Path cli = stubCli(store.root().getParent().resolve("build"));
            Path entry = LauncherShims.cliEntrypoint(store);
            assertFalse(Files.exists(entry), "precondition: a fresh home has no CLI pin");

            assertEquals(LauncherShims.PinOutcome.WROTE, OnboardCommand.ensureCliPin(store, cli),
                    "a home with no pin is pinned");
            assertTrue(Files.isRegularFile(entry) && Files.isExecutable(entry),
                    "bin/cli/skill-manager is an executable file");
            assertContains(Files.readString(entry), "cli=\"${SKILL_MANAGER_CLI:-" + cli + "}\"",
                    "the entrypoint names the build that onboarded the home");
            for (String agent : LauncherShims.AGENTS) {
                assertTrue(Files.isRegularFile(LauncherShims.dir(store).resolve(agent)),
                        "the same shape `home shims` writes: launcher " + agent);
            }
        });

        suite.test("onboard keeps an existing pin rather than re-pinning the home", () -> {
            SkillStore store = home("onboard-pin-kept-");
            Path entry = LauncherShims.cliEntrypoint(store);
            Fs.ensureDir(entry.getParent());
            String chosen = "#!/bin/sh\nexec /some/chosen/build \"$@\"\n";
            Files.writeString(entry, chosen);
            Path other = stubCli(store.root().getParent().resolve("other-build"));

            assertEquals(LauncherShims.PinOutcome.KEPT, OnboardCommand.ensureCliPin(store, other),
                    "an existing pin is kept");
            assertEquals(chosen, Files.readString(entry),
                    "re-onboarding does not move the home to a different build");
        });

        suite.test("a dangling entrypoint counts as absent and is replaced, not written through", () -> {
            SkillStore store = home("onboard-pin-dangling-");
            Path entry = LauncherShims.cliEntrypoint(store);
            Fs.ensureDir(entry.getParent());
            Path missing = store.root().getParent().resolve("gone/skill-manager");
            Files.createSymbolicLink(entry, missing);
            Path cli = stubCli(store.root().getParent().resolve("build"));

            assertEquals(LauncherShims.PinOutcome.WROTE, OnboardCommand.ensureCliPin(store, cli),
                    "a dangling pin is repaired");
            assertFalse(Files.isSymbolicLink(entry), "the link was replaced by a file");
            assertFalse(Files.exists(missing), "nothing was written through the link");
        });

        suite.test("an unlocatable CLI writes nothing and says so", () -> {
            SkillStore store = home("onboard-pin-unlocated-");
            assertEquals(LauncherShims.PinOutcome.UNLOCATED,
                    OnboardCommand.ensureCliPin(store, null), "no CLI to pin");
            assertFalse(Files.exists(LauncherShims.cliEntrypoint(store)),
                    "no half-written entrypoint");
            assertFalse(Files.exists(LauncherShims.dir(store).resolve("claude")),
                    "no launchers with nothing to delegate to");
        });

        suite.test("project resolve's publish refusal names the failed effect and no fake exit=0", () -> {
            InstallUseCase.Report report = new InstallUseCase.Report(1, 0, List.of(), Map.of(),
                    List.of(), 0, List.of("EnsureGateway: remote gateway unreachable"));
            String msg = ProjectDependencyResolver.publishFailureMessage(report, false);
            assertContains(msg, "EnsureGateway: remote gateway unreachable",
                    "the underlying error is printed");
            assertFalse(msg.contains("exit=0"),
                    "no exit code that the command does not exit with: " + msg);
        });

        suite.test("a typed halt code is still reported when there was one", () -> {
            InstallUseCase.Report report = new InstallUseCase.Report(1, 6, List.of(), Map.of(),
                    List.of(), 0, List.of("ResolveTransitives: no such unit"));
            String msg = ProjectDependencyResolver.publishFailureMessage(report, true);
            assertContains(msg, "(rolled back)", "rollback named");
            assertContains(msg, "halt exit code 6", "halt code named");
            assertContains(msg, "ResolveTransitives: no such unit", "failure named");
        });

        return suite.runAll();
    }

    private static SkillStore home(String prefix) throws Exception {
        Path root = Files.createTempDirectory(prefix);
        SkillStore store = new SkillStore(root.resolve(".skill-manager"));
        store.init();
        return store;
    }

    private static Path stubCli(Path dir) throws Exception {
        Fs.ensureDir(dir);
        Path stub = dir.resolve("skill-manager");
        Files.writeString(stub, "#!/bin/sh\necho stub \"$@\"\nexit 0\n");
        stub.toFile().setExecutable(true);
        return stub;
    }
}
