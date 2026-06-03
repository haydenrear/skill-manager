package dev.skillmanager.commands;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.project.ProjectDependencyResolver;
import dev.skillmanager.project.ProjectLibResolver;
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
                ProjectCommand.ResolveCmd.class,
                ProjectCommand.ShowCmd.class,
                ProjectCommand.ListCmd.class,
                ProjectCommand.ProfilesCmd.class
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

        @Option(names = "--profile",
                description = "Named project profile to register as a concrete harness realization.")
        String profile;

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
            project = project.withProfile(profile);
            SkillProjectRegistration registration = new SkillProjectRegistry(store).register(project);
            if (json) {
                System.out.println("""
                        {"name":"%s","profile":"%s","projectRoot":"%s","manifestPath":"%s","registrationDir":"%s"}"""
                        .formatted(
                                esc(registration.name()),
                                esc(project.activeProfile() == null ? "" : project.activeProfile()),
                                esc(registration.projectRoot().toString()),
                                esc(registration.manifestPath().toString()),
                                esc(registration.registrationDir().toString())));
            } else {
                Log.ok("registered project %s", registration.name());
                if (project.activeProfile() != null) Log.info("  profile:      %s", project.activeProfile());
                Log.info("  project root: %s", registration.projectRoot());
                Log.info("  manifest:     %s", registration.manifestPath());
                Log.info("  registry:     %s", registration.registrationDir());
            }
            return 0;
        }
    }

    @Command(name = "resolve",
            description = "Install declared project dependencies and materialize project bindings.")
    public static final class ResolveCmd implements Callable<Integer> {

        @Option(names = "--project-dir",
                description = "Project root. Defaults to the current working directory.")
        String projectDir;

        @Option(names = "--manifest",
                description = "Explicit project manifest path. Defaults to skill-project.toml, then skill-manager-project.toml.")
        String manifest;

        @Option(names = "--skip-gateway",
                description = "Skip gateway startup/registration; useful for local fixture validation.")
        boolean skipGateway;

        @Option(names = "--profile",
                description = "Named project profile to resolve as a concrete project harness.")
        String profile;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Option(names = "--resolve-libs",
                description = "Also materialize project [[libs]] checkouts under project libs/ and lock their git shas.")
        boolean resolveLibs;

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
            project = project.withProfile(profile);
            GatewayConfig gw = skipGateway ? null : GatewayConfig.resolve(store, null);
            ProjectDependencyResolver.Result result = new ProjectDependencyResolver(store, gw)
                    .resolve(project, new ProjectDependencyResolver.Options(true, !skipGateway));
            ProjectLibResolver.Result libResult = resolveLibs
                    ? new ProjectLibResolver(store).resolve(project)
                    : null;
            if (json) {
                System.out.println("""
                        {"name":"%s","profile":"%s","installed":%d,"resolved":%d,"bindings":%d,"libs":%d,"childHome":"%s","lock":"%s"}"""
                        .formatted(
                                esc(result.registration().name()),
                                esc(project.activeProfile() == null ? "" : project.activeProfile()),
                                result.installed().size(),
                                result.lock().resolvedUnits().size(),
                                result.bindingIds().size(),
                                libResult == null ? result.lock().libs().size() : libResult.libs().size(),
                                esc(result.childHome().layout().childSkillManagerHome().toString()),
                                esc(result.registration().registrationDir()
                                        .resolve(dev.skillmanager.project.SkillProjectLock.FILENAME)
                                        .toString())));
            } else {
                Log.ok("resolved project %s", result.registration().name());
                if (project.activeProfile() != null) Log.info("  profile:   %s", project.activeProfile());
                Log.info("  installed: %d", result.installed().size());
                Log.info("  resolved:  %d", result.lock().resolvedUnits().size());
                Log.info("  bindings:  %d", result.bindingIds().size());
                Log.info("  libs:      %d", libResult == null ? result.lock().libs().size() : libResult.libs().size());
                Log.info("  child:     %s", result.childHome().layout().childSkillManagerHome());
                Log.info("  lock:      %s", result.registration().registrationDir()
                        .resolve(dev.skillmanager.project.SkillProjectLock.FILENAME));
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
                System.out.printf("profiles: %d%n", project.profiles().size());
            }
            var lock = new dev.skillmanager.project.SkillProjectLockStore(store).read(name).orElse(null);
            if (lock != null) {
                System.out.printf("resolved: %d%n", lock.resolvedUnits().size());
                System.out.printf("bindings: %d%n", lock.bindings().size());
                System.out.printf("env locks:%d%n", lock.envs().size());
                System.out.printf("lib locks:%d%n", lock.libs().size());
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

    @Command(name = "profiles",
            description = "Inspect named profiles declared by a skill project.",
            subcommands = {ProfilesCmd.ListCmd.class})
    public static final class ProfilesCmd implements Runnable {
        @Override public void run() { new picocli.CommandLine(this).usage(System.out); }

        @Command(name = "list", description = "List profiles declared in skill-project.toml.")
        public static final class ListCmd implements Callable<Integer> {

            @Option(names = "--project-dir",
                    description = "Project root. Defaults to the current working directory.")
            String projectDir;

            @Option(names = "--manifest",
                    description = "Explicit project manifest path. Defaults to skill-project.toml, then skill-manager-project.toml.")
            String manifest;

            @Override
            public Integer call() throws Exception {
                Path root = projectDir == null || projectDir.isBlank()
                        ? Path.of(System.getProperty("user.dir"))
                        : Path.of(projectDir);
                root = root.toAbsolutePath().normalize();
                SkillProject project = manifest == null || manifest.isBlank()
                        ? SkillProjectParser.load(root)
                        : SkillProjectParser.loadManifest(resolveManifestPath(root, manifest), root);
                if (project.profiles().isEmpty()) {
                    Log.info("no project profiles declared");
                    return 0;
                }
                for (SkillProject.ProjectProfile profile : project.profiles()) {
                    System.out.printf("%s%n", profile.name());
                }
                return 0;
            }
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
