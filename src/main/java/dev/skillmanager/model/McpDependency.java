package dev.skillmanager.model;

import java.util.List;
import java.util.Map;

/**
 * A skill-compatible MCP server declared by a skill.
 *
 * <p>Skill-manager registers each {@code McpDependency} with the virtual MCP
 * gateway. To be compatible, the server must specify exactly one load type:
 * <ul>
 *   <li>{@code docker} – a container image that speaks MCP (stdio by default)</li>
 *   <li>{@code binary} – a downloadable archive + optional init script + bin path</li>
 * </ul>
 */
public record McpDependency(
        String name,
        String displayName,
        String description,
        LoadSpec load,
        List<InitField> initSchema,
        Map<String, Object> initializationParams,
        List<String> requiredTools,
        Integer idleTimeoutSeconds,
        String defaultScope
) {
    /** Scope names understood by the virtual MCP gateway. */
    public static final String SCOPE_SESSION = "session";
    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_GLOBAL_STICKY = "global-sticky";
    public static final String DEFAULT_SCOPE = SCOPE_GLOBAL_STICKY;
    public static final java.util.Set<String> VALID_SCOPES =
            java.util.Set.of(SCOPE_SESSION, SCOPE_GLOBAL, SCOPE_GLOBAL_STICKY);

    public McpDependency {
        initSchema = initSchema == null ? List.of() : List.copyOf(initSchema);
        initializationParams = initializationParams == null ? Map.of() : Map.copyOf(initializationParams);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
        if (defaultScope == null || defaultScope.isBlank()) {
            defaultScope = DEFAULT_SCOPE;
        } else if (!VALID_SCOPES.contains(defaultScope)) {
            throw new IllegalArgumentException(
                    "default_scope must be one of " + VALID_SCOPES + ", got: " + defaultScope);
        }
    }

    /** Required init-schema fields that have no default — must be provided at deploy time. */
    public List<String> missingRequiredInit() {
        List<String> missing = new java.util.ArrayList<>();
        for (InitField f : initSchema) {
            if (f.required() && f.defaultValue() == null) missing.add(f.name());
        }
        return missing;
    }

    public enum LoadType { DOCKER, BINARY }

    public sealed interface LoadSpec permits DockerLoad, BinaryLoad {
        LoadType type();
    }

    public record DockerLoad(
            String image,
            boolean pull,
            String containerPlatform,
            List<String> command,
            List<String> args,
            Map<String, String> env,
            List<String> volumes,
            String transport,
            String url
    ) implements LoadSpec {
        public DockerLoad {
            command = command == null ? List.of() : List.copyOf(command);
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            volumes = volumes == null ? List.of() : List.copyOf(volumes);
        }

        @Override public LoadType type() { return LoadType.DOCKER; }
    }

    public record BinaryLoad(
            Map<String, InstallTarget> install,
            String initScript,
            String binPath,
            List<String> args,
            Map<String, String> env,
            String transport,
            String url
    ) implements LoadSpec {
        public BinaryLoad {
            install = install == null ? Map.of() : Map.copyOf(install);
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
        }

        @Override public LoadType type() { return LoadType.BINARY; }
    }

    public record InstallTarget(String url, String archive, String binary, String sha256) {}

    public record InitField(
            String name,
            String type,
            String description,
            boolean required,
            boolean secret,
            Object defaultValue,
            List<String> enumValues
    ) {
        public InitField {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }
}
