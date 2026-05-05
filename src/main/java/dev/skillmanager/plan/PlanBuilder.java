package dev.skillmanager.plan;

import dev.skillmanager.lock.CliLock;
import dev.skillmanager.lock.RequestedVersion;
import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.model.CliDependency;
import dev.skillmanager.model.McpDependency;
import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitReference;
import dev.skillmanager.pm.PackageManagerRuntime;
import dev.skillmanager.policy.Policy;
import dev.skillmanager.resolve.ResolvedGraph;
import dev.skillmanager.tools.ToolDependency;
import dev.skillmanager.tools.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
            plan.add(new PlanAction.FetchUnit(r));
        }
        for (ResolvedGraph.Resolved r : graph.resolved()) {
            plan.add(new PlanAction.InstallUnitIntoStore(r.name(), r.version(), r.unit().kind()));
        }
        if (withCli || withMcp) {
            addToolEnsures(plan, graph.units(), withCli, withMcp);
            for (ResolvedGraph.Resolved r : graph.resolved()) {
                if (withCli) addCli(plan, r.unit());
                if (withMcp) addMcp(plan, r.unit());
            }
        }
        if (withCli && hasAnyCli(graph.units())) {
            plan.add(new PlanAction.PathHint(cliBinDir.toString()));
        }
        return plan;
    }

    /**
     * Plan over an already-resolved unit set (for {@code install} and {@code sync}).
     * Throws {@link PlanCycleException} if the reference graph has a cycle.
     * Emits {@link PlanAction.InstallUnitIntoStore} actions in dependency order
     * (dependencies before dependents).
     */
    public InstallPlan plan(List<AgentUnit> units, boolean withCli, boolean withMcp, Path cliBinDir) {
        Optional<List<String>> cycle = detectCycle(units);
        if (cycle.isPresent()) throw new PlanCycleException(cycle.get());
        List<AgentUnit> ordered = topoSort(units);
        InstallPlan plan = new InstallPlan();
        for (AgentUnit u : ordered) {
            plan.add(new PlanAction.InstallUnitIntoStore(u.name(), u.version(), u.kind()));
        }
        if (withCli || withMcp) {
            addToolEnsures(plan, ordered, withCli, withMcp);
            for (AgentUnit u : ordered) {
                if (withCli) addCli(plan, u);
                if (withMcp) addMcp(plan, u);
            }
        }
        if (withCli && hasAnyCli(ordered)) {
            plan.add(new PlanAction.PathHint(cliBinDir.toString()));
        }
        return plan;
    }

    // --------------------------------------------------------- cycle detection

    /**
     * Detects cycles in the reference graph of {@code units}. Returns the
     * offending chain (e.g. {@code [a, b, a]}) if a cycle exists, or
     * {@link Optional#empty()} if the graph is acyclic.
     *
     * <p>Only references to units IN the provided list are followed; external
     * references (units not in the list) are ignored.
     */
    public static Optional<List<String>> detectCycle(List<AgentUnit> units) {
        Map<String, AgentUnit> byName = new LinkedHashMap<>();
        for (AgentUnit u : units) byName.put(u.name(), u);

        Set<String> gray = new LinkedHashSet<>();  // nodes currently on the DFS stack
        Set<String> black = new HashSet<>();        // fully processed nodes

        for (AgentUnit u : units) {
            if (!black.contains(u.name())) {
                List<String> cycle = dfsCycle(u.name(), byName, gray, black);
                if (cycle != null) return Optional.of(cycle);
            }
        }
        return Optional.empty();
    }

    private static List<String> dfsCycle(String name, Map<String, AgentUnit> byName,
                                          Set<String> gray, Set<String> black) {
        gray.add(name);
        AgentUnit u = byName.get(name);
        if (u != null) {
            for (UnitReference ref : u.references()) {
                String refName = ref.name();
                if (refName == null || !byName.containsKey(refName)) continue;
                if (gray.contains(refName)) {
                    List<String> chain = new ArrayList<>(gray);
                    chain.add(refName);
                    return chain;
                }
                if (!black.contains(refName)) {
                    List<String> cycle = dfsCycle(refName, byName, gray, black);
                    if (cycle != null) return cycle;
                }
            }
        }
        gray.remove(name);
        black.add(name);
        return null;
    }

    // --------------------------------------------------------- topological sort

    /**
     * Returns {@code units} in dependency order: if A references B (and B is
     * in the list), B appears before A. Assumes the graph is acyclic — call
     * {@link #detectCycle} first.
     */
    private static List<AgentUnit> topoSort(List<AgentUnit> units) {
        Map<String, AgentUnit> byName = new LinkedHashMap<>();
        for (AgentUnit u : units) byName.put(u.name(), u);
        Set<String> visited = new HashSet<>();
        List<AgentUnit> result = new ArrayList<>(units.size());
        for (AgentUnit u : units) {
            if (!visited.contains(u.name())) {
                topoVisit(u.name(), byName, visited, result);
            }
        }
        return result;
    }

    private static void topoVisit(String name, Map<String, AgentUnit> byName,
                                   Set<String> visited, List<AgentUnit> result) {
        visited.add(name);
        AgentUnit u = byName.get(name);
        if (u != null) {
            for (UnitReference ref : u.references()) {
                String refName = ref.name();
                if (refName != null && byName.containsKey(refName) && !visited.contains(refName)) {
                    topoVisit(refName, byName, visited, result);
                }
            }
            result.add(u);
        }
    }

    // --------------------------------------------------------- categorization

    /**
     * Returns display lines summarising what kinds of units and deps the plan
     * involves. Lines prefixed with {@code +} describe what gets installed;
     * lines prefixed with {@code !} flag categories that deserve review.
     * Display-only this ticket — gating wires up in ticket 12.
     */
    public static List<String> categorize(List<AgentUnit> units, InstallPlan plan) {
        List<String> lines = new ArrayList<>();

        List<String> skillNames = new ArrayList<>();
        List<String> agentNames = new ArrayList<>();
        for (AgentUnit u : units) {
            if (u.kind() == UnitKind.SKILL) skillNames.add(u.name());
            else agentNames.add(u.name());
        }
        if (!skillNames.isEmpty()) lines.add("+ SKILLS   " + String.join(", ", skillNames));
        if (!agentNames.isEmpty()) lines.add("+ AGENTS   " + String.join(", ", agentNames));

        boolean hasCli = false;
        boolean hasMcp = false;
        boolean hasHooks = false;
        for (PlanAction a : plan.actions()) {
            if (a instanceof PlanAction.RunCliInstall) hasCli = true;
            if (a instanceof PlanAction.RegisterMcpServer rm) {
                hasMcp = true;
                if (rm.dep().load() instanceof McpDependency.ShellLoad) hasHooks = true;
                if (rm.dep().load() instanceof McpDependency.BinaryLoad b && b.initScript() != null) hasHooks = true;
            }
        }
        if (hasCli) lines.add("! CLI");
        if (hasMcp) lines.add("! MCP");
        if (hasHooks) lines.add("! HOOKS");

        return lines;
    }

    // --------------------------------------------------------- private helpers

    /**
     * Collect every {@code requiredToolIds()} from CLI deps + MCP loads
     * across {@code units}, deduplicate by tool id (accumulating
     * requesters), and emit one {@code EnsureTool} action per unique
     * tool. Section.TOOLS sits between STORE and CLI/MCP so by the time
     * CLI / MCP actions execute, every runtime ({@code uv}, {@code npx},
     * {@code docker}) is guaranteed available (or has been flagged
     * missing for an external).
     */
    private void addToolEnsures(InstallPlan plan, List<AgentUnit> units,
                                boolean withCli, boolean withMcp) {
        Map<String, Set<String>> byUnit = new LinkedHashMap<>();
        for (AgentUnit u : units) {
            if (withCli) {
                for (CliDependency cli : u.cliDependencies()) {
                    for (String tid : cli.requiredToolIds()) {
                        byUnit.computeIfAbsent(u.name(),
                                k -> new LinkedHashSet<>()).add(tid);
                    }
                }
            }
            if (withMcp) {
                for (McpDependency mcp : u.mcpDependencies()) {
                    for (String tid : mcp.load().requiredToolIds()) {
                        byUnit.computeIfAbsent(u.name(),
                                k -> new LinkedHashSet<>()).add(tid);
                    }
                }
            }
        }
        Map<String, ToolDependency> tools = ToolRegistry.collectFlat(byUnit);
        for (ToolDependency tool : tools.values()) {
            boolean missingOnPath = false;
            if (tool instanceof ToolDependency.External && pmRuntime != null) {
                missingOnPath = pmRuntime.systemPath(tool.id()) == null;
            }
            plan.add(new PlanAction.EnsureTool(tool, missingOnPath));
        }
    }

    private void addCli(InstallPlan plan, AgentUnit u) {
        for (CliDependency dep : u.cliDependencies()) {
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
                        && !existing.requestedBy().contains(u.name())) {
                    plan.add(new PlanAction.CliVersionConflict(
                            u.name(), dep, req.version(), existing.version(), existing.requestedBy()));
                    continue;
                }
            }
            plan.add(new PlanAction.RunCliInstall(u.name(), dep));
        }
    }

    private void addMcp(InstallPlan plan, AgentUnit u) {
        for (McpDependency dep : u.mcpDependencies()) {
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
                case McpDependency.UvLoad u2 -> { /* no policy gate */ }
                case McpDependency.ShellLoad sh -> { /* DANGER-rated; no gate yet */ }
            }
            if (blocked) continue;
            plan.add(new PlanAction.RegisterMcpServer(u.name(), dep));
        }
    }

    private boolean hasAnyCli(List<AgentUnit> units) {
        for (AgentUnit u : units) if (!u.cliDependencies().isEmpty()) return true;
        return false;
    }
}
