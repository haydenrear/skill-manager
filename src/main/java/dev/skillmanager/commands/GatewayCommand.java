package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Virtual MCP gateway lifecycle. No MCP-passthrough subcommands here —
 * agents talk to the gateway over MCP directly; skill-install registers
 * MCP servers transitively. This command only manages the local gateway
 * process.
 */
@Command(
        name = "gateway",
        description = "Manage the virtual MCP gateway process: up, down, status.",
        subcommands = {
                GatewayCommand.Up.class,
                GatewayCommand.Down.class,
                GatewayCommand.Status.class,
                GatewayCommand.Set.class,
        })
public final class GatewayCommand implements Runnable {

    @Override
    public void run() { new picocli.CommandLine(this).usage(System.out); }

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
            rt.ensureVenv();
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
        @Option(names = "--clear-agents",
                description = "Also remove the virtual-mcp-gateway entry from agent MCP configs")
        boolean clearAgents;

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
}
