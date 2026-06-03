package dev.skillmanager.commands;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.project.ProjectEnvMaterializer;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "env",
        description = "Materialize and run project-local uv environments.",
        subcommands = {EnvCommand.Sync.class, EnvCommand.Run.class})
public final class EnvCommand implements Runnable {
    @Override public void run() { new picocli.CommandLine(this).usage(System.out); }

    @Command(name = "sync",
            description = "Render a declared project env and optionally run uv sync.")
    public static final class Sync implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1",
                description = "Env name. Defaults to default, or the only declared env.")
        String envName;

        @Option(names = "--project-dir",
                description = "Project root. Defaults to the current working directory.")
        String projectDir;

        @Option(names = "--manifest",
                description = "Explicit project manifest path. Defaults to skill-project.toml, then skill-manager-project.toml.")
        String manifest;

        @Option(names = "--profile",
                description = "Named project profile whose env realization should be synced.")
        String profile;

        @Option(names = "--skip-uv",
                description = "Only render files and lock state; do not invoke uv sync.")
        boolean skipUv;

        @Option(names = "--uv",
                description = "uv executable to use. Defaults to uv on PATH.")
        String uv;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            SkillProject project = loadProject(projectDir, manifest, profile);
            ProjectEnvMaterializer.Result result = new ProjectEnvMaterializer(store)
                    .materialize(project, envName, new ProjectEnvMaterializer.Options(!skipUv, uv));
            if (json) {
                System.out.println("""
                        {"project":"%s","profile":"%s","env":"%s","envRoot":"%s","pyproject":"%s","docs":"%s","uvExitCode":%d}"""
                        .formatted(
                                esc(project.name()),
                                esc(project.activeProfile() == null ? "" : project.activeProfile()),
                                esc(result.env().name()),
                                esc(result.envRoot().toString()),
                                esc(result.pyprojectFile().toString()),
                                esc(result.docsFile().toString()),
                                result.uvExitCode()));
            } else {
                Log.ok("synced project env %s/%s", project.name(), result.env().name());
                Log.info("  env root:  %s", result.envRoot());
                Log.info("  pyproject: %s", result.pyprojectFile());
                Log.info("  docs:      %s", result.docsFile());
            }
            return 0;
        }
    }

    @Command(name = "run",
            description = "Run a command through a materialized project env.")
    public static final class Run implements Callable<Integer> {

        @Parameters(index = "0", description = "Env name.")
        String envName;

        @Parameters(index = "1..*", arity = "1..*",
                description = "Command and args to run. Use -- before the command when needed.")
        List<String> command = new ArrayList<>();

        @Option(names = "--project-dir",
                description = "Project root. Defaults to the current working directory.")
        String projectDir;

        @Option(names = "--manifest",
                description = "Explicit project manifest path. Defaults to skill-project.toml, then skill-manager-project.toml.")
        String manifest;

        @Option(names = "--profile",
                description = "Named project profile whose env realization should run the command.")
        String profile;

        @Option(names = "--uv",
                description = "uv executable to use. Defaults to uv on PATH.")
        String uv;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            SkillProject project = loadProject(projectDir, manifest, profile);
            return new ProjectEnvMaterializer(store).runEnv(project, envName, command, uv);
        }
    }

    private static SkillProject loadProject(String projectDir, String manifest, String profile) throws Exception {
        Path root = projectDir == null || projectDir.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(projectDir);
        root = root.toAbsolutePath().normalize();
        SkillProject project = manifest == null || manifest.isBlank()
                ? SkillProjectParser.load(root)
                : SkillProjectParser.loadManifest(ProjectCommand.resolveManifestPath(root, manifest), root);
        return project.withProfile(profile);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
