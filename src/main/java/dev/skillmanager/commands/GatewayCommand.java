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
 *   <li>{@code attach} / {@code detach} → shared-gateway mode (see
 *       {@link GatewayConfig} for why ownership is modeled at all)</li>
 *   <li>{@code status} → read-only inspection (no effects)</li>
 * </ul>
 *
 * <p>{@code up} and {@code down} are gated on ownership. An attached home
 * starting its own gateway would collide on the port; an attached home
 * stopping one would kill the gateway every other home is using. Both are
 * refused with {@link #ATTACHED_EXIT} rather than attempted.
 */
@Command(
        name = "gateway",
        description = "Manage the virtual MCP gateway process: up, down, attach, detach, status.",
        subcommands = {
                GatewayCommand.Up.class,
                GatewayCommand.Down.class,
                GatewayCommand.Status.class,
                GatewayCommand.Set.class,
                GatewayCommand.Attach.class,
                GatewayCommand.Detach.class,
        })
public final class GatewayCommand implements Runnable {

    /**
     * Exit code for "refused: this home does not own that gateway".
     * Distinct from 1 (the operation ran and failed) because nothing was
     * attempted.
     */
    public static final int ATTACHED_EXIT = 9;

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

        @Option(names = "--force",
                description = "Start a gateway even though this home is attached to a shared one. "
                        + "Expect a port collision unless you also change --host/--port.")
        boolean force;

        private final SkillStore injectedStore;

        public Up() { this(null); }

        public Up(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = injectedStore != null ? injectedStore : SkillStore.defaultStore();
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
                if (!gw.owned() && !force) {
                    Log.error("this home is attached to the shared gateway at %s — it does not "
                            + "own it, so `gateway up` would collide on the port.", gw.baseUrl());
                    Log.info("  use it as-is (agents are already pointed at it), or run "
                            + "`skill-manager gateway detach` to take ownership, or pass --force.");
                    return ATTACHED_EXIT;
                }
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

        @Option(names = "--force",
                description = "Stop the gateway even though this home only attached to it. "
                        + "This takes it away from every other home using it.")
        boolean force;

        private final SkillStore injectedStore;

        public Down() { this(null); }

        public Down(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = injectedStore != null ? injectedStore : SkillStore.defaultStore();
            store.init();
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            // Stopping a shared gateway from an attached home is the most
            // damaging thing in this command surface: it looks local and it
            // takes MCP away from every other home at once.
            if (!gw.owned() && !force) {
                Log.error("this home is attached to the shared gateway at %s — it does not own "
                        + "it, so `gateway down` would stop a gateway other homes are using.",
                        gw.baseUrl());
                Log.info("  run `skill-manager gateway detach` first to take ownership, "
                        + "or pass --force if you really mean to stop the shared one.");
                return ATTACHED_EXIT;
            }

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

    /**
     * Point this home at a gateway another home runs.
     *
     * <p>This is the supported form of what meta-orchestrator does by hand:
     * one gateway is started once, every other home attaches to its
     * endpoint, and none of them fight for the port. Per-unit MCP servers
     * are still reached through the gateway, so the endpoint is the only
     * thing that has to be shared.
     */
    @Command(name = "attach",
            description = "Use a gateway owned by another home. Records the endpoint and gives up "
                    + "the right to start or stop it, so N homes can share one gateway.")
    public static final class Attach implements Callable<Integer> {
        @Parameters(index = "0",
                description = "Base URL of the shared gateway, e.g. http://127.0.0.1:51717")
        String url;

        @Option(names = "--no-sync-agents",
                description = "Don't update agent MCP configs to point at the shared gateway.")
        boolean noSyncAgents;

        @Option(names = "--dry-run",
                description = "Print the effects the program would run without executing them.")
        boolean dryRun;

        private final SkillStore injectedStore;

        public Attach() { this(null); }

        public Attach(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = injectedStore != null ? injectedStore : SkillStore.defaultStore();
            store.init();
            GatewayConfig gw = GatewayConfig.attach(store, url);
            Log.ok("attached to shared gateway %s (this home will not start or stop it)",
                    gw.baseUrl());
            // Reachability is reported, not required: the owner may bring the
            // shared gateway up after the attaching home is configured, and
            // refusing here would force an ordering nobody needs.
            boolean reachable = new GatewayClient(gw).ping();
            if (!reachable) {
                Log.warn("  %s is not reachable yet — the owning home has to run "
                        + "`skill-manager gateway up`", gw.baseUrl());
            }
            if (!noSyncAgents) {
                Program<Integer> program = new Program<>(
                        "gateway-attach-" + UUID.randomUUID(),
                        List.of(new SkillEffect.SyncAgents(List.of(), gw)),
                        receipts -> 0);
                ProgramInterpreter interp = dryRun
                        ? new DryRunInterpreter() : new LiveInterpreter(store, gw);
                interp.run(program);
            }
            return reachable ? 0 : 2;
        }
    }

    /**
     * Take back ownership of the configured gateway so this home may start
     * and stop it again. Deliberately separate from {@code up --force}:
     * reclaiming is a persistent decision about this home, while
     * {@code --force} is a one-off override.
     */
    @Command(name = "detach",
            description = "Stop treating the configured gateway as shared — this home may start "
                    + "and stop it again.")
    public static final class Detach implements Callable<Integer> {

        private final SkillStore injectedStore;

        public Detach() { this(null); }

        public Detach(SkillStore injectedStore) { this.injectedStore = injectedStore; }

        @Override
        public Integer call() throws Exception {
            SkillStore store = injectedStore != null ? injectedStore : SkillStore.defaultStore();
            store.init();
            GatewayConfig gw = GatewayConfig.detach(store);
            Log.ok("this home now owns %s — `gateway up` / `gateway down` are enabled",
                    gw.baseUrl());
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
            // The discovery contract: another home reads `mode` to learn
            // whether this endpoint is one it may manage or only consume.
            System.out.println("mode:         " + (cfg.owned() ? "owner" : "attached"));
            System.out.println("owned:        " + cfg.owned());
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
                // An attached home cannot fix this itself, so telling it to
                // run `gateway up` would send it straight into the refusal.
                System.out.println(cfg.owned()
                        ? "next:         run `skill-manager gateway up` to initialize"
                        : "next:         the owning home must run `skill-manager gateway up` "
                          + "(this home is attached)");
            }
            return reachable ? 0 : 2;
        }
    }
}
