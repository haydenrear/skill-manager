package dev.skillmanager.commands;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.shared.util.Fs;
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

@Command(name = "remove", aliases = "rm", description = "Remove an installed skill.")
public final class RemoveCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Skill name")
    String name;

    @Option(names = "--from", description = "Also unlink from the given agent(s)", split = ",")
    List<String> unlink;

    @Override
    public Integer call() throws Exception {
        SkillStore store = SkillStore.defaultStore();
        store.init();
        if (!store.contains(name)) {
            Log.warn("skill not found: %s", name);
            return 1;
        }

        // Capture the MCP deps this skill declared before we delete it
        // from disk — we need the names to unregister orphans from the
        // gateway after removal.
        List<McpDependency> removedDeps = store.load(name)
                .map(Skill::mcpDependencies)
                .orElse(List.of());

        store.remove(name);
        Log.ok("removed %s", name);

        // Unregister any MCP deps no other installed skill still declares.
        // Best-effort: warn and continue if the gateway is unreachable.
        if (!removedDeps.isEmpty()) {
            Set<String> stillReferenced = new HashSet<>();
            for (Skill s : store.listInstalled()) {
                for (McpDependency d : s.mcpDependencies()) stillReferenced.add(d.name());
            }
            List<String> orphans = new ArrayList<>();
            for (McpDependency d : removedDeps) {
                if (!stillReferenced.contains(d.name())) orphans.add(d.name());
            }
            if (!orphans.isEmpty()) {
                GatewayClient client = new GatewayClient(GatewayConfig.resolve(store, null));
                if (!client.ping()) {
                    Log.warn("gateway unreachable — skipping unregister for: %s", orphans);
                } else {
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
        }
        if (unlink != null) {
            for (String id : unlink) {
                Agent agent = Agent.byId(id);
                Path link = agent.skillsDir().resolve(name);
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(link)) {
                    Fs.deleteRecursive(link);
                    Log.ok("%s: unlinked %s", agent.id(), name);
                }
            }
        }
        return 0;
    }
}
