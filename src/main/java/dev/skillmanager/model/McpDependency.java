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
        Integer idleTimeoutSeconds
) {
    public McpDependency {
        initSchema = initSchema == null ? List.of() : List.copyOf(initSchema);
        initializationParams = initializationParams == null ? Map.of() : Map.copyOf(initializationParams);
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
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
