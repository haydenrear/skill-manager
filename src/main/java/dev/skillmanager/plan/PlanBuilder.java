package dev.skillmanager.plan;

import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.Skill;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.tools.ToolDependency;
import dev.skillmanager.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlanBuilder {

    private final Policy policy;
    private final CliLock lock;
    /** Optional: when present, the planner does presence-checks against
     *  the gateway/CLI process PATH so {@code EnsureTool} entries for
     *  external tools (docker, brew) carry an accurate missing/present
     *  signal. {@code null} skips the check (equivalent to "assume on
     *  PATH"). */
    private final PackageManagerRuntime pmRuntime;

    public PlanBuilder(Policy policy) { this(policy, null, null); }

    public PlanBuilder(Policy policy, CliLock lock) { this(policy, lock, null); }

    public PlanBuilder(Policy policy, CliLock lock, PackageManagerRuntime pmRuntime) {
        this.policy = policy;
        this.lock = lock;
        this.pmRuntime = pmRuntime;
    }

    /** Plan for a fresh {@code add} that already resolved a full transitive graph. */
    public InstallPlan plan(ResolvedGraph graph, boolean withCli, boolean withMcp, Path cliBinDir) {
        InstallPlan plan = new InstallPlan();
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            plan.add(new PlanAction.FetchSkill(r));
        }
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            plan.add(new PlanAction.InstallSkillIntoStore(r.name(), r.skill().version()));
        }
        if (withCli || withMcp) {
            addToolEnsures(plan, graph.skills(), withCli, withMcp);
            for (ResolvedGraph.Resolved r : graph.resolved()) {
                if (withCli) addCli(plan, r.skill());
                if (withMcp) addMcp(plan, r.skill());
            }
        }
        if (withCli && hasAnyCli(graph.skills())) {
            plan.add(new PlanAction.PathHint(cliBinDir.toString()));
        }
        return plan;
    }

    /** Plan over an already-installed skill set (for {@code install} and {@code sync}). */
    public InstallPlan plan(List<Skill> skills, boolean withCli, boolean withMcp, Path cliBinDir) {
        InstallPlan plan = new InstallPlan();
        for (Skill s : skills) {
            plan.add(new PlanAction.InstallSkillIntoStore(s.name(), s.version()));
        }
        if (withCli || withMcp) {
            addToolEnsures(plan, skills, withCli, withMcp);
            for (Skill s : skills) {
                if (withCli) addCli(plan, s);
                if (withMcp) addMcp(plan, s);
            }
        }
        if (withCli && hasAnyCli(skills)) {
            plan.add(new PlanAction.PathHint(cliBinDir.toString()));
        }
        return plan;
    }

    /**
     * Collect every {@code requiredToolIds()} from CLI deps + MCP loads
     * across {@code skills}, deduplicate by tool id (accumulating
     * requesters), and emit one {@code EnsureTool} action per unique
     * tool. Section.TOOLS sits between STORE and CLI/MCP so by the time
     * CLI / MCP actions execute, every runtime ({@code uv}, {@code npx},
     * {@code docker}) is guaranteed available (or has been flagged
     * missing for an external).
     */
    private void addToolEnsures(InstallPlan plan, List<Skill> skills,
                                boolean withCli, boolean withMcp) {
        Map<String, Set<String>> bySkill = new LinkedHashMap<>();
        for (Skill s : skills) {
            if (withCli) {
                for (CliDependency cli : s.cliDependencies()) {
                    for (String tid : cli.requiredToolIds()) {
                        bySkill.computeIfAbsent(s.name(),
                                k -> new java.util.LinkedHashSet<>()).add(tid);
                    }
                }
            }
            if (withMcp) {
                for (McpDependency mcp : s.mcpDependencies()) {
                    for (String tid : mcp.load().requiredToolIds()) {
                        bySkill.computeIfAbsent(s.name(),
                                k -> new java.util.LinkedHashSet<>()).add(tid);
                    }
                }
            }
        }
        Map<String, ToolDependency> tools = ToolRegistry.collectFlat(bySkill);
        for (ToolDependency tool : tools.values()) {
            boolean missingOnPath = false;
            if (tool instanceof ToolDependency.External && pmRuntime != null) {
                missingOnPath = pmRuntime.systemPath(tool.id()) == null;
            }
            plan.add(new PlanAction.EnsureTool(tool, missingOnPath));
        }
    }

    private void addCli(InstallPlan plan, Skill s) {
        for (CliDependency dep : s.cliDependencies()) {
            if (!policy.backendAllowed(dep.backend())) {
                plan.add(new PlanAction.BlockedByPolicy(
                        PlanAction.Section.CLI,
                        "cli " + dep.name() + " (" + dep.spec() + ")",
                        "backend '" + dep.backend() + "' is not in allowed_backends"));
                continue;
            }
            if ("tar".equals(dep.backend()) && policy.requireHash()) {
                boolean anyHash = dep.install().values().stream().anyMatch(t -> t.sha256() != null);
                if (!anyHash) {
                    plan.add(new PlanAction.BlockedByPolicy(
                            PlanAction.Section.CLI,
                            "cli " + dep.name() + " (" + dep.spec() + ")",
                            "policy.require_hash: tar install target has no sha256"));
                    continue;
                }
            }
            if (lock != null) {
                var req = RequestedVersion.of(dep);
                CliLock.Entry existing = lock.get(dep.backend(), req.tool());
                if (existing != null && req.version() != null && existing.version() != null
                        && !req.version().equals(existing.version())
                        && !existing.requestedBy().contains(s.name())) {
                    plan.add(new PlanAction.CliVersionConflict(
                            s.name(), dep, req.version(), existing.version(), existing.requestedBy()));
                    continue;
                }
            }
            plan.add(new PlanAction.RunCliInstall(s.name(), dep));
        }
    }

    private void addMcp(InstallPlan plan, Skill s) {
        for (McpDependency dep : s.mcpDependencies()) {
            // Per-load-type policy gates. Loads with no policy gate
            // (npm, uv, shell) just fall through to the register step;
            // shell is flagged DANGER in PlanAction so reviewers see it.
            boolean blocked = false;
            switch (dep.load()) {
                case McpDependency.DockerLoad d -> {
                    if (!policy.dockerImageAllowed(d.image())) {
                        plan.add(new PlanAction.BlockedByPolicy(
                                PlanAction.Section.MCP,
                                "mcp docker " + dep.name() + " (" + d.image() + ")",
                                policy.allowDocker()
                                        ? "image not in allowed_docker_prefixes"
                                        : "policy.allow_docker is false"));
                        blocked = true;
                    }
                }
                case McpDependency.BinaryLoad b -> {
                    if (b.initScript() != null && !policy.allowInitScripts()) {
                        plan.add(new PlanAction.BlockedByPolicy(
                                PlanAction.Section.MCP,
                                "mcp binary " + dep.name() + " (" + b.binPath() + ")",
                                "init_script present; policy.allow_init_scripts is false"));
                        blocked = true;
                    }
                }
                case McpDependency.NpmLoad n -> { /* no policy gate */ }
                case McpDependency.UvLoad u -> { /* no policy gate */ }
                case McpDependency.ShellLoad sh -> { /* DANGER-rated; no gate yet */ }
            }
            if (blocked) continue;
            plan.add(new PlanAction.RegisterMcpServer(s.name(), dep));
        }
    }

    private boolean hasAnyCli(List<Skill> skills) {
        for (Skill s : skills) if (!s.cliDependencies().isEmpty()) return true;
        return false;
    }
}
