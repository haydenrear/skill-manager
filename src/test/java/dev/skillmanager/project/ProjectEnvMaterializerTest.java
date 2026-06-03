package dev.skillmanager.project;

import dev.skillmanager._lib.fixtures.DepSpec;
import dev.skillmanager._lib.fixtures.UnitFixtures;
import dev.skillmanager._lib.harness.TestHarness;
import dev.skillmanager._lib.test.Tests;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.shared.util.Fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.skillmanager._lib.test.Tests.assertEquals;
import static dev.skillmanager._lib.test.Tests.assertTrue;

public final class ProjectEnvMaterializerTest {

    public static int run() throws Exception {
        return Tests.suite("ProjectEnvMaterializerTest")
                .test("renders pyproject with project-relative vendor paths and env docs", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-env-render-");
                        UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "env-skill", DepSpec.empty());
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "env-project"

                                [skills.env]
                                source = "%s"

                                [envs.dev]
                                python = "3.12"
                                dependencies = ["pytest==8.4.0"]
                                skill_packages = ["env-skill"]
                                tools = ["pytest"]
                                """.formatted(repoRoot.resolve("units/env-skill")));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));

                        ProjectEnvMaterializer.Result result = new ProjectEnvMaterializer(h.store())
                                .materialize(project, "dev", ProjectEnvMaterializer.Options.renderOnly());

                        String pyproject = Files.readString(result.pyprojectFile());
                        assertTrue(pyproject.contains("requires-python = \">=3.12\""), "python version rendered");
                        assertTrue(pyproject.contains("\"pytest==8.4.0\""), "dependency rendered");
                        assertTrue(pyproject.contains("env-skill = { path = \"../../vendor/env-skill\", editable = true }"),
                                "vendor source path is project-relative");
                        assertTrue(Files.isRegularFile(repoRoot.resolve(".skill-manager/vendor/env-skill/SKILL.md")),
                                "skill unit vendored under project .skill-manager");
                        assertTrue(Files.isExecutable(repoRoot.resolve(".skill-manager/bin/pytest")),
                                "tool shim is executable");
                        assertTrue(Files.readString(result.docsFile()).contains("skill-project.toml"),
                                "env docs state that manifest remains source of truth");
                        assertEquals(1, result.lock().envs().size(), "env lock recorded");
                    }
                })
                .test("sync invokes uv in the generated env root", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-env-sync-");
                        UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "sync-skill", DepSpec.empty());
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "sync-project"

                                [skills.sync]
                                source = "%s"

                                [envs.dev]
                                dependencies = []
                                """.formatted(repoRoot.resolve("units/sync-skill")));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        Path capture = repoRoot.resolve("uv-sync.txt");
                        Path fakeUv = fakeUv(repoRoot, capture);

                        ProjectEnvMaterializer.Result result = new ProjectEnvMaterializer(h.store())
                                .materialize(project, "dev", new ProjectEnvMaterializer.Options(true, fakeUv.toString()));

                        String captured = Files.readString(capture);
                        String pwd = captured.lines()
                                .filter(line -> line.startsWith("PWD="))
                                .findFirst()
                                .orElseThrow()
                                .substring("PWD=".length());
                        assertEquals(result.envRoot().toRealPath(), Path.of(pwd).toRealPath(),
                                "uv sync cwd is env root");
                        assertTrue(captured.contains("ARGS=sync"), "uv sync command invoked");
                    }
                })
                .test("run executes through uv project for materialized env", () -> {
                    try (TestHarness h = TestHarness.create()) {
                        Path repoRoot = Files.createTempDirectory("project-env-run-");
                        UnitFixtures.scaffoldSkill(
                                repoRoot.resolve("units"), "run-skill", DepSpec.empty());
                        SkillProject project = project(repoRoot, """
                                [project]
                                name = "run-project"

                                [skills.run]
                                source = "%s"

                                [envs.dev]
                                dependencies = []
                                """.formatted(repoRoot.resolve("units/run-skill")));
                        resolver(h).resolve(project, new ProjectDependencyResolver.Options(true, false));
                        ProjectEnvMaterializer.Result result = new ProjectEnvMaterializer(h.store())
                                .materialize(project, "dev", ProjectEnvMaterializer.Options.renderOnly());
                        Path capture = repoRoot.resolve("uv-run.txt");
                        Path fakeUv = fakeUv(repoRoot, capture);

                        int rc = new ProjectEnvMaterializer(h.store())
                                .runEnv(project, "dev", List.of("python", "-V"), fakeUv.toString());

                        assertEquals(0, rc, "fake uv command exits cleanly");
                        String captured = Files.readString(capture);
                        assertTrue(captured.contains("ARGS=run --project " + result.envRoot() + " python -V"),
                                "uv run receives generated project root and command");
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

    private static Path fakeUv(Path root, Path capture) throws Exception {
        Path script = root.resolve("fake-uv.sh");
        Files.writeString(script, """
                #!/usr/bin/env sh
                {
                  printf 'PWD=%%s\\n' "$(pwd)"
                  printf 'ARGS='
                  printf '%%s' "$1"
                  shift || true
                  for arg in "$@"; do
                    printf ' %%s' "$arg"
                  done
                  printf '\\n'
                } > "%s"
                exit 0
                """.formatted(capture));
        Fs.makeExecutable(script);
        return script;
    }
}
