package dev.skillmanager.cli.installer;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.store.SkillStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class SkillScriptBackendTest {

    public static int run() throws Exception {
        Tests.Suite suite = Tests.suite("SkillScriptBackendTest");

        suite.test("force reruns matching-fingerprint skill-script with existing binary", () -> {
            SkillStore store = new SkillStore(Files.createTempDirectory("skill-script-force-"));
            store.init();
            String unitName = "force-script-skill";
            CliDependency dep = skillScriptDep("force-script-bin");
            scaffoldSkillScript(store, unitName, "force-script-bin");

            Files.writeString(store.cliBinDir().resolve("force-script-bin"), "#!/bin/sh\n");
            store.cliBinDir().resolve("force-script-bin").toFile().setExecutable(true, false);
            String fingerprint = SkillScriptBackend.fingerprintFor(store, unitName, dep);
            CliLock lock = CliLock.load(store);
            RequestedVersion.Requested req = RequestedVersion.of(dep);
            lock.recordInstall(dep.backend(), req.tool(), req.version(),
                    dep.spec(), null, unitName, fingerprint);
            lock.save(store);

            InstallerRegistry registry = new InstallerRegistry();
            registry.installOne(dep, store, unitName);
            assertFalse(Files.exists(store.cliBinDir().resolve("run.log")),
                    "matching fingerprint skips without force");

            registry.installOne(dep, store, unitName, true);
            assertEquals("run\n", Files.readString(store.cliBinDir().resolve("run.log")),
                    "force path reruns script");
        });

        suite.test("planner marks only skill-script deps forced", () -> {
            CliDependency script = skillScriptDep("force-script-bin");
            CliDependency pip = new CliDependency(
                    "cowsay", "pip:cowsay==6.0", null, null, null,
                    true, Map.of());
            AgentUnit unit = new Skill(
                    "planner-skill", "planner", "0.1.0",
                    List.of(script, pip), List.of(), List.of(), Map.of(), "",
                    Files.createTempDirectory("planner-force-skill-")).asUnit();

            InstallPlan plan = new PlanBuilder(Policy.defaults(), null, null, true)
                    .plan(List.of(unit), true, false,
                            Files.createTempDirectory("planner-force-bin-"));

            PlanAction.RunCliInstall scriptAction = cliAction(plan, "force-script-bin");
            PlanAction.RunCliInstall pipAction = cliAction(plan, "cowsay");
            assertTrue(scriptAction.forceScripts(), "skill-script dep marked forced");
            assertFalse(pipAction.forceScripts(), "pip dep not marked forced");
        });

        return suite.runAll();
    }

    private static CliDependency skillScriptDep(String name) {
        Map<String, CliDependency.InstallTarget> install = new LinkedHashMap<>();
        install.put("any", new CliDependency.InstallTarget(
                null, null, name, List.of(), null, "install.sh", List.of()));
        return new CliDependency(
                name, "skill-script:" + name, null, null, name,
                true, install);
    }

    private static void scaffoldSkillScript(SkillStore store, String unitName,
                                            String binaryName) throws Exception {
        Path scripts = store.skillDir(unitName).resolve(SkillScriptBackend.SCRIPTS_DIRNAME);
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("install.sh"), """
                #!/bin/sh
                set -eu
                echo run >> "$SKILL_MANAGER_BIN_DIR/run.log"
                touch "$SKILL_MANAGER_BIN_DIR/%s"
                chmod +x "$SKILL_MANAGER_BIN_DIR/%s"
                """.formatted(binaryName, binaryName));
    }

    private static PlanAction.RunCliInstall cliAction(InstallPlan plan, String depName) {
        for (PlanAction action : plan.actions()) {
            if (action instanceof PlanAction.RunCliInstall cli
                    && cli.dep().name().equals(depName)) {
                return cli;
            }
        }
        throw new AssertionError("missing CLI action for " + depName);
    }
}
