package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.sync.SkillSync;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager sync [name]} — re-run the install-time side effects
 * for already-installed skills without re-fetching them.
 *
 * <p>The motivating case: a skill declared an MCP server with a required
 * env var (e.g. {@code RUNPOD_API_KEY}) that wasn't set during {@code
 * install}, so the gateway registered the server but never deployed it.
 * After exporting the env var, {@code sync} re-registers and tries to
 * deploy. Also re-syncs agent symlinks and re-asserts the {@code
 * virtual-mcp-gateway} entry in each agent's MCP config.
 *
 * <p>With no argument, syncs every installed skill. With a name, syncs just
 * that skill (but the agent's MCP-config entry is rewritten the same way —
 * it's a single shared entry, not per-skill).
 */
@Command(name = "sync",
        description = "Re-run install side effects (MCP deploy, agent symlinks) for installed skills.")
public final class SyncCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Skill name to sync (default: all installed)")
    String name;

    @Option(names = "--skip-agents",
            description = "Don't refresh agent symlinks or MCP-config entries.")
    boolean skipAgents;

    @Option(names = "--skip-mcp",
            description = "Don't re-register MCP servers with the gateway.")
    boolean skipMcp;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();

        List<Skill> targets;
        if (name != null && !name.isBlank()) {
            if (!store.contains(name)) {
                Log.error("not installed: %s", name);
                return 1;
            }
            targets = List.of(store.load(name).orElseThrow());
        } else {
            targets = store.listInstalled();
        }
        if (targets.isEmpty()) {
            Log.warn("no skills installed");
            return 0;
        }

        GatewayConfig gw = GatewayConfig.resolve(store, null);

        if (!skipMcp) {
            if (!InstallCommand.ensureGatewayRunning(store, gw)) {
                Log.error("gateway at %s is unreachable and could not be started — "
                        + "start it manually (`skill-manager gateway up`) and rerun",
                        gw.baseUrl());
                return 4;
            }
            McpWriter writer = new McpWriter(gw);
            var results = writer.registerAll(targets);
            writer.printInstallResults(results);
        }

        if (!skipAgents) {
            // Symlink + MCP-config refresh always operates on the full
            // installed set, since the gateway entry is shared. When the
            // user named a single skill, only its symlink gets rebuilt.
            List<Skill> linkSet = (name != null && !name.isBlank())
                    ? targets
                    : store.listInstalled();
            McpWriter writer = new McpWriter(gw);
            for (Agent agent : Agent.all()) {
                try {
                    new SkillSync(store).sync(agent, linkSet, true);
                } catch (Exception e) {
                    Log.warn("%s: skill sync failed — %s", agent.id(), e.getMessage());
                }
                try {
                    writer.writeAgentEntry(agent);
                } catch (Exception e) {
                    Log.warn("%s: mcp config update failed — %s", agent.id(), e.getMessage());
                }
            }
        }
        return 0;
    }
}
