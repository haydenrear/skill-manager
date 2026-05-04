package dev.skillmanager.effects;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes effects against the real filesystem / gateway / registry. Every
 * effect type is routed to the existing service code — this class is the
 * single seam where "side effect" becomes "actual mutation," so install /
 * sync / upgrade can build different programs but converge on the same
 * mutation surface.
 */
public final class LiveInterpreter implements ProgramInterpreter {

    private final SkillStore store;
    private final SkillSourceStore sources;

    public LiveInterpreter(SkillStore store) {
        this.store = store;
        this.sources = new SkillSourceStore(store);
    }

    @Override
    public <R> R run(Program<R> program) {
        List<EffectReceipt> receipts = new ArrayList<>();
        for (SkillEffect effect : program.effects()) {
            try {
                receipts.add(execute(effect));
            } catch (Exception e) {
                Log.warn("effect %s failed: %s", effect.getClass().getSimpleName(), e.getMessage());
                receipts.add(EffectReceipt.failed(effect, e.getMessage()));
            }
        }
        return program.decoder().decode(receipts);
    }

    private EffectReceipt execute(SkillEffect effect) throws IOException {
        return switch (effect) {
            case SkillEffect.ResolveTransitives e -> resolveTransitives(e);
            case SkillEffect.InstallToolsAndCli e -> installToolsAndCli(e);
            case SkillEffect.RegisterMcp e -> registerMcp(e);
            case SkillEffect.UnregisterMcpOrphan e -> unregisterOrphan(e);
            case SkillEffect.SyncAgents e -> syncAgents(e);
        };
    }

    private EffectReceipt resolveTransitives(SkillEffect.ResolveTransitives e) {
        List<String> installed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Skill s : e.skills()) {
            for (SkillReference ref : s.skillReferences()) {
                String coord = referenceToCoord(ref, store, s.name());
                String name = ref.name() != null ? ref.name() : guessName(coord);
                if (name == null || name.isBlank() || store.contains(name)) continue;
                Log.step("transitive: %s declares unmet skill_reference %s — installing", s.name(), coord);
                if (invokeInstall(coord, ref.version()) == 0) installed.add(name);
                else failed.add(coord);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("installed", installed);
        facts.put("failed", failed);
        return failed.isEmpty()
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, failed.size() + " transitive install(s) failed");
    }

    private EffectReceipt installToolsAndCli(SkillEffect.InstallToolsAndCli e) throws IOException {
        Policy policy = Policy.load(store);
        CliLock lock = CliLock.load(store);
        PackageManagerRuntime pmRuntime = new PackageManagerRuntime(store);
        InstallPlan plan = new PlanBuilder(policy, lock, pmRuntime)
                .plan(e.skills(), true, true, store.cliBinDir());
        ToolInstallRecorder.run(plan, store);
        CliInstallRecorder.run(plan, store);
        return EffectReceipt.ok(e, Map.of("skills", e.skills().size()));
    }

    private EffectReceipt registerMcp(SkillEffect.RegisterMcp e) throws IOException {
        if (!new GatewayClient(e.gateway()).ping()) {
            for (Skill s : e.skills()) {
                if (s.mcpDependencies().isEmpty()) continue;
                sources.addError(s.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE,
                        "gateway at " + e.gateway().baseUrl() + " unreachable");
            }
            return EffectReceipt.skipped(e, "gateway unreachable");
        }

        McpWriter writer = new McpWriter(e.gateway());
        List<InstallResult> results = writer.registerAll(e.skills());
        writer.printInstallResults(results);

        for (Skill s : e.skills()) {
            if (s.mcpDependencies().isEmpty()) continue;
            sources.clearError(s.name(), SkillSource.ErrorKind.GATEWAY_UNAVAILABLE);
        }
        List<String> errored = new ArrayList<>();
        for (InstallResult r : results) {
            String owner = ownerOf(e.skills(), r.serverId());
            if (owner == null) continue;
            if (InstallResult.Status.ERROR.code.equals(r.status())) {
                sources.addError(owner, SkillSource.ErrorKind.MCP_REGISTRATION_FAILED,
                        r.serverId() + ": " + r.message());
                errored.add(owner);
            } else {
                sources.clearError(owner, SkillSource.ErrorKind.MCP_REGISTRATION_FAILED);
            }
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("registered", results.size());
        facts.put("errored", errored);
        return errored.isEmpty()
                ? EffectReceipt.ok(e, facts)
                : EffectReceipt.partial(e, facts, errored.size() + " skill(s) had MCP errors");
    }

    private EffectReceipt unregisterOrphan(SkillEffect.UnregisterMcpOrphan e) {
        GatewayClient client = new GatewayClient(e.gateway());
        if (!client.ping()) {
            return EffectReceipt.skipped(e, "gateway unreachable");
        }
        try {
            if (client.unregister(e.serverId())) {
                Log.ok("gateway: unregistered orphan %s", e.serverId());
                return EffectReceipt.ok(e, Map.of("serverId", e.serverId()));
            }
            return EffectReceipt.skipped(e, "not registered");
        } catch (Exception ex) {
            return EffectReceipt.failed(e, ex.getMessage());
        }
    }

    private EffectReceipt syncAgents(SkillEffect.SyncAgents e) {
        McpWriter writer = new McpWriter(e.gateway());
        Map<McpWriter.ConfigChange, List<String>> changes = new LinkedHashMap<>();
        for (Agent agent : Agent.all()) {
            try { new SkillSync(store).sync(agent, e.skills(), true); }
            catch (Exception ex) { Log.warn("%s: skill sync failed — %s", agent.id(), ex.getMessage()); }
            try {
                McpWriter.ConfigChange change = writer.writeAgentEntry(agent);
                changes.computeIfAbsent(change, k -> new ArrayList<>())
                        .add(agent.id() + " (" + agent.mcpConfigPath() + ")");
            } catch (Exception ex) {
                Log.warn("%s: mcp config update failed — %s", agent.id(), ex.getMessage());
            }
        }
        return EffectReceipt.ok(e, Map.of("agentConfigChanges", changes));
    }

    private static String ownerOf(List<Skill> skills, String mcpServerId) {
        for (Skill s : skills) {
            for (McpDependency d : s.mcpDependencies()) {
                if (d.name().equals(mcpServerId)) return s.name();
            }
        }
        return null;
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
