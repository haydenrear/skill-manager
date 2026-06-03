package dev.skillmanager.model;

import dev.skillmanager._lib.test.Tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.skillmanager._lib.test.Tests.assertContains;
import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertSize;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class SkillProjectParserTest {

    public static int run() throws IOException {
        Path tmp = Files.createTempDirectory("skill-project-parser-test-");

        return Tests.suite("SkillProjectParserTest")
                .test("parses skill project manifest sections as portable intent", () -> {
                    Path project = tmp.resolve("demo-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-project.toml"), """
                            [project]
                            name = "demo"
                            version = "0.1.0"
                            description = "demo project"

                            [skills.test_graph]
                            source = "skill:test-graph@1.0.0"

                            [plugins.reviewer]
                            source = "plugin:reviewer"
                            install = false

                            [harnesses.codex]
                            source = "harness:codex-harness"
                            revision = "main"

                            [docs.prompts]
                            source = "doc:agent-prompts/review"

                            [[cli_dependencies]]
                            spec = "pip:ruff==0.8.0"

                            [[mcp_dependencies]]
                            name = "shell-server"
                            [mcp_dependencies.load]
                            type = "shell"
                            command = ["python", "-m", "server"]

                            [envs.test]
                            python = "3.12"
                            dependencies = ["pytest"]
                            skill_packages = ["test_graph"]
                            tools = ["ruff"]

                            [[libs]]
                            name = "support-agent"
                            source = "github:haydenrear/support-agent-rears"
                            ref = "main"
                            sha = "abc123"
                            """);

                    SkillProject parsed = SkillProjectParser.load(project);
                    assertEquals("demo", parsed.name(), "project name");
                    assertEquals("0.1.0", parsed.version(), "project version");
                    assertEquals("demo project", parsed.description(), "project description");
                    assertEquals(project.toAbsolutePath().normalize(), parsed.projectRoot(), "project root");
                    assertSize(1, parsed.skills(), "skills");
                    assertEquals("test_graph", parsed.skills().get(0).alias(), "skill alias");
                    assertEquals(UnitKind.SKILL, parsed.skills().get(0).kind(), "skill kind");
                    assertEquals("test-graph", parsed.skills().get(0).reference().name(), "skill ref name");
                    assertSize(1, parsed.plugins(), "plugins");
                    assertEquals(false, parsed.plugins().get(0).install(), "plugin install flag");
                    assertSize(1, parsed.harnesses(), "harnesses");
                    assertEquals("main", parsed.harnesses().get(0).revision(), "harness revision");
                    assertSize(1, parsed.docs(), "docs");
                    assertEquals("review", ((Coord.SubElement) parsed.docs().get(0).reference().coord()).elementName(),
                            "doc source selector");
                    assertSize(1, parsed.cliDependencies(), "cli deps");
                    assertEquals("ruff", parsed.cliDependencies().get(0).name(), "cli derived name");
                    assertSize(1, parsed.mcpDependencies(), "mcp deps");
                    assertEquals("shell-server", parsed.mcpDependencies().get(0).name(), "mcp name");
                    assertSize(1, parsed.envs(), "envs");
                    assertEquals("test", parsed.envs().get(0).name(), "env name");
                    assertSize(1, parsed.libs(), "libs");
                    assertEquals("support-agent", parsed.libs().get(0).name(), "lib name");
                    assertTrue(SkillProjectParser.looksLikeProject(project), "looksLikeProject");
                })
                .test("rejects malformed project references", () -> {
                    Path project = tmp.resolve("bad-ref-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-project.toml"), """
                            [project]
                            name = "bad-ref"

                            [skills.bad]
                            source = ""
                            """);
                    try {
                        SkillProjectParser.load(project);
                        throw new AssertionError("expected IOException");
                    } catch (IOException e) {
                        assertContains(e.getMessage(), "Missing source", "clear source error");
                    }
                })
                .test("rejects kind-conflicting references", () -> {
                    Path project = tmp.resolve("wrong-kind-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-project.toml"), """
                            [project]
                            name = "wrong-kind"

                            [plugins.bad]
                            source = "skill:not-a-plugin"
                            """);
                    try {
                        SkillProjectParser.load(project);
                        throw new AssertionError("expected IOException");
                    } catch (IllegalArgumentException e) {
                        assertContains(e.getMessage(), "does not match plugin", "kind conflict");
                    }
                })
                .test("accepts legacy skill-manager-project.toml filename", () -> {
                    Path project = tmp.resolve("legacy-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-manager-project.toml"), """
                            [project]
                            name = "legacy"
                            """);
                    SkillProject parsed = SkillProjectParser.load(project);
                    assertEquals("legacy", parsed.name(), "legacy name");
                })
                .test("parses root array dependency declarations", () -> {
                    Path project = tmp.resolve("array-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-project.toml"), """
                            [project]
                            name = "array-project"

                            skills = ["skill:array-skill"]
                            docs = ["doc:array-doc/source"]
                            """);
                    SkillProject parsed = SkillProjectParser.load(project);
                    assertEquals(1, parsed.skills().size(), "one array skill");
                    assertEquals("array-skill", parsed.skills().get(0).alias(), "array skill alias");
                    assertEquals(1, parsed.docs().size(), "one array doc");
                })
                .test("profiles select named project harness declarations", () -> {
                    Path project = tmp.resolve("profile-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-project.toml"), """
                            [project]
                            name = "profile-project"

                            [skills.common]
                            source = "skill:common-skill"

                            [skills.dev]
                            source = "skill:dev-skill"

                            [skills.review]
                            source = "skill:review-skill"

                            [envs.dev]
                            python = "3.12"

                            [envs.review]
                            python = "3.11"

                            [profiles.dev]
                            skills = ["common", "dev"]
                            envs = ["dev"]

                            [profiles.review]
                            skills = ["common", "review"]
                            envs = ["review"]
                            """);

                    SkillProject parsed = SkillProjectParser.load(project);
                    assertSize(2, parsed.profiles(), "profiles parsed");
                    SkillProject dev = parsed.withProfile("dev");
                    assertEquals("dev", dev.activeProfile(), "active profile");
                    assertEquals("profile-project--dev", dev.registryName(), "profile registry name");
                    assertEquals("project:profile-project:profile:dev", dev.childHomeId(), "profile child id");
                    assertSize(2, dev.skills(), "dev selected skills");
                    assertEquals("common", dev.skills().get(0).alias(), "common selected first");
                    assertEquals("dev", dev.skills().get(1).alias(), "dev selected second");
                    assertSize(1, dev.envs(), "dev selected env");
                    assertEquals("dev", dev.envs().get(0).name(), "dev env");

                    SkillProject review = parsed.withProfile("review");
                    assertSize(2, review.skills(), "review selected skills");
                    assertEquals("review", review.skills().get(1).alias(), "review selected");
                    assertEquals("review", review.envs().get(0).name(), "review env");
                })
                .test("profile default inheritance keeps legacy manifest declarations", () -> {
                    Path project = tmp.resolve("profile-default-project");
                    Files.createDirectories(project);
                    Files.writeString(project.resolve("skill-project.toml"), """
                            [project]
                            name = "profile-default-project"

                            [skills.common]
                            source = "skill:common-skill"

                            [profiles.dev]
                            extends = ["default"]
                            """);

                    SkillProject dev = SkillProjectParser.load(project).withProfile("dev");
                    assertSize(1, dev.skills(), "default skill inherited");
                    assertEquals("common", dev.skills().get(0).alias(), "common inherited");
                })
                .runAll();
    }
}
