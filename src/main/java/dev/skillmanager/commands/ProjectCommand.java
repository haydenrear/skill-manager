package dev.skillmanager.commands;

import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.project.ProjectDependencyResolver;
import dev.skillmanager.project.ProjectLibResolver;
import dev.skillmanager.project.ProjectRemoveUseCase;
import dev.skillmanager.project.ProjectSyncUseCase;
import dev.skillmanager.project.SkillProjectRegistration;
import dev.skillmanager.project.SkillProjectRegistry;
import dev.skillmanager.project.UnitTrunkPull;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import dev.skillmanager.validation.MarkdownImportValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "project",
        description = "Register and inspect skill project manifests.",
        subcommands = {
                ProjectCommand.RegisterCmd.class,
                ProjectCommand.ResolveCmd.class,
                ProjectCommand.SyncCmd.class,
                ProjectCommand.RemoveCmd.class,
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

        @Option(names = "--repair-vendored",
                description = "Re-point declared [[vendored]] paths at this project's own "
                        + ".skill-manager home. Off by default: a vendored path is a tracked "
                        + "symlink, so repairing one edits your working tree. Validation always "
                        + "runs; only the writing is opt-in.")
        boolean repairVendored;

        // The help text used to say "Refused by default: that layout isolates
        // nothing, and it used to exit 0 reporting every unit resolved." That
        // refusal was removed — a repository-local home IS the per-checkout
        // layout this epic exists to produce, so refusing it broke the normal
        // path — and the behaviour is now
        // ProjectChildHomeScaffolder#reportSameHome, which warns and proceeds.
        // A --help that documents a refusal the code no longer performs is the
        // same fail-open as a check that cannot see: the operator reads a
        // guarantee, gets none, and has no way to tell.
        @Option(names = "--allow-same-home",
                description = "Accepted and no longer consulted. SKILL_MANAGER_HOME already "
                        + "BEING this project's own .skill-manager home is the per-checkout "
                        + "layout, not an error: units resolve in place, no separate child home "
                        + "is created, and resolve says so and proceeds. Kept so existing "
                        + "invocations and scripts do not break.")
        boolean allowSameHome;

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
                    .resolve(project, new ProjectDependencyResolver.Options(
                            true, !skipGateway, java.util.Set.of(), repairVendored,
                            allowSameHome));
            ProjectLibResolver.Result libResult = resolveLibs
                    ? new ProjectLibResolver(store).resolve(project)
                    : null;
            // The project's OWN markdown, which until now nothing validated.
            // AFTER the resolve, deliberately: a `skill-imports` entry names an
            // installed unit, and the resolve is what installs them, so checking
            // first would report every declared unit as missing.
            List<MarkdownImportValidator.Violation> importViolations =
                    MarkdownImportValidator.validateProject(
                            store, result.registration().name(), root);
            if (json) {
                System.out.println("""
                        {"name":"%s","profile":"%s","installed":%d,"resolved":%d,"bindings":%d,"libs":%d,\
                        "vendored":%d,"vendoredRepaired":%d,"vendoredProblems":%s,"heldBack":%s,"childHome":"%s","lock":"%s",\
                        "markdownImportViolations":%d}"""
                        .formatted(
                                esc(result.registration().name()),
                                esc(project.activeProfile() == null ? "" : project.activeProfile()),
                                result.installed().size(),
                                result.lock().resolvedUnits().size(),
                                result.bindingIds().size(),
                                libResult == null ? result.lock().libs().size() : libResult.libs().size(),
                                result.vendored().entries().size(),
                                result.vendored().repairs().size(),
                                vendoredJson(result.vendored()),
                                heldBackJson(result.childHome()),
                                esc(result.childHome().layout().childSkillManagerHome().toString()),
                                esc(result.registration().registrationDir()
                                        .resolve(dev.skillmanager.project.SkillProjectLock.FILENAME)
                                        .toString()),
                                importViolations.size()));
            } else {
                Log.ok("resolved project %s", result.registration().name());
                if (project.activeProfile() != null) Log.info("  profile:   %s", project.activeProfile());
                Log.info("  installed: %d", result.installed().size());
                Log.info("  resolved:  %d", result.lock().resolvedUnits().size());
                Log.info("  bindings:  %d", result.bindingIds().size());
                Log.info("  libs:      %d", libResult == null ? result.lock().libs().size() : libResult.libs().size());
                Log.info("  vendored:  %d checked, %d repaired, %d finding(s)",
                        result.vendored().entries().size(),
                        result.vendored().repairs().size(),
                        result.vendored().problems().size());
                Log.info("  child:     %s", result.childHome().layout().childSkillManagerHome());
                Log.info("  lock:      %s", result.registration().registrationDir()
                        .resolve(dev.skillmanager.project.SkillProjectLock.FILENAME));
                reportHeldBack(result.childHome());
            }
            return reportProjectImportViolations(importViolations, json);
        }
    }

    /**
     * Print the project checkout's own markdown skill-import violations, and
     * give them an exit code.
     *
     * <h2>Why an exit code, and why THIS exit code</h2>
     *
     * <p>A printed violation that does not reach {@code $?} is a comment. That
     * lesson was already paid for on {@code install}, which used to print the
     * block after its success banner and exit 0 —
     * {@link MarkdownImportValidator#EXIT_CODE} exists because of it. This
     * reuses that code rather than inventing a second one: the condition is
     * the same condition ("your markdown names something that is not there"),
     * and a caller that already handles 11 from {@code install} should not
     * have to learn a second number to handle it from {@code resolve}.
     *
     * <p>Printed before the return and on stderr, next to the summary rather
     * than beneath a wall of success output, for the same reason.
     *
     * <p>The resolve itself is NOT rolled back. The units are installed, the
     * bindings are materialized, and the manifest that describes them is
     * correct; what is wrong is a reference inside the checkout's own prose.
     * Undoing the install over that would be a worse trade than reporting it,
     * which is the same call {@code install} makes when a committed unit
     * carries a bad reference.
     */
    private static int reportProjectImportViolations(
            List<MarkdownImportValidator.Violation> violations, boolean json) {
        if (violations.isEmpty()) return 0;
        if (!json) {
            System.err.println();
            System.err.println("markdown skill-import violations (" + violations.size()
                    + ") in this project's own files — fix these references:");
            for (MarkdownImportValidator.Violation v : violations) {
                System.err.println("  - " + v.unitName() + " (project): " + v.file());
                System.err.println("    " + v.message());
            }
            System.err.println();
        }
        return MarkdownImportValidator.EXIT_CODE;
    }

    /**
     * {@code project sync} — pull each unit's trunk, then reconcile the
     * realization in place.
     *
     * <p>Was a placeholder that tore the realization down and re-resolved it from
     * the same local store. See {@link ProjectSyncUseCase} for what replaced it
     * and why the teardown is now behind {@code --rebuild}.
     */
    @Command(name = "sync",
            description = "Pull each project unit's trunk and reconcile the project realization "
                    + "in place. Units with local edits are held back, not merged over, unless "
                    + "--merge is given.")
    public static final class SyncCmd implements Callable<Integer> {

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
                description = "Named project profile to sync as a concrete project harness.")
        String profile;

        @Option(names = "--no-pull",
                description = "Reconcile only; do not fetch or merge any unit's trunk.")
        boolean noPull;

        @Option(names = "--merge",
                description = "Three-way merge the trunk into units that have local changes, "
                        + "instead of holding them back. A conflict is reported and left for you; "
                        + "local work is never discarded either way.")
        boolean merge;

        @Option(names = "--ref",
                description = "Branch to pull. Defaults to " + UnitTrunkPull.DEFAULT_TRUNK
                        + ", falling back to the remote's default branch.")
        String ref;

        @Option(names = "--git-latest",
                description = "Pull the ref each unit was installed from instead of the trunk.")
        boolean gitLatest;

        @Option(names = "--from", paramLabel = "DIR",
                description = "Directory holding <unit-name> checkouts to pull from instead of "
                        + "each unit's origin.")
        Path from;

        @Option(names = "--checkout", paramLabel = "UNIT",
                description = "Materialize this unit into the child home as its own git checkout "
                        + "so its edits can be published back (repeatable).")
        List<String> checkout = new ArrayList<>();

        @Option(names = "--rebuild",
                description = "Tear the realization down and rebuild it instead of reconciling in "
                        + "place. For a realization that is actually broken; it is the only path "
                        + "that removes child-home content, so it is not the default.")
        boolean rebuild;

        @Option(names = "--repair-vendored",
                description = "Re-point declared [[vendored]] paths at this project's own "
                        + ".skill-manager home. Off by default: a vendored path is a tracked "
                        + "symlink, so repairing one edits your working tree. Validation always "
                        + "runs; only the writing is opt-in.")
        boolean repairVendored;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        private final SkillStore injectedStore;

        public SyncCmd() { this(null); }

        public SyncCmd(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = injectedStore != null ? injectedStore : SkillStore.defaultStore();
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
            ProjectSyncUseCase.Result result = new ProjectSyncUseCase(store, gw)
                    .sync(project,
                            new ProjectDependencyResolver.Options(true, !skipGateway,
                                    new LinkedHashSet<>(checkout), repairVendored),
                            new ProjectSyncUseCase.Options(!noPull, rebuild,
                                    new UnitTrunkPull.Options(merge, ref, gitLatest, from)));
            if (json) {
                System.out.println("""
                        {"name":"%s","profile":"%s","mode":"%s","pulled":%d,"pullHeldBack":%s,\
                        "pullProblems":%s,"bindingsRemoved":%d,"clearedPaths":%d,"installed":%d,\
                        "resolved":%d,"vendored":%d,"vendoredRepaired":%d,"vendoredProblems":%s,\
                        "heldBack":%s,"childHome":"%s"}"""
                        .formatted(
                                esc(result.resolved().registration().name()),
                                esc(project.activeProfile() == null ? "" : project.activeProfile()),
                                result.mode(),
                                result.pull().changed().size(),
                                pullJson(result.pull().heldBack()),
                                pullJson(result.pull().problems()),
                                result.bindingsRemoved(),
                                result.clearedPaths().size(),
                                result.resolved().installed().size(),
                                result.resolved().lock().resolvedUnits().size(),
                                result.resolved().vendored().entries().size(),
                                result.resolved().vendored().repairs().size(),
                                vendoredJson(result.resolved().vendored()),
                                heldBackJson(result.resolved().childHome()),
                                esc(result.resolved().childHome().layout().childSkillManagerHome().toString())));
            } else {
                Log.ok("synced project %s", result.resolved().registration().name());
                if (project.activeProfile() != null) Log.info("  profile:          %s", project.activeProfile());
                Log.info("  mode:             %s", result.mode());
                Log.info("  pulled:           %d of %d unit(s) moved",
                        result.pull().changed().size(), result.pull().pulls().size());
                if (rebuild) {
                    Log.info("  bindings removed: %d", result.bindingsRemoved());
                    Log.info("  cleared paths:    %d", result.clearedPaths().size());
                }
                Log.info("  installed:        %d", result.resolved().installed().size());
                Log.info("  resolved:         %d", result.resolved().lock().resolvedUnits().size());
                Log.info("  vendored:         %d checked, %d repaired, %d finding(s)",
                        result.resolved().vendored().entries().size(),
                        result.resolved().vendored().repairs().size(),
                        result.resolved().vendored().problems().size());
                Log.info("  child:            %s", result.resolved().childHome().layout().childSkillManagerHome());
                reportPull(result.pull());
                reportHeldBack(result.resolved().childHome());
            }
            return result.pull().problems().isEmpty() ? 0 : 1;
        }
    }

    @Command(name = "remove",
            description = "Remove a project registration and generated project realization without uninstalling shared units.")
    public static final class RemoveCmd implements Callable<Integer> {

        @Parameters(index = "0", arity = "0..1", description = "Registered project name. Defaults to --project-dir/current project.")
        String name;

        @Option(names = "--project-dir",
                description = "Project root. Defaults to the current working directory when name is omitted.")
        String projectDir;

        @Option(names = "--manifest",
                description = "Explicit project manifest path. Defaults to skill-project.toml, then skill-manager-project.toml.")
        String manifest;

        @Option(names = "--profile",
                description = "Named project profile to remove.")
        String profile;

        @Option(names = "--skip-gateway",
                description = "Skip gateway access while removing project bindings.")
        boolean skipGateway;

        @Option(names = "--json", description = "Emit machine-readable JSON.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig gw = skipGateway ? null : GatewayConfig.resolve(store, null);
            ProjectRemoveUseCase remover = new ProjectRemoveUseCase(store, gw);
            ProjectRemoveUseCase.Result result;
            if (name != null && !name.isBlank()) {
                result = remover.remove(name);
            } else {
                Path root = projectDir == null || projectDir.isBlank()
                        ? Path.of(System.getProperty("user.dir"))
                        : Path.of(projectDir);
                root = root.toAbsolutePath().normalize();
                SkillProject project = manifest == null || manifest.isBlank()
                        ? SkillProjectParser.load(root)
                        : SkillProjectParser.loadManifest(resolveManifestPath(root, manifest), root);
                project = project.withProfile(profile);
                result = remover.remove(project);
            }

            if (json) {
                System.out.println("""
                        {"name":"%s","profile":"%s","childHomeId":"%s","bindingsRemoved":%d,"clearedPaths":%d,"registrationRemoved":%s}"""
                        .formatted(
                                esc(result.projectName()),
                                esc(result.profile() == null ? "" : result.profile()),
                                esc(result.childHomeId()),
                                result.bindingsRemoved(),
                                result.clearedPaths().size(),
                                result.registrationRemoved()));
            } else {
                Log.ok("removed project %s", result.projectName());
                if (result.profile() != null) Log.info("  profile:              %s", result.profile());
                Log.info("  child-home id:        %s", result.childHomeId());
                Log.info("  bindings removed:     %d", result.bindingsRemoved());
                Log.info("  generated paths gone: %d", result.clearedPaths().size());
                Log.info("  registration removed: %s", result.registrationRemoved());
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
                System.out.printf("vendored: %d%n", project.vendored().stream()
                        .mapToInt(v -> v.paths().size()).sum());
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

    /**
     * Tells the user which child-home units were left alone because they carry
     * local edits. {@code ChildHomeMaterializer} already warned per unit; this
     * makes the count part of the command summary so it is not lost in the
     * scroll, and spells out how to take the parent's version instead.
     */
    private static void reportHeldBack(
            dev.skillmanager.project.ProjectChildHomeScaffolder.Result childHome) {
        if (childHome == null || childHome.heldBack().isEmpty()) return;
        Log.warn("held back %d child-home unit(s) with local changes (not overwritten):",
                childHome.heldBack().size());
        for (var outcome : childHome.heldBack()) {
            Log.warn("  %s — %s", outcome.label(), outcome.childPath());
        }
        Log.warn("  delete or move a path above and re-run to take the parent store's version");
    }

    /**
     * Says out loud which units were left alone and which need a human.
     *
     * <p>A held-back unit is the point of the whole hold-back rule, so it cannot
     * be a silent skip; and a conflict is the one outcome where {@code sync}
     * deliberately stops short of finishing, so the path to finish it has to be
     * printed.
     */
    private static void reportPull(dev.skillmanager.project.UnitTrunkPull.Report pull) {
        if (!pull.heldBack().isEmpty()) {
            Log.warn("held back %d unit(s) with uncommitted local changes (not merged over):",
                    pull.heldBack().size());
            for (var unit : pull.heldBack()) Log.warn("  %s — %s", unit.label(), unit.detail());
            Log.warn("  re-run with --merge to three-way merge them against the trunk");
        }
        for (var unit : pull.problems()) {
            Log.error("%s — %s", unit.label(), unit.detail());
            for (String file : unit.conflictedFiles()) Log.error("    conflict: %s", file);
        }
    }

    private static String pullJson(
            List<dev.skillmanager.project.UnitTrunkPull.UnitPull> units) {
        if (units == null || units.isEmpty()) return "[]";
        return units.stream()
                .map(unit -> "{\"unit\":\"" + esc(unit.label())
                        + "\",\"status\":\"" + unit.status().name().toLowerCase()
                        + "\",\"repo\":\"" + esc(String.valueOf(unit.repo()))
                        + "\",\"detail\":\"" + esc(unit.detail()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /**
     * The surviving vendored findings, in machine-readable form.
     *
     * <p>Carries {@code resolvedTo} as well as {@code linkText}: the whole point
     * of the check is that those two can disagree — a relative link text that
     * resolves into a foreign home through a sibling link is the case a text-only
     * report cannot express.
     */
    private static String vendoredJson(
            dev.skillmanager.project.ProjectVendoredResolver.Report report) {
        if (report == null || report.problems().isEmpty()) return "[]";
        return report.problems().stream()
                .map(entry -> "{\"declaration\":\"" + esc(entry.declaration())
                        + "\",\"path\":\"" + esc(entry.declaredPath())
                        + "\",\"status\":\"" + entry.status().name().toLowerCase()
                        + "\",\"fatal\":" + entry.fatal()
                        + ",\"linkText\":\"" + esc(String.valueOf(entry.linkText()))
                        + "\",\"resolvedTo\":\"" + esc(String.valueOf(entry.resolvedTo()))
                        + "\",\"expected\":\"" + esc(String.valueOf(entry.expectedTarget()))
                        + "\",\"detail\":\"" + esc(entry.detail()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String heldBackJson(
            dev.skillmanager.project.ProjectChildHomeScaffolder.Result childHome) {
        if (childHome == null || childHome.heldBack().isEmpty()) return "[]";
        return childHome.heldBack().stream()
                .map(outcome -> "{\"unit\":\"" + esc(outcome.label())
                        + "\",\"path\":\"" + esc(outcome.childPath().toString())
                        + "\",\"reason\":\"" + esc(outcome.detail()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    static Path resolveManifestPath(Path root, String manifest) {
        Path path = Path.of(manifest);
        return path.isAbsolute()
                ? path.normalize()
                : root.resolve(path).normalize();
    }
}
