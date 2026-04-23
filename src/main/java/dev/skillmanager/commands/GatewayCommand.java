package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayMcpClient;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "gateway",
        description = "Manage the virtual MCP gateway: start/stop, register servers, proxy MCP tool calls.",
        subcommands = {
                GatewayCommand.Up.class,
                GatewayCommand.Down.class,
                GatewayCommand.Status.class,
                GatewayCommand.Set.class,
                GatewayCommand.Push.class,
                GatewayCommand.Register.class,
                GatewayCommand.Unregister.class,
                GatewayCommand.ListTools.class,
                GatewayCommand.Servers.class,
                GatewayCommand.DescribeServer.class,
                GatewayCommand.Deploy.class,
                GatewayCommand.Undeploy.class,
                GatewayCommand.Tools.class,
                GatewayCommand.Search.class,
                GatewayCommand.DescribeTool.class,
                GatewayCommand.Invoke.class,
                GatewayCommand.Refresh.class,
        })
public final class GatewayCommand implements Runnable {

    @Override
    public void run() { new picocli.CommandLine(this).usage(System.out); }

    // -------------------------------------------------------------- lifecycle

    @Command(name = "up", description = "Start the bundled virtual MCP gateway as a background process.")
    public static final class Up implements Callable<Integer> {
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "8080") int port;
        @Option(names = "--wait-seconds", defaultValue = "15") int waitSeconds;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayRuntime rt = new GatewayRuntime(store);
            if (rt.isRunning()) {
                Log.info("gateway already running (pid=%d)", rt.readPid());
                return 0;
            }
            rt.start(host, port);
            String baseUrl = "http://" + host + ":" + port;
            if (!rt.waitForHealthy(baseUrl, Duration.ofSeconds(waitSeconds))) {
                Log.error("gateway did not become healthy within %ds; see %s", waitSeconds, rt.logFile());
                return 1;
            }
            GatewayConfig.persist(store, baseUrl);
            Log.ok("gateway up at %s", baseUrl);
            return 0;
        }
    }

    @Command(name = "down", description = "Stop the gateway process started via `gateway up`.")
    public static final class Down implements Callable<Integer> {
        @Option(names = "--clear-agents", description = "Also remove the virtual-mcp-gateway entry from agent MCP configs") boolean clearAgents;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayRuntime rt = new GatewayRuntime(store);
            boolean stopped = rt.stop(Duration.ofSeconds(10));
            if (stopped) Log.ok("gateway stopped");
            else Log.info("gateway was not running");
            if (clearAgents) {
                GatewayConfig cfg = GatewayConfig.resolve(store, null);
                McpWriter writer = new McpWriter(cfg);
                for (Agent agent : Agent.all()) writer.removeAgentEntry(agent);
            }
            return 0;
        }
    }

    @Command(name = "set", description = "Persist the gateway URL.")
    public static final class Set implements Callable<Integer> {
        @Parameters(index = "0", description = "Base URL, e.g. http://127.0.0.1:8080") String url;
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig.persist(store, url);
            Log.ok("gateway URL set: %s", url);
            return 0;
        }
    }

    @Command(name = "status", description = "Show gateway URL, process state, reachability.")
    public static final class Status implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig cfg = GatewayConfig.resolve(store, null);
            GatewayRuntime rt = new GatewayRuntime(store);
            System.out.println("base:         " + cfg.baseUrl());
            System.out.println("mcp:          " + cfg.mcpEndpoint());
            System.out.println("servers:      " + cfg.serversEndpoint());
            System.out.println("pid file:     " + rt.pidFile());
            System.out.println("log file:     " + rt.logFile());
            System.out.println("config file:  " + rt.configFile());
            System.out.println("data dir:     " + rt.gatewayDataDir() + "  (dynamic-servers.json + mcp_binaries/)");
            System.out.println("gateway src:  " + rt.gatewaySource());
            System.out.println("python:       " + rt.pythonExecutable());
            boolean running = rt.isRunning();
            System.out.println("process:      " + (running ? "running (pid=" + rt.readPid() + ")" : "not running"));
            boolean reachable = new GatewayClient(cfg).ping();
            System.out.println("health:       " + (reachable ? "reachable" : "unreachable"));
            return reachable ? 0 : 2;
        }
    }

    // ---------------------------------------------------- REST (dynamic registration)

    @Command(name = "push", description = "Register every installed skill's MCP deps with the gateway.")
    public static final class Push implements Callable<Integer> {
        @Option(names = "--gateway") String gatewayUrl;
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig cfg = GatewayConfig.resolve(store, gatewayUrl);
            var skills = store.listInstalled();
            new McpWriter(cfg).registerAll(skills);
            return 0;
        }
    }

    @Command(name = "register",
            description = "Dynamically register a single MCP server with the gateway (REST /servers).")
    public static final class Register implements Callable<Integer> {
        @Parameters(index = "0", description = "Server id (unique identifier on the gateway)") String serverId;
        @Option(names = "--display-name") String displayName;
        @Option(names = "--description", defaultValue = "") String description;

        @Option(names = "--docker", description = "Docker image, e.g. ghcr.io/foo/bar:v1") String image;
        @Option(names = "--pull", description = "Run `docker pull` at register time", defaultValue = "true") boolean pull;
        @Option(names = "--arg", description = "Argument passed to the container/binary (repeatable)") List<String> args;

        @Option(names = "--url", description = "For HTTP/SSE transports: the MCP endpoint") String url;
        @Option(names = "--transport", description = "stdio | streamable-http | sse", defaultValue = "stdio") String transport;

        @Option(names = "--env", description = "KEY=VALUE env var for the server (repeatable)") List<String> env;
        @Option(names = "--idle-timeout", defaultValue = "1800") int idleTimeoutSeconds;
        @Option(names = "--deploy", description = "Deploy immediately after register") boolean deploy;

        @Option(names = "--gateway") String gatewayUrl;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig cfg = GatewayConfig.resolve(store, gatewayUrl);

            McpDependency.LoadSpec load;
            if (image != null) {
                load = new McpDependency.DockerLoad(image, pull, null, List.of(),
                        args == null ? List.of() : args,
                        parseEnv(env), List.of(), transport, url);
            } else if (url != null) {
                // Binary with HTTP transport — gateway proxies to `url` without downloading.
                load = new McpDependency.BinaryLoad(Map.of(), null, null,
                        args == null ? List.of() : args, parseEnv(env), transport, url);
            } else {
                Log.error("provide --docker <image> or --url <mcp-endpoint>");
                return 2;
            }

            McpDependency dep = new McpDependency(
                    serverId,
                    displayName == null ? serverId : displayName,
                    description,
                    load,
                    List.of(),
                    Map.of(),
                    List.of(),
                    idleTimeoutSeconds);

            try {
                var result = new GatewayClient(cfg).register(dep, deploy);
                Log.ok("registered %s (%s)", result.serverId(), result.transport());
                if (result.deployError() != null) Log.warn("deploy warning: %s", result.deployError());
                Log.info("persists in the gateway's dynamic-servers.json; survives gateway restart.");
                return 0;
            } catch (IOException e) {
                Log.error("register failed: %s", e.getMessage());
                return 1;
            }
        }

        private static Map<String, String> parseEnv(List<String> entries) {
            if (entries == null) return Map.of();
            Map<String, String> out = new LinkedHashMap<>();
            for (String e : entries) {
                int eq = e.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("--env expects KEY=VALUE: " + e);
                out.put(e.substring(0, eq), e.substring(eq + 1));
            }
            return out;
        }
    }

    @Command(name = "unregister", description = "Remove a dynamically-registered MCP server from the gateway.")
    public static final class Unregister implements Callable<Integer> {
        @Parameters(index = "0") String serverId;
        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig cfg = GatewayConfig.resolve(store, null);
            boolean ok = new GatewayClient(cfg).unregister(serverId);
            if (ok) Log.ok("unregistered %s", serverId);
            else Log.warn("not found on gateway: %s", serverId);
            return ok ? 0 : 1;
        }
    }

    // ----------------------------------------- MCP pass-through (via Java SDK)

    @Command(name = "list-tools", description = "List every MCP tool exposed by the gateway.")
    public static final class ListTools extends McpCallBase {
        @Override public int run(GatewayMcpClient mcp) {
            var listed = mcp.listTools();
            for (var tool : listed.tools()) {
                String desc = tool.description() == null ? "" : tool.description().replace('\n', ' ');
                System.out.printf("%-24s %s%n", tool.name(), desc.isEmpty() ? "" : " " + desc);
            }
            return 0;
        }
    }

    @Command(name = "servers", description = "Browse MCP servers registered with the gateway.")
    public static final class Servers extends McpCallBase {
        @Option(names = "--deployed") boolean deployed;
        @Option(names = "--undeployed") boolean undeployed;

        @Override
        public int run(GatewayMcpClient mcp) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (deployed) args.put("deployed", true);
            else if (undeployed) args.put("deployed", false);
            var result = mcp.call("browse_mcp_servers", args);
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "describe-server", description = "Describe a registered MCP server.")
    public static final class DescribeServer extends McpCallBase {
        @Parameters(index = "0") String serverId;
        @Override public int run(GatewayMcpClient mcp) {
            var result = mcp.call("describe_mcp_server", Map.of("server_id", serverId));
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "deploy", description = "Deploy an MCP server (spins it up on the gateway).")
    public static final class Deploy extends McpCallBase {
        @Parameters(index = "0") String serverId;
        @Option(names = "--init", description = "KEY=VALUE (repeatable)") List<String> init;
        @Option(names = "--no-reuse-last") boolean noReuseLast;

        @Override public int run(GatewayMcpClient mcp) {
            Map<String, Object> initMap = new LinkedHashMap<>();
            if (init != null) for (String kv : init) {
                int eq = kv.indexOf('=');
                if (eq < 0) throw new IllegalArgumentException("--init expects KEY=VALUE: " + kv);
                initMap.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("server_id", serverId);
            args.put("initialization", initMap);
            args.put("reuse_last_initialization", !noReuseLast);
            var result = mcp.call("deploy_mcp_server", args);
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "undeploy", description = "Undeploy an MCP server.")
    public static final class Undeploy extends McpCallBase {
        @Parameters(index = "0") String serverId;
        @Override public int run(GatewayMcpClient mcp) {
            var result = mcp.call("undeploy_mcp_server", Map.of("server_id", serverId));
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "tools", description = "Browse active tools across deployed servers.")
    public static final class Tools extends McpCallBase {
        @Option(names = "--prefix", defaultValue = "") String prefix;
        @Option(names = "--server") String server;
        @Option(names = "--limit", defaultValue = "100") int limit;

        @Override public int run(GatewayMcpClient mcp) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path_prefix", prefix);
            args.put("limit", limit);
            if (server != null) args.put("server_id", server);
            var result = mcp.call("browse_active_tools", args);
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "search", description = "Semantic search across active tools.")
    public static final class Search extends McpCallBase {
        @Parameters(index = "0") String query;
        @Option(names = "--limit", defaultValue = "10") int limit;
        @Override public int run(GatewayMcpClient mcp) {
            var result = mcp.call("search_tools", Map.of("query", query, "limit", limit));
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "describe-tool", description = "Describe a tool (discloses it for the session).")
    public static final class DescribeTool extends McpCallBase {
        @Parameters(index = "0") String toolPath;
        @Override public int run(GatewayMcpClient mcp) {
            var result = mcp.call("describe_tool", Map.of("tool_path", toolPath));
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    @Command(name = "invoke", description = "Invoke a tool via the gateway.")
    public static final class Invoke extends McpCallBase {
        @Parameters(index = "0") String toolPath;
        @Option(names = "--args", defaultValue = "{}") String argsJson;

        @Override public int run(GatewayMcpClient mcp) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = om.readValue(argsJson, Map.class);
                var result = mcp.call("invoke_tool", Map.of("tool_path", toolPath, "arguments", parsed));
                System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
                return 0;
            } catch (Exception e) {
                Log.error("invoke failed: %s", e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "refresh", description = "Force the gateway to refresh its tool registry.")
    public static final class Refresh extends McpCallBase {
        @Override public int run(GatewayMcpClient mcp) {
            var result = mcp.call("refresh_registry", Map.of());
            System.out.println(mcp.prettyPrint(mcp.extractPayload(result)));
            return 0;
        }
    }

    // ------------------------------------------------- base for MCP subcommands

    abstract static class McpCallBase implements Callable<Integer> {
        @Option(names = "--gateway", description = "Gateway base URL override") String gatewayUrl;
        @Option(names = "--session-id",
                description = "Stable x-session-id header. Pin this across a describe-tool + invoke pair "
                        + "(or across agent runs) so the gateway shares disclosure state.")
        String sessionId;

        public abstract int run(GatewayMcpClient mcp);

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig cfg = GatewayConfig.resolve(store, gatewayUrl);
            try (GatewayMcpClient mcp = new GatewayMcpClient(cfg, sessionId)) {
                return run(mcp);
            } catch (Exception e) {
                Log.error("mcp call failed: %s", e.getMessage());
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                if (root != e) Log.error("cause: %s: %s", root.getClass().getSimpleName(), root.getMessage());
                return 1;
            }
        }
    }
}
