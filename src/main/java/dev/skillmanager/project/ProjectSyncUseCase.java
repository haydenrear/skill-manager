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
 * Placeholder project sync implementation. It intentionally tears down the
 * generated project realization and delegates to project resolve for the
 * rebuild; future work can replace this with an incremental reconciler.
 */
public final class ProjectSyncUseCase {

    private final SkillStore store;
    private final GatewayConfig gateway;

    public ProjectSyncUseCase(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
    }

    public record Result(
            ProjectDependencyResolver.Result resolved,
            int bindingsRemoved,
            List<Path> clearedPaths
    ) {}

    public Result sync(SkillProject project, ProjectDependencyResolver.Options options) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        store.init();
        ProjectDependencyResolver.Options opts = options == null
                ? ProjectDependencyResolver.Options.defaults()
                : options;

        SkillProjectLock previousLock = new SkillProjectLockStore(store)
                .read(project.registryName())
                .orElse(null);
        int removed = removeProjectBindings(previousLock);
        new ChildHomeRegistry(store).delete(project.childHomeId());
        List<Path> cleared = clearGeneratedChildStore(project);

        ProjectDependencyResolver.Result resolved = new ProjectDependencyResolver(store, gateway)
                .resolve(project, opts);
        return new Result(resolved, removed, cleared);
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
                "project-sync-uninstall-" + UUID.randomUUID(),
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
            throw new IOException("project sync placeholder uninstall failed with "
                    + outcome.result() + " failed effect(s)");
        }
        return located;
    }

    private static List<Path> clearGeneratedChildStore(SkillProject project) throws IOException {
        ChildHomeHarnessInstaller.Layout layout = ProjectChildHomeScaffolder.layoutFor(project);
        SkillStore childStore = new SkillStore(layout.childSkillManagerHome());
        List<Path> generated = List.of(
                childStore.skillsDir(),
                childStore.pluginsDir(),
                childStore.docsDir(),
                childStore.harnessesDir(),
                childStore.installedDir(),
                childStore.binDir());
        List<Path> cleared = new ArrayList<>();
        for (Path path : generated) {
            if (!Files.exists(path)) continue;
            Fs.deleteRecursive(path);
            cleared.add(path);
        }
        return List.copyOf(cleared);
    }
}
