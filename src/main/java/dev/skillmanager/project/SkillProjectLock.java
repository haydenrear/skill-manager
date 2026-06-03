package dev.skillmanager.project;

import dev.skillmanager.bindings.BindingSource;
import dev.skillmanager.model.UnitKind;

import java.util.List;

/**
 * Per-project realization lock written under
 * {@code $SKILL_MANAGER_HOME/projects/<name>/project-lock.toml}.
 * It records the installed units and project-specific bindings claimed
 * by {@code project resolve}.
 */
public record SkillProjectLock(
        String projectName,
        String manifestFile,
        String resolvedAt,
        List<ResolvedUnit> resolvedUnits,
        List<ProjectBinding> bindings
) {
    public static final String FILENAME = "project-lock.toml";

    public SkillProjectLock {
        resolvedUnits = resolvedUnits == null ? List.of() : List.copyOf(resolvedUnits);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public record ResolvedUnit(
            String name,
            UnitKind kind,
            String version,
            String source,
            boolean direct
    ) {}

    public record ProjectBinding(
            String bindingId,
            String unitName,
            UnitKind unitKind,
            BindingSource source,
            String targetRoot
    ) {}
}
