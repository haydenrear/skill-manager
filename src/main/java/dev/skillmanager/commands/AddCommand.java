package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayConfig;
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

import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "add", description = "Add a skill (resolve full tree → show plan → install on consent).")
public final class AddCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source: name[@version], github:user/repo, git+https://..., or local path")
    String source;

    @Option(names = {"--version", "--ref"}, description = "Registry version / git ref") String version;
    @Option(names = "--no-install", description = "Skip CLI dep installation") boolean noInstall;
    @Option(names = "--sync", description = "Sync to the given agent(s) after installing", split = ",") List<String> sync;
    @Option(names = {"-y", "--yes"}, description = "Skip the confirmation prompt") boolean yes;
    @Option(names = "--dry-run", description = "Print the plan and exit without executing") boolean dryRun;
    @Option(names = "--registry",
            description = "Registry URL override for this invocation (persisted so `search` and "
                    + "`add` stay consistent). Can also be set via SKILL_MANAGER_REGISTRY_URL.")
    String registryUrl;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);
        AuditLog audit = new AuditLog(store);

        // Persist the registry override so the Resolver (which constructs its own
        // RegistryClient via RegistryConfig.resolve) picks it up.
        if (registryUrl != null && !registryUrl.isBlank()) {
            dev.skillmanager.registry.RegistryConfig.resolve(store, registryUrl);
        }

        Log.step("resolving %s", source);
        Resolver resolver = new Resolver(store);
        ResolvedGraph graph = resolver.resolve(source, version);
        try {
            boolean withMcp = sync != null && !sync.isEmpty();
            CliLock lock = CliLock.load(store);
            InstallPlan plan = new PlanBuilder(policy, lock).plan(graph, !noInstall, withMcp, store.cliBinDir());
            PlanPrinter.print(plan);

            if (dryRun) {
                Log.info("--dry-run: not executing plan");
                return plan.blocked() ? 2 : 0;
            }
            if (!PlanPrinter.confirm(plan, policy.requireConfirmation(), yes)) {
                Log.warn("plan not executed — nothing committed to the store");
                return plan.blocked() ? 2 : 1;
            }

            // Only now do we mutate the store.
            resolver.commit(graph);
            audit.recordPlan(plan, "add");

            // Tell agents where each skill now lives so they can load the
            // SKILL.md without restarting — the skill-manager-skill leans
            // on INSTALLED: lines to build its own inventory.
            for (var r : graph.resolved()) {
                System.out.println("INSTALLED: " + r.name()
                        + (r.version() == null ? "" : "@" + r.version())
                        + " -> " + store.skillDir(r.name()));
            }

            if (!noInstall) CliInstallRecorder.run(plan, store);

            if (withMcp) {
                var all = store.listInstalled();
                GatewayConfig gw = GatewayConfig.resolve(store, null);
                McpWriter writer = new McpWriter(gw);
                writer.registerAll(all);
                for (String id : sync) {
                    Agent agent = Agent.byId(id);
                    new SkillSync(store).sync(agent, all, true);
                    writer.writeAgentEntry(agent);
                }
            }
            return 0;
        } finally {
            graph.cleanup();
        }
    }

}
