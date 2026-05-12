package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.source.InstalledUnit;
import dev.skillmanager.source.UnitStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-through state for one program execution: the store + gateway, and a
 * lazily-loaded snapshot of every {@link InstalledUnit} the program might read.
 *
 * <p>Effects that mutate sources (errors set / cleared, baselines bumped after
 * a merge) call into the helpers on this class so the cache invalidates
 * exactly once per write — the next effect that reads sees fresh state without
 * each handler re-loading from disk.
 */
public final class EffectContext {

    private final SkillStore store;
    private final GatewayConfig gateway;
    private final UnitStore sourceStore;
    private Map<String, InstalledUnit> cache;

    /**
     * Plan slot — written by the {@link SkillEffect.BuildInstallPlan}
     * handler, read by every later effect that needs the plan
     * ({@link SkillEffect.RecordAuditPlan},
     * {@link SkillEffect.RunInstallPlan}). Lets us build the plan at exec
     * time (post-merge for sync) without passing it as an effect field.
     */
    private InstallPlan plan;

    /**
     * Resolved-graph slot — written by the {@link SkillEffect.ResolveGraph}
     * handler, read by every downstream effect that needs the graph
     * ({@link SkillEffect.CommitUnitsToStore}, {@link SkillEffect.BuildInstallPlan},
     * etc., when those are wired to read from ctx rather than via a
     * constructor field). The slot is empty until {@code ResolveGraph}
     * runs; sub-programs that don't resolve never observe it.
     */
    private dev.skillmanager.resolve.ResolvedGraph resolvedGraph;

    /**
     * Pre-mutation snapshot of every installed skill's MCP-dep names.
     * Captured by {@link SkillEffect.SnapshotMcpDeps} before any effect
     * mutates the store; consumed by orphan-detection effects after
     * mutations are done.
     */
    private Map<String, Set<String>> preMcpDeps;

    /** Single user-facing renderer for this program-execution tree. */
    private final ProgramRenderer renderer;

    public EffectContext(SkillStore store, GatewayConfig gateway) {
        this(store, gateway, ProgramRenderer.NOOP);
    }

    public EffectContext(SkillStore store, GatewayConfig gateway, ProgramRenderer renderer) {
        this.store = store;
        this.gateway = gateway;
        this.sourceStore = new UnitStore(store);
        this.renderer = renderer;
    }

    public SkillStore store() { return store; }
    public GatewayConfig gateway() { return gateway; }
    public UnitStore sourceStore() { return sourceStore; }
    public ProgramRenderer renderer() { return renderer; }

    public Map<String, InstalledUnit> sources() {
        if (cache == null) cache = loadAll();
        return cache;
    }

    /**
     * Look up an {@link InstalledUnit} by name — kind-agnostic. Falls back
     * to a direct {@link UnitStore#read} when the cache (built from the
     * skill-only {@code listInstalled}) misses, so plugin-kind units land
     * here too.
     */
    public Optional<InstalledUnit> source(String name) {
        InstalledUnit cached = sources().get(name);
        if (cached != null) return Optional.of(cached);
        return sourceStore.read(name);
    }

    public void invalidate() { cache = null; }

    public void setPlan(InstallPlan plan) { this.plan = plan; }
    public InstallPlan plan() { return plan; }

    public void setResolvedGraph(dev.skillmanager.resolve.ResolvedGraph graph) {
        this.resolvedGraph = graph;
    }
    public Optional<dev.skillmanager.resolve.ResolvedGraph> resolvedGraph() {
        return Optional.ofNullable(resolvedGraph);
    }

    public void setPreMcpDeps(Map<String, Set<String>> snapshot) { this.preMcpDeps = snapshot; }
    public Map<String, Set<String>> preMcpDeps() {
        return preMcpDeps == null ? Map.of() : preMcpDeps;
    }

    /**
     * Snapshot of the slots that sub-programs may write to. Use with
     * {@link #restore} to bracket a sub-program so its writes don't leak
     * into the parent — e.g. {@link SkillEffect.ResolveTransitives}'s
     * sub-{@link dev.skillmanager.app.InstallUseCase} program calls
     * {@link SkillEffect.BuildInstallPlan} which would otherwise clobber
     * the parent's plan slot before the parent's
     * {@link SkillEffect.RunInstallPlan} reads it.
     */
    public Snapshot snapshot() {
        return new Snapshot(plan, preMcpDeps);
    }

    public void restore(Snapshot s) {
        this.plan = s.plan;
        this.preMcpDeps = s.preMcpDeps;
    }

    public record Snapshot(InstallPlan plan, Map<String, Set<String>> preMcpDeps) {}

    public void addError(String skill, InstalledUnit.ErrorKind kind, String message) throws IOException {
        sourceStore.addError(skill, kind, message);
        invalidate();
    }

    public void clearError(String skill, InstalledUnit.ErrorKind kind) throws IOException {
        sourceStore.clearError(skill, kind);
        invalidate();
    }

    public void writeSource(InstalledUnit source) throws IOException {
        sourceStore.write(source);
        invalidate();
    }

    private Map<String, InstalledUnit> loadAll() {
        Map<String, InstalledUnit> out = new LinkedHashMap<>();
        try {
            for (var skill : store.listInstalled()) {
                sourceStore.read(skill.name()).ifPresent(s -> out.put(skill.name(), s));
            }
        } catch (IOException ignored) {}
        return out;
    }
}
