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
            // Through the production recorder, which is now the only thing
            // that knows how to turn a dep into a row.
            InstallerRegistry registry = new InstallerRegistry();
            CliLock lock = CliLock.load(store);
            dev.skillmanager.lock.CliInstallRecorder.record(lock, registry, dep, store, unitName);
            lock.save(store);
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

        // OHV-4 (#341) (c): whatever an installer writes, the shim that is left
        // in bin/cli after the install spells no form of this home, the exec
        // line included. Two installers: deploy-helm's real shape (both lines
        // literal), and one that half-adopted the recipe (token on the export,
        // this home literal on the exec) -- the second is the one the old
        // "already holds the token" early return let through unchanged.
        for (boolean halfAdopted : new boolean[]{false, true}) {
            String label = halfAdopted ? "half-adopted" : "deploy-helm";
            suite.test("OHV-4 (c): a " + label + " installer's shim leaves the install with a token exec line", () -> {
                SkillStore store = new SkillStore(Files.createTempDirectory("skill-script-shim-" + label + "-"));
                store.init();
                String unitName = "shim-token-skill";
                String tool = "shim-token-bin";
                CliDependency dep = skillScriptDep(tool);
                // An UNQUOTED heredoc, as deploy-helm's install-console-script.sh
                // writes its launcher: $SKILL_DIR and $venv expand at write
                // time (the freeze), \$ stays literal (the recipe's token).
                String exportLine = halfAdopted
                        ? "SKILL_MANAGER_SHIM_HOME=\"\\$(cd \"\\$(dirname \"\\$0\")/../..\" && pwd)\"\n"
                                + "export UNIT_ROOT=\"\\${SKILL_MANAGER_SHIM_HOME}/skills/" + unitName + "\""
                        : "export UNIT_ROOT=\"$SKILL_DIR\"";
                scaffoldSkillScript(store, unitName, tool, """
                        #!/usr/bin/env bash
                        set -euo pipefail
                        venv="$SKILL_MANAGER_CACHE_DIR/skill-script-%1$s-%2$s/venv"
                        mkdir -p "$venv/bin"
                        printf '#!/bin/sh\\necho %2$s-ran\\n' > "$venv/bin/%2$s"
                        chmod +x "$venv/bin/%2$s"
                        cat > "$SKILL_MANAGER_BIN_DIR/%2$s" <<EOF
                        #!/usr/bin/env bash
                        %3$s
                        exec "$venv/bin/%2$s" "\\$@"
                        EOF
                        chmod 0755 "$SKILL_MANAGER_BIN_DIR/%2$s"
                        """.formatted(unitName, tool, exportLine));

                new InstallerRegistry().installOne(dep, store, unitName, true);

                Path shim = store.cliBinDir().resolve(tool);
                String body = Files.readString(shim);
                assertTrue(dev.skillmanager.store.ShimHomeContract.frozenHomeLines(store.root(), shim).isEmpty(),
                        "no line of the installed shim spells this home: " + body);
                assertFalse(body.contains(store.root().toString()), "given spelling gone: " + body);
                assertFalse(body.contains(store.root().toRealPath().toString()), "real spelling gone: " + body);
                assertContains(body, "exec \"${SKILL_MANAGER_SHIM_HOME}/cache/skill-script-" + unitName
                        + "-" + tool + "/venv/bin/" + tool + "\"", "the exec line derives the home: " + body);
                assertEquals(1, (int) body.lines().filter(l -> l.startsWith("SKILL_MANAGER_SHIM_HOME=")).count(),
                        "one assignment of the token: " + body);
                Process p = new ProcessBuilder("bash", shim.toString()).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                assertEquals(0, p.waitFor(), "the installed shim still runs: " + out);
                assertContains(out, tool + "-ran", "and runs its tool");
            });
        }

        suite.test("SI-11-DF-01: a skill-script dep declared on a CONTAINED skill resolves", () -> {
            // A skill-script: installer is resolved by the INSTALLED UNIT's
            // name, never by the declaring skill's — PluginUnit.unionCli
            // flattens every contained skill's cli_dependencies onto the
            // plugin, and CliDependency carries no field saying which skill
            // declared it. With two rungs that made a dep on a contained skill
            // UNSPELLABLE: installing the tla-spec-dev plugin from git reported
            //
            //   ✗ cli: tlc2 install failed for tla-spec-dev — skill-script not
            //     found: <home>/skills/tla-spec-dev/skill-scripts/install-tlc2.sh
            //
            // on every fresh home, naming the STANDALONE rung it probed first,
            // which is why this read as a migration leftover rather than as an
            // unsupported shape.
            SkillStore store = new SkillStore(Files.createTempDirectory("skill-script-contained-"));
            store.init();
            String pluginName = "carrier-plugin";
            CliDependency dep = skillScriptDep("contained-script-bin");

            // The installer lives in the CONTAINED skill's skill-scripts/, and
            // nowhere else: neither unit rung holds it.
            Path contained = store.pluginsDir().resolve(pluginName)
                    .resolve("skills").resolve("declaring-skill");
            Path scripts = contained.resolve(SkillScriptBackend.SCRIPTS_DIRNAME);
            Files.createDirectories(scripts);
            Files.writeString(scripts.resolve("install.sh"), """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    echo "SKILL_DIR=$SKILL_DIR"
                    touch "$SKILL_MANAGER_BIN_DIR/contained-script-bin"
                    chmod +x "$SKILL_MANAGER_BIN_DIR/contained-script-bin"
                    """);

            new InstallerRegistry().installOne(dep, store, pluginName, true);

            assertTrue(Files.exists(store.cliBinDir().resolve("contained-script-bin")),
                    "the installer on the contained rung actually ran");
            // SKILL_DIR is the CONTAINED SKILL's root, not the plugin's. That
            // is the point: the script sits in that skill's skill-scripts/ and
            // its relative content is that skill's. It also means the two
            // shapes are not interchangeable — a script written for the plugin
            // rung spells $SKILL_DIR/skills/<skill>/… and one written for this
            // rung spells $SKILL_DIR/….
            assertContains(readSkillScriptLogs(store), "SKILL_DIR=" + contained,
                    "SKILL_DIR is the declaring skill's root");
        });

        suite.test("SI-11-DF-01: the plugin's own rung still wins over a contained one", () -> {
            // The contained rung is probed LAST, after both unit rungs, so
            // nothing that resolves today resolves anywhere else. A plugin that
            // keeps its installers at its own root — which is what tla-spec-dev
            // does, and what the docs prescribe — never reaches the new code.
            SkillStore store = new SkillStore(Files.createTempDirectory("skill-script-precedence-"));
            store.init();
            String pluginName = "both-rungs-plugin";
            CliDependency dep = skillScriptDep("both-rungs-bin");

            Path pluginRoot = store.pluginsDir().resolve(pluginName);
            Path pluginScripts = pluginRoot.resolve(SkillScriptBackend.SCRIPTS_DIRNAME);
            Files.createDirectories(pluginScripts);
            Files.writeString(pluginScripts.resolve("install.sh"), """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    echo "ran=plugin-root"
                    touch "$SKILL_MANAGER_BIN_DIR/both-rungs-bin"
                    chmod +x "$SKILL_MANAGER_BIN_DIR/both-rungs-bin"
                    """);
            Path containedScripts = pluginRoot.resolve("skills/inner")
                    .resolve(SkillScriptBackend.SCRIPTS_DIRNAME);
            Files.createDirectories(containedScripts);
            Files.writeString(containedScripts.resolve("install.sh"), """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    echo "ran=contained"
                    touch "$SKILL_MANAGER_BIN_DIR/both-rungs-bin"
                    chmod +x "$SKILL_MANAGER_BIN_DIR/both-rungs-bin"
                    """);

            new InstallerRegistry().installOne(dep, store, pluginName, true);

            String logs = readSkillScriptLogs(store);
            assertContains(logs, "ran=plugin-root", "the plugin's own rung ran");
            assertFalse(logs.contains("ran=contained"), "and the contained rung did not: " + logs);
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
