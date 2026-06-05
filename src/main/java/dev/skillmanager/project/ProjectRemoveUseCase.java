package dev.skillmanager.project;

import dev.skillmanager.bindings.Binding;
import dev.skillmanager.bindings.BindingStore;
import dev.skillmanager.bindings.ChildHomeHarnessInstaller;
import dev.skillmanager.bindings.ChildHomeRegistry;
import dev.skillmanager.bindings.Projection;
import dev.skillmanager.bindings.ProjectionKind;
import dev.skillmanager.effects.Executor;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.model.SkillProjectParser;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        int removed = removeProjectBindings(project, lock);
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

    private int removeProjectBindings(SkillProject project, SkillProjectLock lock) throws IOException {
        if (lock == null) return 0;

        BindingStore bindings = new BindingStore(store);
        ChildHomeHarnessInstaller.Layout layout = ProjectChildHomeScaffolder.layoutFor(project);
        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        List<SkillEffect> effects = new ArrayList<>();
        Set<Path> scheduledDestinations = new LinkedHashSet<>();
        int removed = 0;
        for (SkillProjectLock.ProjectBinding row : lock.bindings()) {
            BindingStore.LocatedBinding locatedBinding = bindings.findById(row.bindingId()).orElse(null);
            if (locatedBinding != null) {
                removed++;
                Binding binding = locatedBinding.binding();
                addUnmaterializeEffects(effects, binding.projections(), scheduledDestinations);
                effects.add(new SkillEffect.RemoveBinding(locatedBinding.unitName(), binding.bindingId()));
                continue;
            }

            Binding fallback = ownedFallbackProjectAgentBinding(row, layout, childStore);
            if (fallback == null) continue;
            removed++;
            addUnmaterializeEffects(effects, fallback.projections(), scheduledDestinations);
            effects.add(new SkillEffect.RemoveBinding(row.unitName(), row.bindingId()));
        }
        addResolvedUnitProjectionFallbacks(lock, layout, childStore, effects, scheduledDestinations);
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
        return removed;
    }

    private static void addUnmaterializeEffects(
            List<SkillEffect> effects,
            List<Projection> projections,
            Set<Path> scheduledDestinations
    ) {
        List<Projection> reversed = new ArrayList<>(projections);
        Collections.reverse(reversed);
        for (Projection p : reversed) {
            if (!scheduledDestinations.add(normalize(p.destPath()))) continue;
            effects.add(new SkillEffect.UnmaterializeProjection(p));
        }
    }

    private static Binding ownedFallbackProjectAgentBinding(
            SkillProjectLock.ProjectBinding row,
            ChildHomeHarnessInstaller.Layout layout,
            SkillStore childStore
    ) throws IOException {
        if (!row.bindingId().contains(":unit:")) return null;
        if (row.unitKind() != UnitKind.SKILL && row.unitKind() != UnitKind.PLUGIN) return null;
        Path source = childStore.unitDir(row.unitName(), row.unitKind()).toAbsolutePath().normalize();
        List<Projection> projections = projectAgentProjections(
                row.bindingId(), source, layout, row.unitName(), row.unitKind());
        List<Projection> ownedProjections = new ArrayList<>();
        for (Projection projection : projections) {
            if (isProjectOwnedSymlink(projection.destPath(), source)) {
                ownedProjections.add(projection);
            }
        }
        if (ownedProjections.isEmpty()) return null;
        return new Binding(
                row.bindingId(),
                row.unitName(),
                row.unitKind(),
                null,
                row.targetRoot() == null ? layout.targetDir() : Path.of(row.targetRoot()),
                dev.skillmanager.bindings.ConflictPolicy.OVERWRITE,
                BindingStore.nowIso(),
                row.source(),
                ownedProjections);
    }

    private static void addResolvedUnitProjectionFallbacks(
            SkillProjectLock lock,
            ChildHomeHarnessInstaller.Layout layout,
            SkillStore childStore,
            List<SkillEffect> effects,
            Set<Path> scheduledDestinations
    ) throws IOException {
        for (SkillProjectLock.ResolvedUnit unit : lock.resolvedUnits()) {
            if (unit.kind() != UnitKind.SKILL && unit.kind() != UnitKind.PLUGIN) continue;
            Path source = childStore.unitDir(unit.name(), unit.kind()).toAbsolutePath().normalize();
            for (Projection p : projectAgentProjections(
                    "project-remove-fallback:" + unit.name(), source, layout, unit.name(), unit.kind())) {
                if (!isProjectOwnedSymlink(p.destPath(), source)) continue;
                if (!scheduledDestinations.add(normalize(p.destPath()))) continue;
                effects.add(new SkillEffect.UnmaterializeProjection(p));
            }
        }
    }

    static List<Projection> projectAgentProjections(
            String bindingId,
            Path source,
            ChildHomeHarnessInstaller.Layout layout,
            String name,
            UnitKind kind
    ) {
        return switch (kind) {
            case SKILL -> List.of(
                    new Projection(bindingId, source,
                            layout.claudeHome().resolve("skills").resolve(name),
                            ProjectionKind.SYMLINK, null),
                    new Projection(bindingId, source,
                            layout.codexHome().resolve("skills").resolve(name),
                            ProjectionKind.SYMLINK, null),
                    new Projection(bindingId, source,
                            layout.geminiHome().resolve("skills").resolve(name),
                            ProjectionKind.SYMLINK, null));
            case PLUGIN -> List.of(
                    new Projection(bindingId, source,
                            layout.claudeHome().resolve("plugins").resolve(name),
                            ProjectionKind.SYMLINK, null));
            case DOC, HARNESS -> List.of();
        };
    }

    static boolean isProjectOwnedSymlink(Path dest, Path expectedSource) throws IOException {
        if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS) || !Files.isSymbolicLink(dest)) {
            return false;
        }
        Path link = Files.readSymbolicLink(dest);
        Path resolved = link.isAbsolute()
                ? link.normalize()
                : dest.getParent().resolve(link).normalize();
        return resolved.equals(expectedSource.toAbsolutePath().normalize());
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    static List<Path> generatedProjectStatePaths(SkillProject project, SkillProjectLock lock) {
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
        return List.copyOf(generated);
    }

    private static List<Path> clearGeneratedProjectState(SkillProject project, SkillProjectLock lock)
            throws IOException {
        ChildHomeHarnessInstaller.Layout layout = ProjectChildHomeScaffolder.layoutFor(project);
        List<Path> generated = generatedProjectStatePaths(project, lock);
        List<Path> cleared = new ArrayList<>();
        for (Path path : generated) {
            if (path == null || !Files.exists(path)) continue;
            Fs.deleteRecursive(path);
            cleared.add(path);
        }
        deleteIfEmpty(layout.claudeHome().resolve("skills"));
        deleteIfEmpty(layout.claudeHome().resolve("plugins"));
        deleteIfEmpty(layout.codexHome().resolve("skills"));
        deleteIfEmpty(layout.codexHome().resolve("plugins"));
        deleteIfEmpty(layout.geminiHome().resolve("skills"));
        deleteIfEmpty(layout.geminiHome().resolve("plugins"));
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
