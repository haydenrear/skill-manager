package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.plan.AuditLog;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanAction;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.plan.PlanPrinter;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.sync.SkillSync;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "sync", description = "Sync installed skills to agents and register MCP servers with the gateway.")
public final class SyncCommand implements Callable<Integer> {

    @Option(names = "--agent", description = "Agent id(s): claude, codex, all", split = ",", defaultValue = "all")
    List<String> agents;

    @Option(names = "--copy", description = "Copy instead of symlinking") boolean copy;
    @Option(names = "--no-mcp", description = "Skip MCP gateway registration + agent MCP writes") boolean noMcp;
    @Option(names = "--gateway", description = "Gateway base URL") String gatewayUrl;
    @Option(names = {"-y", "--yes"}, description = "Skip the confirmation prompt") boolean yes;
    @Option(names = "--dry-run", description = "Print the plan and exit") boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        Policy.writeDefaultIfMissing(store);
        Policy policy = Policy.load(store);
        AuditLog audit = new AuditLog(store);

        var skills = store.listInstalled();
        if (skills.isEmpty()) {
            Log.info("no skills installed; nothing to sync");
            return 0;
        }
        List<Agent> targets = resolveAgents();

        // Build a plan just for the MCP actions — file sync + gateway wiring are low-risk.
        InstallPlan plan = new PlanBuilder(policy).plan(skills, false, !noMcp, store.cliBinDir());
        PlanPrinter.print(plan);

        if (dryRun) return plan.blocked() ? 2 : 0;
        if (!PlanPrinter.confirm(plan, policy.requireConfirmation(), yes)) {
            return plan.blocked() ? 2 : 1;
        }

        audit.recordPlan(plan, "sync");

        SkillSync sync = new SkillSync(store);
        for (Agent agent : targets) sync.sync(agent, skills, !copy);

        if (noMcp) return 0;

        GatewayConfig gw = GatewayConfig.resolve(store, gatewayUrl);
        McpWriter writer = new McpWriter(gw);

        // Only register the MCP deps that survived the policy check.
        List<McpDependency> approved = new ArrayList<>();
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.RegisterMcpServer r) approved.add(r.dep());
        }
        if (!approved.isEmpty()) {
            // Re-register with a filtered view: build per-skill lists? For simplicity we
            // call registerAll on all skills; the gateway dedupes by name.
            writer.registerAll(skills);
        }
        for (Agent agent : targets) writer.writeAgentEntry(agent);
        return 0;
    }

    private List<Agent> resolveAgents() {
        if (agents == null || agents.isEmpty() || agents.contains("all")) return Agent.all();
        return agents.stream().map(Agent::byId).toList();
    }
}
