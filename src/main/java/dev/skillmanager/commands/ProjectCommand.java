package dev.skillmanager.commands;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.project.SkillProjectRegistration;
import dev.skillmanager.project.SkillProjectRegistry;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "project",
        description = "Register and inspect skill project manifests.",
        subcommands = {
                ProjectCommand.RegisterCmd.class,
                ProjectCommand.ShowCmd.class,
                ProjectCommand.ListCmd.class
        })
public final class ProjectCommand {

    @Command(name = "register",
            description = "Register skill-project.toml intent without installing or materializing dependencies.")
    public static final class RegisterCmd implements Callable<Integer> {

        @Option(names = "--project-dir",
                description = "Project root. Defaults to the current working directory.")
        String projectDir;

        @Option(names = "--manifest",
                description = "Explicit project manifest path. Defaults to skill-project.toml, then skill-manager-project.toml.")
        String manifest;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();

            Path root = projectDir == null || projectDir.isBlank()
                    ? Path.of(System.getProperty("user.dir"))
                    : Path.of(projectDir);
            root = root.toAbsolutePath().normalize();
            SkillProject project = manifest == null || manifest.isBlank()
                    ? SkillProjectParser.load(root)
                    : SkillProjectParser.loadManifest(resolveManifestPath(root, manifest), root);
            SkillProjectRegistration registration = new SkillProjectRegistry(store).register(project);
            if (json) {
                System.out.println("""
                        {"name":"%s","projectRoot":"%s","manifestPath":"%s","registrationDir":"%s"}"""
                        .formatted(
                                esc(registration.name()),
                                esc(registration.projectRoot().toString()),
                                esc(registration.manifestPath().toString()),
                                esc(registration.registrationDir().toString())));
            } else {
                Log.ok("registered project %s", registration.name());
                Log.info("  project root: %s", registration.projectRoot());
                Log.info("  manifest:     %s", registration.manifestPath());
                Log.info("  registry:     %s", registration.registrationDir());
            }
            return 0;
        }
    }

    @Command(name = "show", description = "Show a registered skill project.")
    public static final class ShowCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "Project name.")
        String name;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            SkillProjectRegistry registry = new SkillProjectRegistry(store);
            SkillProjectRegistration registration = registry.read(name).orElse(null);
            if (registration == null) {
                Log.error("project not registered: %s", name);
                return 1;
            }
            SkillProject project = registry.loadSnapshot(name).orElse(null);
            System.out.printf("PROJECT  %s%n", registration.name());
            System.out.printf("root:     %s%n", registration.projectRoot());
            System.out.printf("manifest: %s%n", registration.manifestPath());
            System.out.printf("registry: %s%n", registration.registrationDir());
            if (project != null) {
                System.out.printf("skills:   %d%n", project.skills().size());
                System.out.printf("plugins:  %d%n", project.plugins().size());
                System.out.printf("docs:     %d%n", project.docs().size());
                System.out.printf("harnesses:%d%n", project.harnesses().size());
                System.out.printf("envs:     %d%n", project.envs().size());
                System.out.printf("libs:     %d%n", project.libs().size());
                System.out.printf("cli:      %d%n", project.cliDependencies().size());
                System.out.printf("mcp:      %d%n", project.mcpDependencies().size());
            }
            return 0;
        }
    }

    @Command(name = "list", description = "List registered skill projects.")
    public static final class ListCmd implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            List<SkillProjectRegistration> projects = new SkillProjectRegistry(store).list();
            if (projects.isEmpty()) {
                Log.info("no registered projects");
                return 0;
            }
            for (SkillProjectRegistration p : projects) {
                System.out.printf("%s\t%s%n", p.name(), p.projectRoot());
            }
            return 0;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static Path resolveManifestPath(Path root, String manifest) {
        Path path = Path.of(manifest);
        return path.isAbsolute()
                ? path.normalize()
                : root.resolve(path).normalize();
    }
}
