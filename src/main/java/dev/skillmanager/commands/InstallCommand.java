package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.GatewayRuntime;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.plan.AuditLog;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.plan.PlanPrinter;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.resolve.Resolver;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.sync.SkillSync;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager install <source>} is the one-shot skill install. It
 * always:
 *
 * <ul>
 *   <li>resolves the full transitive skill dep tree and commits to the store,</li>
 *   <li>installs the CLI deps every resolved skill declares,</li>
 *   <li>registers each skill's MCP deps with the virtual MCP gateway,</li>
 *   <li>copies the new skills into every known agent's skills dir,</li>
 *   <li>ensures every known agent's MCP config has the single
 *       {@code virtual-mcp-gateway} entry (idempotent — no-op if present).</li>
 * </ul>
 *
 * No flags for opting out of steps. No confirmation prompts. Fails fast if
 * the requested top-level skill is already installed — remove it first.
 */
@Command(
        name = "install",
        description = "Install a skill and everything it depends on. Sources can be a registry name "
                + "(`name[@version]`), a github coordinate (`github:user/repo`), a git URL "
                + "(`git+https://...`), or a local directory (`./path`, `/abs/path`, or `file:<path>`). "
                + "Local-directory installs do not contact the registry — useful for iterating on "
                + "a skill from a working tree without publishing first. Use `skill-manager sync "
                + "<name> --from <dir>` to refresh an already-installed skill from the same dir."
)
public final class InstallCommand implements Callable<Integer> {

    @Parameters(index = "0",
            description = "Source: name[@version] (registry), github:user/repo, git+https://..., "
                    + "or a local directory (./path, /abs/path, file:<path>) — local sources do not "
                    + "contact the registry.")
    String source;

    @Option(names = {"--version", "--ref"}, description = "Registry version / git ref") String version;
    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted so `search` and "
                    + "`install` stay consistent). Can also be set via SKILL_MANAGER_REGISTRY_URL.")
    String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);
        AuditLog audit = new AuditLog(store);

        if (registryUrl != null && !registryUrl.isBlank()) {
            dev.skillmanager.registry.RegistryConfig.resolve(store, registryUrl);
        }

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);
        try {
            // Reject if the top-level skill is already installed.
            var resolvedList = graph.resolved();
            String top = resolvedList.isEmpty() ? null : resolvedList.get(0).name();
            if (top != null && store.contains(top)) {
                Log.error("skill '%s' is already installed at %s — remove it first (skill-manager remove %s)",
                        top, store.skillDir(top), top);
                return 3;
            }

            CliLock lock = CliLock.load(store);
            // Pass the runtime so the planner can presence-check external
            // tools (docker, brew) and emit accurate EnsureTool entries.
            dev.skillmanager.pm.PackageManagerRuntime pmRuntime =
                    new dev.skillmanager.pm.PackageManagerRuntime(store);
            InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                    .plan(graph, true, true, store.cliBinDir());
            PlanPrinter.print(plan);
            if (plan.blocked()) {
                Log.error("plan has blocked items — see policy at %s", store.root().resolve("policy.toml"));
                return 2;
            }

            // Ensure the gateway is reachable BEFORE committing the skill
            // to the store. If we commit first and the gateway check then
            // fails, `store.contains(top)` is permanently true and the
            // user's only recovery is `remove` + retry — contradicting the
            // "rerun" hint we'd print.
            GatewayConfig gw = GatewayConfig.resolve(store, null);
            if (!ensureGatewayRunning(store, gw)) {
                Log.error("gateway at %s is unreachable and could not be started — "
                        + "start it manually (`skill-manager gateway up`) and rerun",
                        gw.baseUrl());
                return 4;
            }

            // Commit + record.
            resolver.commit(graph);
            audit.recordPlan(plan, "install");
            for (var r : graph.resolved()) {
                System.out.println("INSTALLED: " + r.name()
                        + (r.version() == null ? "" : "@" + r.version())
                        + " -> " + store.skillDir(r.name()));
            }

            // Tools (uv, npx, docker, brew, …) — bundle bundleables, presence-
            // check externals. Runs once per unique tool, regardless of how
            // many CLI / MCP deps in the graph need it. See PlanAction.EnsureTool
            // and dev.skillmanager.tools.ToolInstallRecorder.
            dev.skillmanager.tools.ToolInstallRecorder.run(plan, store);

            // CLI deps.
            CliInstallRecorder.run(plan, store);

            // MCP gateway: register every installed skill's MCP deps.
            var all = store.listInstalled();

            McpWriter writer = new McpWriter(gw);
            var results = writer.registerAll(all);
            writer.printInstallResults(results);

            // Agents: copy skills + ensure the virtual-mcp-gateway entry
            // exists (idempotent). Track what actually changed so we can
            // tell the user whether they need to restart their agent.
            Map<McpWriter.ConfigChange, java.util.List<String>> changes = new EnumMap<>(McpWriter.ConfigChange.class);
            for (Agent agent : Agent.all()) {
                try {
                    new SkillSync(store).sync(agent, all, true);
                } catch (Exception e) {
                    Log.warn("%s: skill sync failed — %s", agent.id(), e.getMessage());
                }
                try {
                    McpWriter.ConfigChange change = writer.writeAgentEntry(agent);
                    changes.computeIfAbsent(change, k -> new java.util.ArrayList<>())
                            .add(agent.id() + " (" + agent.mcpConfigPath() + ")");
                } catch (Exception e) {
                    Log.warn("%s: mcp config update failed — %s", agent.id(), e.getMessage());
                }
            }
            printAgentConfigSummary(changes, gw.mcpEndpoint().toString());
            return 0;
        } finally {
            graph.cleanup();
        }
    }

    /**
     * Ensure the virtual MCP gateway referenced by {@code gw} is reachable.
     * If its host is local and nothing is listening, start it and wait for
     * {@code /health}. Returns {@code true} when the gateway is alive by
     * the time we return.
     */
    static boolean ensureGatewayRunning(SkillStore store, GatewayConfig gw) {
        GatewayClient ping = new GatewayClient(gw);
        if (ping.ping()) return true;

        URI base = gw.baseUrl();
        String host = base.getHost();
        boolean isLocal = "127.0.0.1".equals(host) || "localhost".equals(host) || "0.0.0.0".equals(host);
        if (!isLocal) {
            Log.warn("gateway at %s is unreachable and not local — not attempting to start", gw.baseUrl());
            return false;
        }

        int port = base.getPort() > 0 ? base.getPort() : 51717;
        GatewayRuntime rt = new GatewayRuntime(store);
        try {
            if (!rt.isRunning()) {
                Log.step("starting virtual MCP gateway on %s:%d", host, port);
                rt.start(host, port);
            }
            if (rt.waitForHealthy(gw.baseUrl().toString(), Duration.ofSeconds(20))) {
                Log.ok("gateway up at %s", gw.baseUrl());
                GatewayConfig.persist(store, gw.baseUrl().toString());
                return true;
            }
            Log.error("gateway did not become healthy within 20s; see %s", rt.logFile());
            return false;
        } catch (Exception e) {
            Log.error("failed to start gateway: %s", e.getMessage());
            return false;
        }
    }

    private static void printAgentConfigSummary(
            Map<McpWriter.ConfigChange, java.util.List<String>> changes,
            String mcpUrl) {
        var added = changes.getOrDefault(McpWriter.ConfigChange.ADDED, java.util.List.of());
        var updated = changes.getOrDefault(McpWriter.ConfigChange.UPDATED, java.util.List.of());
        if (added.isEmpty() && updated.isEmpty()) return;
        System.out.println();
        System.out.println("agent MCP configs:");
        for (String a : added) {
            System.out.println("  ADDED    " + a + "  → " + mcpUrl);
        }
        for (String a : updated) {
            System.out.println("  UPDATED  " + a + "  → " + mcpUrl);
        }
        System.out.println();
        System.out.println("ACTION_REQUIRED: Restart Claude / Codex for the virtual-mcp-gateway entry");
        System.out.println("to take effect — without a restart the agent will not see any MCP tools.");
        System.out.println();
    }
}
