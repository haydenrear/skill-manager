package dev.skillmanager.lifecycle;

import dev.skillmanager.agent.Agent;
import dev.skillmanager.lock.CliInstallRecorder;
import dev.skillmanager.lock.CliLock;
import dev.skillmanager.mcp.GatewayClient;
import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.mcp.InstallResult;
import dev.skillmanager.mcp.McpWriter;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.model.SkillReference;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.plan.PlanBuilder;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;
import dev.skillmanager.sync.SkillSync;
import dev.skillmanager.tools.ToolInstallRecorder;
import dev.skillmanager.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The post-mutation pipeline shared by install, sync, and upgrade. One service
 * so install and sync can't drift on what "fully apply this skill set" means.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Resolve transitive {@code skill_references} that arrived in updated
 *       TOMLs (recursively installs missing deps).</li>
 *   <li>Tool + CLI dep installer via the planner.</li>
 *   <li>MCP register every skill's deps with the gateway.</li>
 *   <li>Refresh agent symlinks + MCP-config entries.</li>
 *   <li>Unregister MCP servers no surviving skill still declares (diffed
 *       against the {@code preMcpDeps} snapshot the caller passed in).</li>
 * </ol>
 *
 * <p>Steps 2-4 are idempotent; safe to re-run on every command. Failures
 * during MCP register set {@link SkillSource.Status#NEEDS_RECONCILE} on
 * the affected skill so the next command's reconciler retries.
 */
public final class SkillSideEffects {

    private SkillSideEffects() {}

    public record Result(
            Map<McpWriter.ConfigChange, List<String>> agentConfigChanges,
            List<String> orphanMcpServersUnregistered,
            List<String> skillsWithErrors) {
        public static Result empty() {
            return new Result(new EnumMap<>(McpWriter.ConfigChange.class), new ArrayList<>(), new ArrayList<>());
        }
    }

    public static Map<String, Set<String>> snapshotMcpDeps(SkillStore store) throws IOException {
        Map<String, Set<String>> snapshot = new LinkedHashMap<>();
        for (Skill s : store.listInstalled()) {
            Set<String> names = new HashSet<>();
            for (McpDependency d : s.mcpDependencies()) names.add(d.name());
            snapshot.put(s.name(), names);
        }
        return snapshot;
    }

    /** Recursively install transitive skill_references missing from the store. */
    public static List<String> resolveMissingTransitives(SkillStore store, List<Skill> skills) {
        List<String> installed = new ArrayList<>();
        for (Skill s : skills) {
            for (SkillReference ref : s.skillReferences()) {
                String coord = referenceToCoord(ref, store, s.name());
                String name = ref.name() != null ? ref.name() : guessName(coord);
                if (name == null || name.isBlank() || store.contains(name)) continue;
                Log.step("transitive: %s declares unmet skill_reference %s — installing", s.name(), coord);
                if (invokeInstall(coord, ref.version()) == 0) installed.add(name);
                else Log.warn("transitive install of %s failed — declared by %s", coord, s.name());
            }
        }
        return installed;
    }

    public static Result runPostUpdate(SkillStore store, GatewayConfig gw,
                                       Map<String, Set<String>> preMcpDeps,
                                       boolean withMcp,
                                       boolean withAgents) throws IOException {
        Result out = Result.empty();
        SkillSourceStore sources = new SkillSourceStore(store);
        List<Skill> live = store.listInstalled();

        Policy policy = Policy.load(store);
        CliLock lock = CliLock.load(store);
        PackageManagerRuntime pmRuntime = new PackageManagerRuntime(store);
        InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                .plan(live, true, true, store.cliBinDir());
        ToolInstallRecorder.run(plan, store);
        CliInstallRecorder.run(plan, store);

        if (withMcp) {
            GatewayClient gwClient = new GatewayClient(gw);
            if (!gwClient.ping()) {
                Log.warn("gateway unreachable — recording GATEWAY_UNAVAILABLE on each skill with MCP deps");
                for (Skill s : live) {
                    if (s.mcpDependencies().isEmpty()) continue;
                    sources.addError(s.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE,
                            "gateway at " + gw.baseUrl() + " unreachable");
                    out.skillsWithErrors.add(s.name());
                }
            } else {
                McpWriter writer = new McpWriter(gw);
                List<InstallResult> results = writer.registerAll(live);
                writer.printInstallResults(results);
                for (Skill s : live) {
                    if (s.mcpDependencies().isEmpty()) continue;
                    sources.clearError(s.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE);
                }
                for (InstallResult r : results) {
                    String owner = ownerOf(live, r.serverId());
                    if (owner == null) continue;
                    if (InstallResult.Status.ERROR.code.equals(r.status())) {
                        sources.addError(owner, SkillSource.ErrorKind.MCP_REGISTRATION_FAILED,
                                r.serverId() + ": " + r.message());
                        out.skillsWithErrors.add(owner);
                    } else {
                        sources.clearError(owner, SkillSource.ErrorKind.MCP_REGISTRATION_FAILED);
                    }
                }
            }
        }

        if (withAgents) {
            McpWriter writer = new McpWriter(gw);
            for (Agent agent : Agent.all()) {
                try { new SkillSync(store).sync(agent, live, true); }
                catch (Exception e) { Log.warn("%s: skill sync failed — %s", agent.id(), e.getMessage()); }
                try {
                    McpWriter.ConfigChange change = writer.writeAgentEntry(agent);
                    out.agentConfigChanges
                            .computeIfAbsent(change, k -> new ArrayList<>())
                            .add(agent.id() + " (" + agent.mcpConfigPath() + ")");
                } catch (Exception e) {
                    Log.warn("%s: mcp config update failed — %s", agent.id(), e.getMessage());
                }
            }
        }

        if (withMcp && !preMcpDeps.isEmpty()) {
            List<String> orphans = computeOrphans(preMcpDeps, live);
            if (!orphans.isEmpty()) {
                GatewayClient client = new GatewayClient(gw);
                if (!client.ping()) {
                    Log.warn("gateway unreachable — skipping orphan unregister for: %s", orphans);
                } else {
                    for (String serverId : orphans) {
                        try {
                            if (client.unregister(serverId)) {
                                Log.ok("gateway: unregistered orphan %s", serverId);
                                out.orphanMcpServersUnregistered.add(serverId);
                            }
                        } catch (Exception e) {
                            Log.warn("gateway: unregister %s failed — %s", serverId, e.getMessage());
                        }
                    }
                }
            }
        }
        return out;
    }

    public static void printAgentConfigSummary(Result result, String mcpUrl) {
        var added = result.agentConfigChanges.getOrDefault(McpWriter.ConfigChange.ADDED, List.of());
        var updated = result.agentConfigChanges.getOrDefault(McpWriter.ConfigChange.UPDATED, List.of());
        if (added.isEmpty() && updated.isEmpty()) return;
        System.out.println();
        System.out.println("agent MCP configs:");
        for (String a : added) System.out.println("  ADDED    " + a + "  → " + mcpUrl);
        for (String a : updated) System.out.println("  UPDATED  " + a + "  → " + mcpUrl);
        System.out.println();
        System.out.println("ACTION_REQUIRED: Restart Claude / Codex for the virtual-mcp-gateway entry");
        System.out.println("to take effect — without a restart the agent will not see any MCP tools.");
        System.out.println();
    }

    private static String ownerOf(List<Skill> skills, String mcpServerId) {
        for (Skill s : skills) {
            for (McpDependency d : s.mcpDependencies()) {
                if (d.name().equals(mcpServerId)) return s.name();
            }
        }
        return null;
    }

    private static List<String> computeOrphans(Map<String, Set<String>> preMcpDeps, List<Skill> postSkills) {
        Set<String> stillReferenced = new HashSet<>();
        for (Skill s : postSkills) {
            for (McpDependency d : s.mcpDependencies()) stillReferenced.add(d.name());
        }
        Set<String> previouslyReferenced = new HashSet<>();
        for (Set<String> deps : preMcpDeps.values()) previouslyReferenced.addAll(deps);
        List<String> orphans = new ArrayList<>();
        for (String name : previouslyReferenced) {
            if (!stillReferenced.contains(name)) orphans.add(name);
        }
        return orphans;
    }

    private static String referenceToCoord(SkillReference ref, SkillStore store, String parentSkillName) {
        if (ref.isLocal()) {
            java.nio.file.Path rel = java.nio.file.Path.of(ref.path());
            if (rel.isAbsolute()) return rel.toString();
            return store.skillDir(parentSkillName).resolve(rel).normalize().toString();
        }
        return ref.version() != null && !ref.version().isBlank()
                ? ref.name() + "@" + ref.version()
                : ref.name();
    }

    private static String guessName(String coord) {
        if (coord == null) return null;
        String s = coord;
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(0, at);
        if (s.startsWith("file:")) s = s.substring("file:".length());
        if (s.startsWith("github:")) {
            int slash = s.lastIndexOf('/');
            return slash >= 0 ? s.substring(slash + 1) : null;
        }
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        int slash = s.lastIndexOf('/');
        String tail = slash >= 0 ? s.substring(slash + 1) : s;
        return tail.isBlank() ? null : tail;
    }

    private static int invokeInstall(String coord, String version) {
        try {
            dev.skillmanager.commands.InstallCommand inst =
                    new dev.skillmanager.commands.InstallCommand();
            inst.source = coord;
            inst.version = version;
            Integer rc = inst.call();
            return rc == null ? 1 : rc;
        } catch (Exception e) {
            Log.warn("transitive install of %s threw: %s", coord, e.getMessage());
            return 1;
        }
    }
}
