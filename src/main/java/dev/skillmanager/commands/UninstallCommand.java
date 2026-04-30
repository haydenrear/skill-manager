package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.shared.util.Fs;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code skill-manager uninstall <name>} — full removal counterpart to
 * {@link InstallCommand}.
 *
 * <p>Removes the skill from the user's skill-manager directory, clears every
 * known agent's symlink for it, and unregisters any MCP servers that no
 * surviving skill still declares. Prefer this over {@code remove} when the
 * goal is "leave no trace"; {@code remove} is the lower-level primitive that
 * only deletes from the store and requires {@code --from} to unlink agents.
 */
@Command(name = "uninstall", aliases = "un",
        description = "Uninstall a skill: clear store entry, all agent symlinks, and orphan MCP servers.")
public final class UninstallCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill name")
    String name;

    @Option(names = "--keep-mcp",
            description = "Don't unregister orphan MCP servers from the gateway.")
    boolean keepMcp;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        if (!store.contains(name)) {
            Log.warn("skill not found: %s", name);
            return 1;
        }

        // Snapshot the MCP deps before deletion so we can compute orphans.
        List<McpDependency> removedDeps = store.load(name)
                .map(Skill::mcpDependencies)
                .orElse(List.of());

        store.remove(name);
        Log.ok("removed %s from %s", name, store.skillDir(name));

        // Always clear every agent's symlink — that's the whole reason this
        // command exists vs. `remove`.
        for (Agent agent : Agent.all()) {
            Path link = agent.skillsDir().resolve(name);
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(link)) {
                try {
                    Fs.deleteRecursive(link);
                    Log.ok("%s: unlinked %s", agent.id(), name);
                } catch (Exception e) {
                    Log.warn("%s: failed to unlink %s — %s", agent.id(), name, e.getMessage());
                }
            }
        }

        if (!keepMcp && !removedDeps.isEmpty()) {
            unregisterOrphans(store, removedDeps);
        }
        return 0;
    }

    private static void unregisterOrphans(SkillStore store, List<McpDependency> removedDeps) throws Exception {
        Set<String> stillReferenced = new HashSet<>();
        for (Skill s : store.listInstalled()) {
            for (McpDependency d : s.mcpDependencies()) stillReferenced.add(d.name());
        }
        List<String> orphans = new ArrayList<>();
        for (McpDependency d : removedDeps) {
            if (!stillReferenced.contains(d.name())) orphans.add(d.name());
        }
        if (orphans.isEmpty()) return;

        GatewayClient client = new GatewayClient(GatewayConfig.resolve(store, null));
        if (!client.ping()) {
            Log.warn("gateway unreachable — skipping unregister for: %s", orphans);
            return;
        }
        for (String serverId : orphans) {
            try {
                if (client.unregister(serverId)) {
                    Log.ok("gateway: unregistered %s", serverId);
                }
            } catch (Exception e) {
                Log.warn("gateway: unregister %s failed — %s", serverId, e.getMessage());
            }
        }
    }
}
