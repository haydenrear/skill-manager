package dev.skillmanager.project;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Central wiring for the {@link Projector}s known to skill-manager. The
 * effect handlers ({@code SyncAgents}, {@code UnlinkAgentUnit}, the
 * {@code UnprojectIfOrphan} compensation applier) iterate this list to
 * fan a unit out across every agent without coupling to the agent set.
 *
 * <p>{@link #defaultRegistry} wires {@link ClaudeProjector} and
 * {@link CodexProjector}. Tests construct registries with custom
 * projector instances pointing at temp dirs.
 */
public final class ProjectorRegistry {

    private final List<Projector> projectors;

    public ProjectorRegistry(List<Projector> projectors) {
        this.projectors = List.copyOf(projectors);
    }

    public static ProjectorRegistry defaultRegistry() {
        return new ProjectorRegistry(List.of(
                ClaudeProjector.forDefaultAgent(),
                CodexProjector.forDefaultAgent()
        ));
    }

    public List<Projector> projectors() { return projectors; }

    /**
     * Plan every projector's projections for {@code unit} and apply
     * them. Returns the flat list of projections that were actually
     * applied (each projection appears exactly once even if multiple
     * projectors agree on its target — they don't, by construction).
     */
    public List<Projection> applyAll(AgentUnit unit, SkillStore store) throws IOException {
        List<Projection> applied = new ArrayList<>();
        for (Projector p : projectors) {
            for (Projection proj : p.planProjection(unit, store)) {
                p.apply(proj);
                applied.add(proj);
            }
        }
        return applied;
    }

    /**
     * Plan + remove every projector's projection of {@code unit}.
     * Used by {@code UnlinkAgentUnit} and the {@code UnprojectIfOrphan}
     * compensation. {@code remove} is idempotent — projections that
     * never landed are no-ops.
     */
    public List<Projection> removeAll(AgentUnit unit, SkillStore store) throws IOException {
        List<Projection> removed = new ArrayList<>();
        for (Projector p : projectors) {
            for (Projection proj : p.planProjection(unit, store)) {
                p.remove(proj);
                removed.add(proj);
            }
        }
        return removed;
    }
}
