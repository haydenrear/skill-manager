///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES ../../sdk/java/src/main/java/com/hayden/testgraphsdk/sdk/*.java
//SOURCES SkillDevGraphSupport.java
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2

import com.hayden.testgraphsdk.sdk.Node;
import com.hayden.testgraphsdk.sdk.NodeResult;
import com.hayden.testgraphsdk.sdk.NodeSpec;
import com.hayden.testgraphsdk.sdk.ProcessRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SkillDevUnitsInstalled {
    static final NodeSpec SPEC = NodeSpec.of("skill-dev.units.installed")
            .kind(NodeSpec.Kind.FIXTURE)
            .dependsOn("skill-dev.installed")
            .tags("skill-dev", "fixture")
            .timeout("180s")
            .output("projectDir", "string");

    public static void main(String[] args) {
        Node.run(args, SPEC, ctx -> {
            String home = ctx.get("env.prepared", "home").orElse(null);
            String claudeHome = ctx.get("env.prepared", "claudeHome").orElse(null);
            String codexHome = ctx.get("env.prepared", "codexHome").orElse(null);
            String geminiHome = ctx.get("env.prepared", "geminiHome").orElse(null);
            String gatewayPort = ctx.get("env.prepared", "gatewayPort").orElse(null);
            String registryUrl = ctx.get("registry.up", "baseUrl").orElse(null);
            if (home == null || claudeHome == null || codexHome == null || geminiHome == null
                    || gatewayPort == null || registryUrl == null) {
                return NodeResult.fail("skill-dev.units.installed", "missing upstream context");
            }
            Path h = Path.of(home);
            Path root = h.resolve("skill-dev-fixtures");
            Path project = h.resolve("skill-dev-project");
            SkillDevGraphSupport.createProject(project);
            SkillDevGraphSupport.createSkill(root.resolve(SkillDevGraphSupport.SKILL), SkillDevGraphSupport.SKILL, "Initial skill body.");
            SkillDevGraphSupport.createSkill(root.resolve(SkillDevGraphSupport.CONFLICT), SkillDevGraphSupport.CONFLICT, "Conflict line: initial.");
            SkillDevGraphSupport.createPlugin(root.resolve(SkillDevGraphSupport.PLUGIN));
            SkillDevGraphSupport.createDoc(root.resolve(SkillDevGraphSupport.DOC));
            SkillDevGraphSupport.createHarness(root.resolve(SkillDevGraphSupport.HARNESS));

            Map<String, String> env = SkillDevGraphSupport.env(
                    home, claudeHome, codexHome, geminiHome,
                    "http://127.0.0.1:" + gatewayPort, registryUrl);
            List<ProcessRecord> procs = new ArrayList<>();
            for (String name : List.of(SkillDevGraphSupport.SKILL, SkillDevGraphSupport.PLUGIN,
                    SkillDevGraphSupport.DOC, SkillDevGraphSupport.HARNESS, SkillDevGraphSupport.CONFLICT)) {
                ProcessRecord p = SkillDevGraphSupport.run(ctx, "install-" + name, env, root,
                        SkillDevGraphSupport.skillManager().toString(), "install",
                        "file:" + root.resolve(name), "--yes");
                procs.add(p);
            }

            boolean skillOk = Files.isRegularFile(h.resolve("skills/" + SkillDevGraphSupport.SKILL + "/SKILL.md"))
                    && SkillDevGraphSupport.kindIs(h, SkillDevGraphSupport.SKILL, "SKILL");
            boolean pluginOk = Files.isRegularFile(h.resolve("plugins/" + SkillDevGraphSupport.PLUGIN + "/.claude-plugin/plugin.json"))
                    && SkillDevGraphSupport.kindIs(h, SkillDevGraphSupport.PLUGIN, "PLUGIN");
            boolean docOk = Files.isRegularFile(h.resolve("docs/" + SkillDevGraphSupport.DOC + "/skill-manager.toml"))
                    && SkillDevGraphSupport.kindIs(h, SkillDevGraphSupport.DOC, "DOC");
            boolean harnessOk = Files.isRegularFile(h.resolve("harnesses/" + SkillDevGraphSupport.HARNESS + "/harness.toml"))
                    && SkillDevGraphSupport.kindIs(h, SkillDevGraphSupport.HARNESS, "HARNESS");
            boolean conflictOk = Files.isRegularFile(h.resolve("skills/" + SkillDevGraphSupport.CONFLICT + "/SKILL.md"))
                    && SkillDevGraphSupport.kindIs(h, SkillDevGraphSupport.CONFLICT, "SKILL");
            boolean procsOk = procs.stream().allMatch(p -> p.exitCode() == 0);
            NodeResult result = procsOk && skillOk && pluginOk && docOk && harnessOk && conflictOk
                    ? NodeResult.pass("skill-dev.units.installed")
                    : NodeResult.fail("skill-dev.units.installed", "procs=" + procsOk + " skill=" + skillOk
                    + " plugin=" + pluginOk + " doc=" + docOk + " harness=" + harnessOk + " conflict=" + conflictOk);
            for (ProcessRecord p : procs) result.process(p);
            return result
                    .assertion("all_installs_exit_zero", procsOk)
                    .assertion("skill_installed", skillOk)
                    .assertion("plugin_installed", pluginOk)
                    .assertion("doc_repo_installed", docOk)
                    .assertion("harness_installed", harnessOk)
                    .assertion("conflict_skill_installed", conflictOk)
                    .publish("projectDir", project.toString());
        });
    }
}
