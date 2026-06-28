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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.skillmanager._lib.test.Tests.assertContains;
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

        suite.test("planner scopes force-scripts to selected units", () -> {
            AgentUnit target = new Skill(
                    "target-skill", "target", "0.1.0",
                    List.of(skillScriptDep("target-script-bin")),
                    List.of(), List.of(), Map.of(), "",
                    Files.createTempDirectory("planner-force-target-")).asUnit();
            AgentUnit other = new Skill(
                    "other-skill", "other", "0.1.0",
                    List.of(skillScriptDep("other-script-bin")),
                    List.of(), List.of(), Map.of(), "",
                    Files.createTempDirectory("planner-force-other-")).asUnit();

            InstallPlan plan = new PlanBuilder(
                    Policy.defaults(), null, null, true, Set.of("target-skill"))
                    .plan(List.of(target, other), true, false,
                            Files.createTempDirectory("planner-force-scope-bin-"));

            assertTrue(cliAction(plan, "target-script-bin").forceScripts(),
                    "target skill-script dep forced");
            assertFalse(cliAction(plan, "other-script-bin").forceScripts(),
                    "unselected skill-script dep not forced");
        });

        suite.test("skill-script stdout and stderr are written under store logs", () -> {
            SkillStore store = new SkillStore(Files.createTempDirectory("skill-script-log-"));
            store.init();
            String unitName = "logged-script-skill";
            CliDependency dep = skillScriptDep("logged-script-bin");
            scaffoldSkillScript(store, unitName, "logged-script-bin", """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    echo "stdout from script"
                    echo "stderr from script" >&2
                    touch "$SKILL_MANAGER_BIN_DIR/logged-script-bin"
                    chmod +x "$SKILL_MANAGER_BIN_DIR/logged-script-bin"
                    """);

            new InstallerRegistry().installOne(dep, store, unitName, true);

            String logs = readSkillScriptLogs(store);
            assertContains(logs, "stdout from script", "stdout captured in skill-script log");
            assertContains(logs, "stderr from script", "stderr captured in skill-script log");
        });

        suite.test("skill-script failure includes log path and output tail", () -> {
            SkillStore store = new SkillStore(Files.createTempDirectory("skill-script-fail-"));
            store.init();
            String unitName = "failing-script-skill";
            CliDependency dep = skillScriptDep("failing-script-bin");
            scaffoldSkillScript(store, unitName, "failing-script-bin", """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    for i in $(seq 1 45); do
                      printf 'line-%02d\\n' "$i"
                    done
                    echo "stderr-tail" >&2
                    exit 7
                    """);

            String message;
            try {
                new InstallerRegistry().installOne(dep, store, unitName, true);
                throw new AssertionError("expected failing skill-script");
            } catch (IOException expected) {
                message = expected.getMessage();
            }

            assertContains(message, "log:", "failure includes log path");
            assertContains(message, "last 40 line(s)", "failure includes output tail header");
            assertContains(message, "line-45", "failure includes recent stdout");
            assertContains(message, "stderr-tail", "failure includes recent stderr");
            assertFalse(message.contains("line-01"), "failure tail omits oldest output");
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
        scaffoldSkillScript(store, unitName, binaryName, """
                #!/bin/sh
                set -eu
                echo run >> "$SKILL_MANAGER_BIN_DIR/run.log"
                touch "$SKILL_MANAGER_BIN_DIR/%s"
                chmod +x "$SKILL_MANAGER_BIN_DIR/%s"
                """.formatted(binaryName, binaryName));
    }

    private static void scaffoldSkillScript(SkillStore store, String unitName,
                                            String binaryName, String script) throws Exception {
        Path scripts = store.skillDir(unitName).resolve(SkillScriptBackend.SCRIPTS_DIRNAME);
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("install.sh"), script);
    }

    private static String readSkillScriptLogs(SkillStore store) throws IOException {
        Path dir = store.root().resolve("logs").resolve("skill-scripts");
        if (!Files.isDirectory(dir)) return "";
        StringBuilder out = new StringBuilder();
        try (var stream = Files.list(dir)) {
            for (Path log : stream.sorted().toList()) {
                if (Files.isRegularFile(log)) out.append(Files.readString(log)).append('\n');
            }
        }
        return out.toString();
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
