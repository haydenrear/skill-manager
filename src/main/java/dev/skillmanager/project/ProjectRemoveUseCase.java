package dev.skillmanager.project;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Removes a project realization without uninstalling parent-home units.
 */
public final class ProjectRemoveUseCase {

    private final SkillStore store;
    private final GatewayConfig gateway;

    public ProjectRemoveUseCase(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public record Result(
            String projectName,
            String profile,
            String childHomeId,
            int bindingsRemoved,
            List<Path> clearedPaths,
            boolean registrationRemoved
    ) {}

    public Result remove(String projectName) throws IOException {
        if (projectName == null || projectName.isBlank()) {
            throw new IOException("project name is required");
        }
        store.init();
        SkillProjectRegistry registry = new SkillProjectRegistry(store);
        SkillProjectRegistration registration = registry.read(projectName).orElseThrow(() ->
                new IOException("project not registered: " + projectName));
        SkillProjectLock lock = new SkillProjectLockStore(store).read(projectName).orElse(null);
        Path manifest = registration.registrationDir().resolve(registration.manifestFile());
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("registered project manifest is missing: " + manifest);
        }
        SkillProject project = SkillProjectParser.loadManifest(manifest, registration.projectRoot());
        if (lock != null && lock.profile() != null) {
            project = project.withProfile(lock.profile());
        }
        Result result = removeRealization(project, lock, false);
        registry.delete(projectName);
        return new Result(
                result.projectName(),
                result.profile(),
                result.childHomeId(),
                result.bindingsRemoved(),
                result.clearedPaths(),
                true);
    }

    public Result remove(SkillProject project) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        store.init();
        SkillProjectLock lock = new SkillProjectLockStore(store)
                .read(project.registryName())
                .orElse(null);
        Result result = removeRealization(project, lock, false);
        new SkillProjectRegistry(store).delete(project.registryName());
        return new Result(
                result.projectName(),
                result.profile(),
                result.childHomeId(),
                result.bindingsRemoved(),
                result.clearedPaths(),
                true);
    }

    Result removeRealization(SkillProject project, SkillProjectLock lock, boolean keepRegistration)
            throws IOException {
        int removed = removeProjectBindings(lock);
        new ChildHomeRegistry(store).delete(project.childHomeId());
        List<Path> cleared = clearGeneratedProjectState(project, lock);
        if (!keepRegistration) {
            new SkillProjectLockStore(store).delete(project.registryName());
        }
        return new Result(
                project.registryName(),
                project.activeProfile(),
                project.childHomeId(),
                removed,
                cleared,
                false);
    }

    private int removeProjectBindings(SkillProjectLock lock) throws IOException {
        if (lock == null || lock.bindings().isEmpty()) return 0;

        BindingStore bindings = new BindingStore(store);
        List<SkillEffect> effects = new ArrayList<>();
        int located = 0;
        for (SkillProjectLock.ProjectBinding row : lock.bindings()) {
            BindingStore.LocatedBinding locatedBinding = bindings.findById(row.bindingId()).orElse(null);
            if (locatedBinding == null) continue;
            located++;
            Binding binding = locatedBinding.binding();
            List<dev.skillmanager.bindings.Projection> projections =
                    new ArrayList<>(binding.projections());
            Collections.reverse(projections);
            for (var p : projections) {
                effects.add(new SkillEffect.UnmaterializeProjection(p));
            }
            effects.add(new SkillEffect.RemoveBinding(locatedBinding.unitName(), binding.bindingId()));
        }
        if (effects.isEmpty()) return 0;

        Program<Integer> program = new Program<>(
                "project-remove-" + UUID.randomUUID(),
                effects,
                receipts -> {
                    int failures = 0;
                    for (var r : receipts) {
                        if (r.status() == dev.skillmanager.effects.EffectStatus.FAILED
                                || r.status() == dev.skillmanager.effects.EffectStatus.PARTIAL) {
                            failures++;
                        }
                    }
                    return failures;
                });
        Executor.Outcome<Integer> outcome = new Executor(store, gateway).run(program);
        if (outcome.result() != 0) {
            throw new IOException("project remove failed with "
                    + outcome.result() + " failed effect(s)");
        }
        return located;
    }

    private static List<Path> clearGeneratedProjectState(SkillProject project, SkillProjectLock lock)
            throws IOException {
        ChildHomeHarnessInstaller.Layout layout = ProjectChildHomeScaffolder.layoutFor(project);
        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        List<Path> generated = new ArrayList<>(List.of(
                childStore.skillsDir(),
                childStore.pluginsDir(),
                childStore.docsDir(),
                childStore.harnessesDir(),
                childStore.installedDir(),
                childStore.binDir()));

        if (lock != null) {
            for (SkillProjectLock.EnvRealization env : lock.envs()) {
                addPath(generated, env.envRoot());
            }
            Path projectSm = layout.childSkillManagerHome();
            generated.add(projectSm.resolve("vendor"));
            generated.add(projectSm.resolve("env.md"));
        }

        List<Path> cleared = new ArrayList<>();
        for (Path path : generated) {
            if (path == null || !Files.exists(path)) continue;
            Fs.deleteRecursive(path);
            cleared.add(path);
        }
        deleteIfEmpty(layout.claudeHome());
        deleteIfEmpty(layout.codexHome());
        deleteIfEmpty(layout.geminiHome());
        deleteIfEmpty(layout.childSkillManagerHome());
        return List.copyOf(cleared);
    }

    private static void addPath(List<Path> paths, String value) {
        if (value == null || value.isBlank()) return;
        paths.add(Path.of(value));
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isPresent()) return;
        }
        Files.delete(dir);
    }
}
