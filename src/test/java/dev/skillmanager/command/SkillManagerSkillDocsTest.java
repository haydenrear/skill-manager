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

                    assertContains(skill, "skill projects", "front matter and body name skill projects");
                    assertContains(skill, "project child homes", "front matter names project child homes");
                    assertContains(skill, "references/projects.md", "project reference linked");
                    assertContains(skill, "skill-manager project --help", "project help routed to CLI");
                    assertContains(skill, "skill-manager env --help", "env help routed to CLI");

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
                    assertContains(toml, "skill projects", "published description includes projects");
                    assertContains(toml, "project child homes", "published description includes child homes");
                })
                .runAll();
    }
}
