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
        List<ProjectBinding> bindings,
        List<EnvRealization> envs,
        List<LibCheckout> libs
) {
    public static final String FILENAME = "project-lock.toml";

    public SkillProjectLock {
        resolvedUnits = resolvedUnits == null ? List.of() : List.copyOf(resolvedUnits);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        envs = envs == null ? List.of() : List.copyOf(envs);
        libs = libs == null ? List.of() : List.copyOf(libs);
    }

    public SkillProjectLock(
            String projectName,
            String manifestFile,
            String resolvedAt,
            List<ResolvedUnit> resolvedUnits,
            List<ProjectBinding> bindings
    ) {
        this(projectName, manifestFile, resolvedAt, resolvedUnits, bindings, List.of(), List.of());
    }

    public SkillProjectLock(
            String projectName,
            String manifestFile,
            String resolvedAt,
            List<ResolvedUnit> resolvedUnits,
            List<ProjectBinding> bindings,
            List<EnvRealization> envs
    ) {
        this(projectName, manifestFile, resolvedAt, resolvedUnits, bindings, envs, List.of());
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

    public record EnvRealization(
            String name,
            String python,
            String envRoot,
            String pyprojectFile,
            String lockFile,
            String venvDir,
            String docsFile,
            List<String> dependencies,
            List<String> skillPackages,
            List<String> vendorUnits,
            List<String> tools,
            String syncedAt
    ) {
        public EnvRealization {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project env lock name must not be blank");
            }
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            skillPackages = skillPackages == null ? List.of() : List.copyOf(skillPackages);
            vendorUnits = vendorUnits == null ? List.of() : List.copyOf(vendorUnits);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record LibCheckout(
            String name,
            String source,
            String url,
            String ref,
            String requestedSha,
            String resolvedSha,
            String checkoutDir,
            String lockedAt
    ) {
        public LibCheckout {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project lib lock name must not be blank");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("project lib lock source must not be blank");
            }
            if (resolvedSha == null || resolvedSha.isBlank()) {
                throw new IllegalArgumentException("project lib resolved sha must not be blank");
            }
        }
    }
}
