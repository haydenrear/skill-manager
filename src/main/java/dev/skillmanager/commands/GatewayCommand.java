package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.effects.DryRunInterpreter;
import dev.skillmanager.effects.LiveInterpreter;
import dev.skillmanager.effects.Program;
import dev.skillmanager.effects.ProgramInterpreter;
import dev.skillmanager.effects.SkillEffect;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Virtual MCP gateway lifecycle, modeled as effect programs:
 * <ul>
 *   <li>{@code up} → {@link SkillEffect.EnsureGateway} + (optional) {@link SkillEffect.SyncAgents}</li>
 *   <li>{@code down} → {@link SkillEffect.StopGateway}</li>
 *   <li>{@code set} → {@link SkillEffect.ConfigureGateway}</li>
 *   <li>{@code status} → read-only inspection (no effects)</li>
 * </ul>
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
        // Nullable so we can tell apart "user passed --host/--port" from
        // "use the persisted URL." Picocli leaves these null if the option
        // wasn't supplied.
        @Option(names = "--host", description = "Host to bind (default: persisted, else 127.0.0.1)")
        String host;
        @Option(names = "--port", description = "Port to bind (default: persisted, else 51717)")
        Integer port;
        @Option(names = "--wait-seconds", defaultValue = "20",
                description = "How long to wait for /health before declaring the gateway unhealthy.")
        int waitSeconds;
        @Option(names = "--no-sync-agents",
                description = "Don't update agent MCP configs to point at the gateway URL.")
        boolean noSyncAgents;
        @Option(names = "--dry-run",
                description = "Print the effects the program would run without executing them.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();

            // If neither --host nor --port were supplied, defer to the
            // already-persisted URL (or the default) — this preserves the
            // already-running detection: pinging the persisted URL hits
            // the running gateway. Only when the user explicitly overrides
            // host/port do we build a custom URL (and even then, persist
            // is deferred to EnsureGateway's healthy-start path).
            GatewayConfig gw;
            if (host == null && port == null) {
                gw = GatewayConfig.resolve(store, null);
            } else {
                String h = host != null ? host : "127.0.0.1";
                int p = port != null ? port : 51717;
                gw = GatewayConfig.of(java.net.URI.create("http://" + h + ":" + p));
            }

            List<SkillEffect> effects = new ArrayList<>();
            effects.add(new SkillEffect.EnsureGateway(gw, java.time.Duration.ofSeconds(waitSeconds)));
            if (!noSyncAgents) effects.add(new SkillEffect.SyncAgents(List.of(), gw));
            Program<Integer> program = new Program<>(
                    "gateway-up-" + UUID.randomUUID(),
                    effects,
                    receipts -> {
                        int errs = 0;
                        for (var r : receipts) {
                            if (r.status() == dev.skillmanager.effects.EffectStatus.FAILED
                                    || r.status() == dev.skillmanager.effects.EffectStatus.PARTIAL) errs++;
                        }
                        return errs;
                    });
            ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
            int rc = interp.run(program);
            return rc == 0 ? 0 : 1;
        }
    }

    @Command(name = "down", description = "Stop the gateway process started via `gateway up`.")
    public static final class Down implements Callable<Integer> {
        @Option(names = "--clear-agents",
                description = "Also remove the virtual-mcp-gateway entry from agent MCP configs")
        boolean clearAgents;

        @Option(names = "--dry-run",
                description = "Print the effects the program would run without executing them.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            GatewayConfig gw = GatewayConfig.resolve(store, null);

            List<SkillEffect> effects = new ArrayList<>();
            effects.add(new SkillEffect.StopGateway(gw));
            if (clearAgents) {
                for (Agent agent : Agent.all()) {
                    effects.add(new SkillEffect.UnlinkAgentMcpEntry(agent.id(), gw));
                }
            }
            Program<Integer> program = new Program<>(
                    "gateway-down-" + UUID.randomUUID(),
                    effects,
                    receipts -> 0);
            ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
            interp.run(program);
            return 0;
        }
    }

    @Command(name = "set", description = "Persist the gateway URL.")
    public static final class Set implements Callable<Integer> {
        @Parameters(index = "0", description = "Base URL, e.g. http://127.0.0.1:51717") String url;
        @Option(names = "--dry-run",
                description = "Print the effect that would run without executing it.")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            SkillStore store = SkillStore.defaultStore();
            store.init();
            Program<Integer> program = new Program<>(
                    "gateway-set-" + UUID.randomUUID(),
                    List.of(new SkillEffect.ConfigureGateway(url)),
                    receipts -> 0);
            ProgramInterpreter interp = dryRun ? new DryRunInterpreter() : new LiveInterpreter(store, null);
            interp.run(program);
            return 0;
        }
    }

    @Command(name = "status", description = "Show gateway URL, process state, reachability.")
    public static final class Status implements Callable<Integer> {
        private final SkillStore store;

        public Status() {
            this(SkillStore.defaultStore());
        }

        public Status(SkillStore store) {
            this.store = store;
        }

        @Override
        public Integer call() throws Exception {
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
            System.out.println("status:       " + (reachable ? "up" : "down"));
            if (!reachable) {
                System.out.println("next:         run `skill-manager gateway up` to initialize");
            }
            return reachable ? 0 : 2;
        }
    }
}
