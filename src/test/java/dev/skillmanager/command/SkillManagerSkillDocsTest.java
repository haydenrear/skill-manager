package dev.skillmanager.command;

import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.cli.CliMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertTrue;

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

                    // The three authoring-doc assertions that used to sit here
                    // read skill-publisher-skill/{skills/unit-authoring/SKILL.md,
                    // references/skill-scripts.md, references/plugins.md}. SI-18
                    // deleted that tree — it was a vendored snapshot of the skt
                    // plugin — and those pages live in the tla-spec-dev plugin
                    // now, in another repository this one does not vendor. There
                    // is nothing here to read, so the assertions are gone rather
                    // than pointed at a path that would always be absent.
                    //
                    // They are not simply dropped: what this repository still
                    // owns is WHICH workflows delegate their docs outward, and
                    // the next test pins that set exactly.
                })
                .test("bundled skill docs cover modeled CLI workflows", () -> {
                    Map<String, String> docsBySurface = new LinkedHashMap<>();
                    for (String surface : CliMetadata.inTreeDocSurfaces()) {
                        docsBySurface.put(surface, markdownUnder(Path.of(surface)));
                    }

                    for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                        String helpCommand = helpCommand(workflow.commandPath());
                        for (String surface : workflow.relatedSkillDocs()) {
                            String docs = docsBySurface.get(surface);
                            // An external surface's bytes are in another
                            // repository. Skipping it silently would be a green
                            // result standing for nothing, so the skip is not
                            // silent: the next test names every workflow that
                            // takes this branch and fails if the list changes.
                            if (docs == null) continue;
                            assertContains(docs, workflow.id(),
                                    surface + " documents workflow id " + workflow.id());
                            assertContains(docs, helpCommand,
                                    surface + " routes " + workflow.id() + " to command help");
                        }
                    }
                })
                .test("exactly the authoring workflows delegate docs outside this repo", () -> {
                    // SI-18. `unit-authoring` moved into the tla-spec-dev plugin
                    // and out of this repository, taking five workflows' docs
                    // with it. No test here can read those pages; this one pins
                    // WHICH workflows are allowed to point at them, so a sixth
                    // cannot join them by editing one string in CliMetadata and
                    // quietly leaving coverage.
                    java.util.Set<String> expected = new java.util.TreeSet<>(java.util.List.of(
                            "author-dependencies",
                            "author-unit",
                            "install-local-unit",
                            "publish-unit",
                            "skill-scripts"));
                    java.util.Set<String> actual = CliMetadata.workflowsWithExternalDocs();
                    assertTrue(expected.equals(actual),
                            "workflows delegating docs outside this repo: expected " + expected
                                    + " but was " + actual);

                    // Every surface is either in-tree or the one external unit.
                    // A typo'd surface name would otherwise land in the external
                    // bucket and look deliberate.
                    for (CliMetadata.WorkflowMetadata workflow : CliMetadata.workflows()) {
                        for (String surface : workflow.relatedSkillDocs()) {
                            assertTrue(CliMetadata.inTreeDocSurfaces().contains(surface)
                                            || CliMetadata.UNIT_AUTHORING_DOCS.equals(surface),
                                    "known doc surface for " + workflow.id() + ": " + surface);
                        }
                    }
                })
                .runAll();
    }

    private static String markdownUnder(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String helpCommand(String commandPath) {
        if ("skill-manager".equals(commandPath)) {
            return "skill-manager --help";
        }
        return "skill-manager " + commandPath + " --help";
    }
}
