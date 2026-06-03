package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertFalse;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ProjectDependencyResolverTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectDependencyResolverTest")
                .test("resolves transitive skill dependencies into the home store and project lock", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-transitive-");
                        Path child = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-child", DepSpec.empty()).sourcePath();
                        Path parent = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "project-parent",
                                DepSpec.of().ref(child.toString()).build()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "transitive-project"

                                [skills.parent]
                                source = "%s"
                                """.formatted(parent));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsUnit("project-parent"), "parent installed");
                        assertTrue(h.store().containsUnit("project-child"), "transitive child installed");
                        assertTrue(Files.isRegularFile(h.store().projectsDir()
                                .resolve("transitive-project")
                                .resolve(SkillProjectLock.FILENAME)), "project lock written");
                        Set<String> locked = result.lock().resolvedUnits().stream()
                                .map(SkillProjectLock.ResolvedUnit::name)
                                .collect(Collectors.toSet());
                        assertTrue(locked.contains("project-parent"), "lock records direct skill");
                        assertTrue(locked.contains("project-child"), "lock records transitive skill");
                    }
                })
                .test("binds selected doc repo source into project root", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-doc-");
                        Path doc = scaffoldDocRepo(repoRoot.resolve("units"), "project-prompts");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "doc-project"

                                [docs.review]
                                source = "%s"
                                """.formatted(doc));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(h.store().containsDocRepo("project-prompts"), "doc repo installed");
                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "managed doc copy materialized");
                        assertTrue(Files.readString(repoRoot.resolve("CLAUDE.md")).contains("docs/agents/review.md"),
                                "CLAUDE.md import directive materialized");
                        assertEquals(1, result.bindingIds().size(), "one selected doc binding");
                        var ledger = new BindingStore(h.store()).read("project-prompts");
                        assertTrue(ledger.findById("project:doc-project:doc:project-prompts:review").isPresent(),
                                "stable project doc binding id recorded");
                    }
                })
                .test("materializes harness bindings into project agent homes", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-harness-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "harness-skill", DepSpec.empty()).sourcePath();
                        Path doc = scaffoldDocRepo(repoRoot.resolve("units"), "harness-prompts");
                        Path harness = scaffoldHarness(repoRoot.resolve("units"), "project-harness", skill, doc);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "harness-project"

                                [harnesses.default]
                                source = "%s"
                                """.formatted(harness));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/harness-skill")),
                                "Codex project skill projection exists");
                        assertTrue(Files.exists(repoRoot.resolve(".claude/skills/harness-skill")),
                                "Claude project skill projection exists");
                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "harness doc copy exists");
                        assertFalse(result.lock().bindings().isEmpty(), "harness bindings locked");
                    }
                })
                .test("plain remove is blocked while a project lock claims the unit", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-remove-");
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "claimed-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "claimed-project"

                                [skills.claimed]
                                source = "%s"
                                """.formatted(skill));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        boolean blocked = false;
                        try {
                            RemoveUseCase.buildProgram(h.store(), null, "claimed-skill", java.util.List.of(), false);
                        } catch (java.io.IOException e) {
                            blocked = e.getMessage().contains("claimed-project");
                        }
                        assertTrue(blocked, "project lock blocks plain remove");
                    }
                })
                .test("preinstalled unit with wrong kind is rejected before skip", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        h.seedUnit("kind-conflict", UnitKind.SKILL);
                        h.scaffoldUnitDir("kind-conflict", UnitKind.SKILL);
                        Path repoRoot = Files.createTempDirectory("project-resolve-kind-conflict-");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "kind-conflict-project"

                                [plugins.conflict]
                                source = "plugin:kind-conflict"
                                """);

                        boolean rejected = false;
                        try {
                            resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        } catch (java.io.IOException e) {
                            rejected = e.getMessage().contains("expected PLUGIN")
                                    && e.getMessage().contains("SKILL");
                        }
                        assertTrue(rejected, "existing wrong-kind unit is rejected");
                    }
                })
                .test("direct git dependency is recorded in the project lock", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-direct-git-");
                        Path gitSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("git-skill"), "git-locked-skill", DepSpec.empty()).sourcePath();
                        gitInitCommit(gitSkill);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "git-project"

                                [skills.git]
                                source = "git+file://%s"
                                """.formatted(gitSkill));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        Set<String> locked = result.lock().resolvedUnits().stream()
                                .map(SkillProjectLock.ResolvedUnit::name)
                                .collect(Collectors.toSet());
                        assertTrue(locked.contains("git-locked-skill"), "direct git unit is locked");
                        assertTrue(new SkillProjectLockStore(h.store()).projectsClaiming("git-locked-skill")
                                .contains("git-project"), "direct git unit is project-claimed");
                    }
                })
                .runAll();
    }

    private static ProjectDependencyResolver resolver(TestHarness h) {
        return new ProjectDependencyResolver(h.store(), null);
    }

    private static SkillProject project(Path root, String manifest) throws Exception {
        Files.writeString(root.resolve("skill-project.toml"), manifest);
        return SkillProjectParser.load(root);
    }

    private static Path scaffoldDocRepo(Path root, String name) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill-manager.toml"), """
                [doc-repo]
                name = "%s"
                version = "0.1.0"

                [[sources]]
                id = "review"
                file = "review.md"
                agents = ["claude", "codex"]
                """.formatted(name));
        Files.writeString(dir.resolve("review.md"), "review guidance\n");
        return dir;
    }

    private static Path scaffoldHarness(Path root, String name, Path skill, Path doc) throws Exception {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                units = ["%s"]
                docs = ["%s"]
                """.formatted(name, skill, doc));
        return dir;
    }

    private static void gitInitCommit(Path repo) throws Exception {
        git(repo, "init");
        git(repo, "checkout", "-b", "main");
        git(repo, "add", ".");
        git(repo, "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-m", "initial");
    }

    private static void git(Path repo, String... args) throws Exception {
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repo.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new java.io.IOException("git " + String.join(" ", args)
                    + " failed with " + rc + ": " + output);
        }
    }
}
