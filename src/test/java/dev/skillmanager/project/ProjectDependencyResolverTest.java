package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.app.RemoveUseCase;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeRegistry;
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
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/docs/project-prompts/skill-manager.toml")),
                                "doc repo projected into project child store");
                        assertTrue(new ChildHomeRegistry(h.store()).childHomesClaiming("project-prompts")
                                .contains("project:doc-project"), "parent registry claims project child doc");
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
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/skills/harness-skill/SKILL.md")),
                                "skill projected into project child store");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/harnesses/project-harness/harness.toml")),
                                "harness projected into project child store");
                        assertTrue(Files.isDirectory(repoRoot.resolve(".gemini")),
                                "Gemini child agent home scaffolded");
                        assertEquals(repoRoot.resolve(".skill-manager").toAbsolutePath().normalize(),
                                result.childHome().layout().childSkillManagerHome(),
                                "child home is project-local .skill-manager");
                        assertTrue(new ChildHomeRegistry(h.store()).exists("project:harness-project"),
                                "parent registry records project child home");
                        assertTrue(new ChildHomeRegistry(h.store()).childHomesClaiming("harness-skill")
                                .contains("project:harness-project"), "parent registry claims child skill");
                        assertTrue(pointsTo(
                                        repoRoot.resolve(".codex/skills/harness-skill"),
                                        repoRoot.resolve(".skill-manager/skills/harness-skill")),
                                "harness projection points at child store skill");
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
                .test("re-resolve updates project child store and parent child-home claims", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-child-update-");
                        Path first = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "first-child-skill", DepSpec.empty()).sourcePath();
                        Path second = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "second-child-skill", DepSpec.empty()).sourcePath();
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "update-project"

                                [skills.first]
                                source = "%s"
                                """.formatted(first));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        project = project(repoRoot, """
                                [project]
                                name = "update-project"

                                [skills.second]
                                source = "%s"
                                """.formatted(second));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        assertFalse(Files.exists(repoRoot.resolve(".skill-manager/skills/first-child-skill")),
                                "removed project dependency pruned from child store");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/skills/second-child-skill/SKILL.md")),
                                "new project dependency present in child store");
                        ChildHomeRegistry registry = new ChildHomeRegistry(h.store());
                        assertFalse(registry.childHomesClaiming("first-child-skill").contains("project:update-project"),
                                "old unit is no longer claimed by project child home");
                        assertTrue(registry.childHomesClaiming("second-child-skill").contains("project:update-project"),
                                "new unit is claimed by project child home");
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
                                source = "git+file://%s#main"
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
                .test("direct git doc and harness dependencies materialize project bindings", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-direct-git-bindings-");
                        Path gitDoc = scaffoldDocRepo(repoRoot.resolve("git-doc"), "git-prompts");
                        gitInitCommit(gitDoc);
                        Path skill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "git-harness-skill", DepSpec.empty()).sourcePath();
                        Path gitHarness = scaffoldHarness(
                                repoRoot.resolve("git-harness"), "git-project-harness", skill, null);
                        gitInitCommit(gitHarness);
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "git-binding-project"

                                [docs.review]
                                source = "git+file://%s"

                                [harnesses.default]
                                source = "git+file://%s"
                                """.formatted(gitDoc, gitHarness));

                        ProjectDependencyResolver.Result result = resolver(h).resolve(
                                project,
                                new ProjectDependencyResolver.Options(true, false));

                        assertTrue(Files.isRegularFile(repoRoot.resolve("docs/agents/review.md")),
                                "direct git doc binding materialized");
                        assertTrue(Files.exists(repoRoot.resolve(".codex/skills/git-harness-skill")),
                                "direct git harness binding materialized");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/docs/git-prompts/skill-manager.toml")),
                                "direct git doc projected into project child store");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/harnesses/git-project-harness/harness.toml")),
                                "direct git harness projected into project child store");
                        assertTrue(result.bindingIds().stream()
                                        .anyMatch(id -> id.contains("git-prompts")),
                                "direct git doc binding id recorded");
                        assertTrue(result.bindingIds().stream()
                                        .anyMatch(id -> id.contains("git-harness-skill")),
                                "direct git harness binding id recorded");
                    }
                })
                .test("manifest revision overrides direct git default branch", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-resolve-revision-");
                        Path gitSkill = UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("revision-skill"), "revision-skill", DepSpec.empty()).sourcePath();
                        gitInitCommit(gitSkill);
                        git(gitSkill, "checkout", "-b", "release");
                        Files.writeString(gitSkill.resolve("skill-manager.toml"), """
                                [skill]
                                name = "revision-skill"
                                version = "9.9.9"
                                description = "revision fixture"
                                """);
                        git(gitSkill, "add", ".");
                        git(gitSkill, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                                "commit", "-m", "release");
                        git(gitSkill, "checkout", "main");
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "revision-project"

                                [skills.rev]
                                source = "git+file://%s"
                                revision = "release"
                                """.formatted(gitSkill));

                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        assertEquals("9.9.9", h.store().loadUnit("revision-skill").orElseThrow().version(),
                                "project dependency revision selects the declared git ref");
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
        String docs = doc == null ? "" : "\ndocs = [\"" + doc + "\"]\n";
        Files.writeString(dir.resolve("harness.toml"), """
                [harness]
                name = "%s"
                version = "0.1.0"
                units = ["%s"]%s
                """.formatted(name, skill, docs));
        return dir;
    }

    private static boolean pointsTo(Path projection, Path expected) throws Exception {
        if (!Files.exists(projection)) return false;
        if (Files.isSymbolicLink(projection)) {
            Path link = Files.readSymbolicLink(projection);
            Path resolved = link.isAbsolute()
                    ? link.normalize()
                    : projection.getParent().resolve(link).normalize();
            return resolved.equals(expected.toAbsolutePath().normalize());
        }
        return projection.toRealPath().equals(expected.toRealPath());
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
