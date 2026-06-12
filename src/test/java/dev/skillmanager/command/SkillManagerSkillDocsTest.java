package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;

public final class SkillManagerSkillDocsTest {

    public static int run() throws Exception {
        return Tests.suite("SkillManagerSkillDocsTest")
                .test("skill docs cover projects and project child homes", () -> {
                    Path root = Path.of("skill-manager-skill");
                    String skill = Files.readString(root.resolve("SKILL.md"));
                    String workflows = Files.readString(root.resolve("references/workflows.md"));
                    String projects = Files.readString(root.resolve("references/projects.md"));
                    String cli = Files.readString(root.resolve("references/cli.md"));
                    String toml = Files.readString(root.resolve("skill-manager.toml"));
                    String publisher = Files.readString(Path.of("skill-publisher-skill/SKILL.md"));
                    String skillScripts = Files.readString(
                            Path.of("skill-publisher-skill/references/skill-scripts.md"));
                    String pluginDocs = Files.readString(
                            Path.of("skill-publisher-skill/references/plugins.md"));
                    String skillDev = Files.readString(Path.of("skill-dev-skill/SKILL.md"));

                    assertContains(skill, "skill projects", "front matter and body name skill projects");
                    assertContains(skill, "project child homes", "front matter names project child homes");
                    assertContains(skill, "references/projects.md", "project reference linked");
                    assertContains(skill, "skill-manager project --help", "project help routed to CLI");
                    assertContains(skill, "skill-manager env --help", "env help routed to CLI");
                    assertContains(skill, "install --force-scripts", "force install documented");
                    assertContains(skill, "sync --force-scripts", "force sync documented");
                    assertContains(skill, "cli-lock.toml", "CLI lock cleanup documented");

                    assertContains(workflows, "Resolve a skill project", "workflow section present");
                    assertContains(workflows, "SKILL_MANAGER_HOME=<project>/.skill-manager",
                            "workflow shows child-home launch env");

                    assertContains(projects, "skill-project.toml", "project manifest described");
                    assertContains(projects, "<project>/.skill-manager", "project child home described");
                    assertContains(projects, "CODEX_HOME=<project>/.codex", "Codex home described");
                    assertContains(projects, "CLAUDE_HOME=<project>/.claude", "Claude home described");
                    assertContains(projects, "GEMINI_HOME=<project>/.gemini", "Gemini home described");
                    assertContains(projects, "skill-manager env sync", "project env workflow described");

                    assertContains(cli, "passive project context", "env helper project context documented");
                    assertContains(cli, "install --force-scripts", "CLI reference documents force install");
                    assertContains(cli, "sync --force-scripts", "CLI reference documents force sync");
                    assertContains(cli, "only when they are orphaned", "CLI reference documents orphan cleanup");
                    assertContains(toml, "skill projects", "published description includes projects");
                    assertContains(toml, "project child homes", "published description includes child homes");

                    assertContains(publisher, "skill-script:", "publisher points to skill-script validation");
                    assertContains(skillScripts, "install --force-scripts", "skill-script docs document force install");
                    assertContains(skillScripts, "sync --force-scripts", "skill-script docs document force sync");
                    assertContains(skillScripts, "surviving installed",
                            "skill-script docs document shared ownership cleanup");
                    assertContains(pluginDocs, "plugin-level `skill-script:` CLI dep",
                            "plugin docs route plugin private script setup correctly");

                    assertContains(skillDev, "--force-scripts", "skill-dev docs document manual force sync");
                    assertContains(skillDev, "skill-imports:", "skill-dev imports runtime CLI reference");
                })
                .runAll();
    }
}
