package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Parsed {@code skill-project.toml} / {@code skill-manager-project.toml}
 * manifest. This is project intent only: registration records these
 * declarations without resolving, installing, or materializing them.
 */
public record SkillProject(
        String name,
        String version,
        String description,
        Path projectRoot,
        Path manifestPath,
        List<ProjectUnitRef> skills,
        List<ProjectUnitRef> plugins,
        List<ProjectUnitRef> harnesses,
        List<ProjectUnitRef> docs,
        List<CliDependency> cliDependencies,
        List<McpDependency> mcpDependencies,
        List<ProjectEnv> envs,
        List<ProjectLib> libs
) {
    public SkillProject {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        manifestPath = manifestPath == null ? null : manifestPath.toAbsolutePath().normalize();
        skills = skills == null ? List.of() : List.copyOf(skills);
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        harnesses = harnesses == null ? List.of() : List.copyOf(harnesses);
        docs = docs == null ? List.of() : List.copyOf(docs);
        cliDependencies = cliDependencies == null ? List.of() : List.copyOf(cliDependencies);
        mcpDependencies = mcpDependencies == null ? List.of() : List.copyOf(mcpDependencies);
        envs = envs == null ? List.of() : List.copyOf(envs);
        libs = libs == null ? List.of() : List.copyOf(libs);
    }

    public record ProjectUnitRef(
            String alias,
            UnitKind kind,
            UnitReference reference,
            String revision,
            boolean install
    ) {
        public ProjectUnitRef {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("project dependency alias must not be blank");
            }
            if (kind == null) throw new IllegalArgumentException("project dependency kind must not be null");
            if (reference == null) throw new IllegalArgumentException("project dependency reference must not be null");
            if (!reference.kindFilter().accepts(kind)) {
                throw new IllegalArgumentException("reference " + reference.coord().raw()
                        + " does not match " + kind.name().toLowerCase() + " dependency " + alias);
            }
        }

        public String source() {
            return reference.coord().raw();
        }
    }

    public record ProjectEnv(
            String name,
            String python,
            List<String> dependencies,
            List<String> skillPackages,
            List<String> tools
    ) {
        public ProjectEnv {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project env name must not be blank");
            }
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            skillPackages = skillPackages == null ? List.of() : List.copyOf(skillPackages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record ProjectLib(
            String name,
            String source,
            String ref,
            String sha
    ) {
        public ProjectLib {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("project lib name must not be blank");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("project lib source must not be blank");
            }
        }
    }
}
