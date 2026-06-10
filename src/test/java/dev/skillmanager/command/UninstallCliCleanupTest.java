package dev.skillmanager.command;

import dev.skillmanager._lib.fixtures.ContainedSkillSpec;
import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.PluginUnit;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertNotNull;
import static dev.skillmanager._lib.test.Tests.assertTrue;

/**
 * ISSUE-91B: uninstall prunes only orphaned managed CLI deps. The
 * lower-level remove path still deletes the unit without touching CLI
 * artifacts or {@code cli-lock.toml}.
 */
public final class UninstallCliCleanupTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("UninstallCliCleanupTest");

        suite.test("uninstall prunes orphaned skill-script binary and cli-lock row", () -> {
            TestHarness h = TestHarness.create();
            installSkillScriptSkill(h, "alpha", "alpha-tool");
            h.seedUnit("alpha", UnitKind.SKILL);
            Path binary = touchCliBin(h, "alpha-tool");
            recordSkillScript(h, "alpha-tool", "alpha");

            runUninstallCleanup(h, "alpha");

            assertFalse(Files.exists(binary), "orphaned skill-script binary removed");
            assertEquals(null, CliLock.load(h.store()).get("skill-script", "alpha-tool"),
                    "orphaned cli-lock row removed");
        });

        suite.test("uninstall preserves shared CLI artifact and rewrites requested_by", () -> {
            TestHarness h = TestHarness.create();
            installSkillScriptSkill(h, "alpha", "shared-tool");
            installSkillScriptSkill(h, "beta", "shared-tool");
            h.seedUnit("alpha", UnitKind.SKILL);
            h.seedUnit("beta", UnitKind.SKILL);
            Path binary = touchCliBin(h, "shared-tool");
            recordSkillScript(h, "shared-tool", "alpha");
            recordSkillScript(h, "shared-tool", "beta");

            runUninstallCleanup(h, "alpha");

            assertTrue(Files.exists(binary), "shared CLI binary remains");
            CliLock.Entry entry = CliLock.load(h.store()).get("skill-script", "shared-tool");
            assertNotNull(entry, "shared cli-lock row remains");
            assertEquals(List.of("beta"), entry.requestedBy(), "only survivor remains in requested_by");
        });

        suite.test("uninstall re-walks plugin-level and contained-skill CLI deps", () -> {
            TestHarness h = TestHarness.create();
            installPlugin(h, "widget",
                    DepSpec.of().cli("pip:plugin-cli==1.0").build(),
                    new ContainedSkillSpec("widget-impl",
                            DepSpec.of().cli("pip:contained-cli==2.0").build()));
            h.seedUnit("widget", UnitKind.PLUGIN);
            Path pluginBin = touchCliBin(h, "plugin-cli");
            Path containedBin = touchCliBin(h, "contained-cli");
            recordPip(h, "plugin-cli", "1.0", "widget");
            recordPip(h, "contained-cli", "2.0", "widget");

            runUninstallCleanup(h, "widget");

            CliLock after = CliLock.load(h.store());
            assertEquals(null, after.get("pip", "plugin-cli"), "plugin-level CLI lock row removed");
            assertEquals(null, after.get("pip", "contained-cli"), "contained-skill CLI lock row removed");
            assertFalse(Files.exists(pluginBin), "plugin-level CLI binary removed");
            assertFalse(Files.exists(containedBin), "contained-skill CLI binary removed");
        });

        suite.test("uninstall preserves CLI dep claimed by surviving plugin-contained skill", () -> {
            TestHarness h = TestHarness.create();
            installSkill(h, "alpha", DepSpec.of().cli("pip:shared-cli==1.0").build());
            installPlugin(h, "widget", DepSpec.empty(),
                    new ContainedSkillSpec("widget-impl",
                            DepSpec.of().cli("pip:shared-cli==1.0").build()));
            h.seedUnit("alpha", UnitKind.SKILL);
            h.seedUnit("widget", UnitKind.PLUGIN);
            Path binary = touchCliBin(h, "shared-cli");
            recordPip(h, "shared-cli", "1.0", "alpha");
            recordPip(h, "shared-cli", "1.0", "widget");

            runUninstallCleanup(h, "alpha");

            assertTrue(Files.exists(binary), "plugin-contained claim keeps CLI binary");
            CliLock.Entry entry = CliLock.load(h.store()).get("pip", "shared-cli");
            assertNotNull(entry, "shared cli-lock row remains");
            assertEquals(List.of("widget"), entry.requestedBy(), "plugin survivor remains in requested_by");
        });

        suite.test("lower-level remove leaves CLI artifacts and cli-lock rows alone", () -> {
            TestHarness h = TestHarness.create();
            installSkillScriptSkill(h, "alpha", "alpha-tool");
            h.seedUnit("alpha", UnitKind.SKILL);
            Path binary = touchCliBin(h, "alpha-tool");
            recordSkillScript(h, "alpha-tool", "alpha");

            Program<RemoveUseCase.Report> program = RemoveUseCase.buildProgram(
                    h.store(), null, "alpha", List.of(), false);
            Executor.Outcome<RemoveUseCase.Report> outcome = new Executor(h.store(), null).run(program);

            assertFalse(outcome.rolledBack(), "remove did not roll back");
            assertFalse(h.store().containsUnit("alpha"), "unit removed from store");
            assertTrue(Files.exists(binary), "CLI binary left for lower-level remove");
            assertNotNull(CliLock.load(h.store()).get("skill-script", "alpha-tool"),
                    "cli-lock row left for lower-level remove");
        });

        return suite.runAll();
    }

    private static void runUninstallCleanup(TestHarness h, String unitName) throws Exception {
        Program<RemoveUseCase.Report> program = RemoveUseCase.buildProgram(
                h.store(), null, unitName, null, false, true);
        Executor.Outcome<RemoveUseCase.Report> outcome = new Executor(h.store(), null).run(program);
        assertFalse(outcome.rolledBack(), "uninstall cleanup did not roll back");
        assertEquals(0, outcome.result().errorCount(), "no reported effect errors");
    }

    private static void installSkillScriptSkill(TestHarness h, String name, String tool) throws Exception {
        Path dir = h.store().skillDir(name);
        Files.createDirectories(dir.resolve("skill-scripts"));
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: fixture
                ---
                body
                """.formatted(name));
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [[cli_dependencies]]
                spec = "skill-script:%2$s"
                on_path = "__missing_%2$s"

                [cli_dependencies.install.any]
                script = "install.sh"
                binary = "%2$s"

                [skill]
                name = "%1$s"
                version = "0.1.0"
                """.formatted(name, tool));
        Files.writeString(dir.resolve("skill-scripts/install.sh"), "#!/usr/bin/env sh\n");
    }

    private static void installPlugin(TestHarness h, String name,
                                      DepSpec pluginLevelDeps,
                                      ContainedSkillSpec... contained) throws Exception {
        Path tmp = Files.createTempDirectory("uninstall-cli-plugin-");
        PluginUnit p = UnitFixtures.scaffoldPlugin(tmp, name, pluginLevelDeps, contained);
        Path dst = h.store().pluginsDir().resolve(name);
        Fs.ensureDir(h.store().pluginsDir());
        Fs.copyRecursive(p.sourcePath(), dst);
    }

    private static void installSkill(TestHarness h, String name, DepSpec deps) throws Exception {
        Path tmp = Files.createTempDirectory("uninstall-cli-skill-");
        AgentUnit u = UnitFixtures.buildEquivalent(UnitKind.SKILL, tmp, name, deps);
        Path dst = h.store().skillDir(name);
        Fs.ensureDir(h.store().skillsDir());
        Fs.copyRecursive(u.sourcePath(), dst);
    }

    private static Path touchCliBin(TestHarness h, String name) throws Exception {
        Fs.ensureDir(h.store().cliBinDir());
        Path binary = h.store().cliBinDir().resolve(name);
        Files.writeString(binary, "#!/usr/bin/env sh\n");
        binary.toFile().setExecutable(true, false);
        return binary;
    }

    private static void recordSkillScript(TestHarness h, String tool, String requester) throws Exception {
        CliLock lock = CliLock.load(h.store());
        lock.recordInstall("skill-script", tool, null, "skill-script:" + tool, null, requester, "fingerprint");
        lock.save(h.store());
    }

    private static void recordPip(TestHarness h, String tool, String version, String requester) throws Exception {
        CliLock lock = CliLock.load(h.store());
        lock.recordInstall("pip", tool, version, "pip:" + tool + "==" + version, null, requester);
        lock.save(h.store());
    }
}
