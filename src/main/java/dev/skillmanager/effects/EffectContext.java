package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.plan.InstallPlan;
import dev.skillmanager.source.SkillSource;
import dev.skillmanager.source.SkillSourceStore;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-through state for one program execution: the store + gateway, and a
 * lazily-loaded snapshot of every {@link SkillSource} the program might read.
 *
 * <p>Effects that mutate sources (errors set / cleared, baselines bumped after
 * a merge) call into the helpers on this class so the cache invalidates
 * exactly once per write — the next effect that reads sees fresh state without
 * each handler re-loading from disk.
 */
public final class EffectContext {

    private final SkillStore store;
    private final GatewayConfig gateway;
    private final SkillSourceStore sourceStore;
    private Map<String, SkillSource> cache;

    /**
     * Plan slot — written by the {@link SkillEffect.BuildInstallPlan}
     * handler, read by every later effect that needs the plan
     * ({@link SkillEffect.RecordAuditPlan},
     * {@link SkillEffect.RunInstallPlan}). Lets us build the plan at exec
     * time (post-merge for sync) without passing it as an effect field.
     */
    private InstallPlan plan;

    /**
     * Pre-mutation snapshot of every installed skill's MCP-dep names.
     * Captured by {@link SkillEffect.SnapshotMcpDeps} before any effect
     * mutates the store; consumed by orphan-detection effects after
     * mutations are done.
     */
    private Map<String, Set<String>> preMcpDeps;

    public EffectContext(SkillStore store, GatewayConfig gateway) {
        this.store = store;
        this.gateway = gateway;
        this.sourceStore = new SkillSourceStore(store);
    }

    public SkillStore store() { return store; }
    public GatewayConfig gateway() { return gateway; }
    public SkillSourceStore sourceStore() { return sourceStore; }

    public Map<String, SkillSource> sources() {
        if (cache == null) cache = loadAll();
        return cache;
    }

    public Optional<SkillSource> source(String name) {
        return Optional.ofNullable(sources().get(name));
    }

    public void invalidate() { cache = null; }

    public void setPlan(InstallPlan plan) { this.plan = plan; }
    public InstallPlan plan() { return plan; }

    public void setPreMcpDeps(Map<String, Set<String>> snapshot) { this.preMcpDeps = snapshot; }
    public Map<String, Set<String>> preMcpDeps() {
        return preMcpDeps == null ? Map.of() : preMcpDeps;
    }

    public void addError(String skill, SkillSource.ErrorKind kind, String message) throws IOException {
        sourceStore.addError(skill, kind, message);
        invalidate();
    }

    public void clearError(String skill, SkillSource.ErrorKind kind) throws IOException {
        sourceStore.clearError(skill, kind);
        invalidate();
    }

    public void writeSource(SkillSource source) throws IOException {
        sourceStore.write(source);
        invalidate();
    }

    private Map<String, SkillSource> loadAll() {
        Map<String, SkillSource> out = new LinkedHashMap<>();
        try {
            for (var skill : store.listInstalled()) {
                sourceStore.read(skill.name()).ifPresent(s -> out.put(skill.name(), s));
            }
        } catch (IOException ignored) {}
        return out;
    }
}
