package dev.skillmanager.project;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.SkillProject;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
        ProjectRemoveUseCase.Result removed = new ProjectRemoveUseCase(store, gateway)
                .removeRealization(project, previousLock, true);

        ProjectDependencyResolver.Result resolved = new ProjectDependencyResolver(store, gateway)
                .resolve(project, opts);
        return new Result(resolved, removed.bindingsRemoved(), removed.clearedPaths());
    }
}
