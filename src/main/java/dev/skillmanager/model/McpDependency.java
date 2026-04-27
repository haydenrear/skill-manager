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

    public enum LoadType { DOCKER, BINARY, NPM, UV, SHELL }

    public sealed interface LoadSpec permits DockerLoad, BinaryLoad, NpmLoad, UvLoad, ShellLoad {
        LoadType type();

        /**
         * Tool ids the gateway must have available to spawn this server.
         * Mirrors {@link CliDependency#requiredToolIds()} so the plan
         * collects both into a single deduplicated {@code EnsureTool}
         * group. Empty for load types that are self-contained
         * ({@code binary}) or unmanaged ({@code shell}).
         */
        default java.util.Set<String> requiredToolIds() {
            return switch (this) {
                case DockerLoad d -> java.util.Set.of("docker");
                case NpmLoad n -> java.util.Set.of("npx");
                case UvLoad u -> java.util.Set.of("uv");
                case BinaryLoad b -> java.util.Set.of();
                case ShellLoad s -> java.util.Set.of();
            };
        }
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

    /**
     * MCP server distributed as an npm package. The gateway resolves
     * {@code npx} from skill-manager's bundled Node first
     * ({@code $SKILL_MANAGER_HOME/pm/node/current/bin/npx}), falling back
     * to system PATH, and spawns
     * {@code npx -y <package>[@<version>] [args...]} as a stdio MCP server.
     *
     * <p>Use this when the upstream MCP server publishes only an npm
     * package (e.g. {@code @runpod/mcp-server}) — wrapping {@code npx} in
     * a docker image is possible but adds an unnecessary layer of
     * indirection plus a forced container roundtrip per deploy.
     *
     * <p>Empty-value entries in {@code env} are passed through from the
     * gateway process environment (mirroring the docker convention), so
     * declaring {@code env = {{ API_KEY = "" }}} in the manifest surfaces
     * the secret requirement without committing the value.
     */
    public record NpmLoad(
            String packageName,
            String version,
            List<String> args,
            Map<String, String> env,
            String transport,
            String url
    ) implements LoadSpec {
        public NpmLoad {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
        }

        @Override public LoadType type() { return LoadType.NPM; }
    }

    /**
     * MCP server distributed as a Python package, run via skill-manager's
     * bundled {@code uv}: {@code uv tool run --from <pkg>==<version>
     * <entry-point> [args]} (or {@code uv tool run <pkg> [args]} when no
     * version is pinned and the package's default script is the right
     * entry point).
     *
     * <p>Use this for MCP servers that publish on PyPI (the common case
     * in the agentic-Python ecosystem). Empty-value entries in
     * {@code env} are passed through from the gateway process
     * environment, mirroring the docker/npm convention.
     */
    public record UvLoad(
            String packageName,
            String version,
            String entryPoint,
            List<String> args,
            Map<String, String> env,
            String transport,
            String url
    ) implements LoadSpec {
        public UvLoad {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
        }

        @Override public LoadType type() { return LoadType.UV; }
    }

    /**
     * Escape hatch for MCP servers that don't fit any other load type:
     * the gateway just runs {@code command} as a stdio subprocess. No
     * downloading, no resolution, no PATH magic — whatever's named in
     * {@code command[0]} must already be reachable from the gateway.
     *
     * <p>Use sparingly. Prefer {@link DockerLoad} / {@link BinaryLoad} /
     * {@link NpmLoad} / {@link UvLoad} when one of them fits, because
     * those types declare what skill-manager needs to install or check
     * before registration. {@link ShellLoad} declares no prerequisites
     * — if the command isn't on disk at deploy time, the spawn fails.
     *
     * <p>Empty-value entries in {@code env} follow the same host-env
     * passthrough convention as the other load types.
     */
    public record ShellLoad(
            List<String> command,
            Map<String, String> env,
            String transport,
            String url
    ) implements LoadSpec {
        public ShellLoad {
            command = command == null ? List.of() : List.copyOf(command);
            env = env == null ? Map.of() : Map.copyOf(env);
        }

        @Override public LoadType type() { return LoadType.SHELL; }
    }

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
