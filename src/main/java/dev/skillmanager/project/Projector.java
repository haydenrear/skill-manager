package dev.skillmanager.project;

import dev.skillmanager.model.AgentUnit;
import dev.skillmanager.store.SkillStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Per-agent strategy for projecting a unit's bytes into the agent's
 * tree. Each agent that consumes skills/plugins gets a {@code Projector}
 * — Claude wires through plugin and skill, Codex (v1) wires only skill.
 *
 * <p>Replaces the provisional direct-symlink calls added in ticket 08's
 * {@code SyncAgents} / {@code UnlinkAgentUnit} handlers. Those handlers
 * now iterate the {@link ProjectorRegistry} and call {@link #apply} /
 * {@link #remove} per projection.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>{@link #planProjection} is pure — given a unit, return what the
 *       agent's tree should look like for it (zero, one, or more
 *       {@link Projection}s). Empty list = "this projector has nothing
 *       to do for this unit", which is how Codex skips plugins in v1.</li>
 *   <li>{@link #apply} writes the projection. Idempotent — applying twice
 *       yields the same on-disk state.</li>
 *   <li>{@link #remove} reverses one projection. No-op when the target
 *       isn't present (uninstall walks every projector and may run after
 *       a partial install where some projections didn't land).</li>
 * </ul>
 */
public interface Projector {

    /** Stable identifier — matches {@link dev.skillmanager.agent.Agent#id()}. */
    String agentId();

    /** Where this agent's plugin entries live. May be unused for projectors that skip plugins. */
    Path pluginsDir();

    /** Where this agent's skill entries live. */
    Path skillsDir();

    /**
     * Compute the projections for {@code unit}. Most calls produce one
     * projection; a projector that doesn't know how to project a kind
     * (e.g. {@link CodexProjector} for plugins) returns an empty list.
     *
     * <p>{@code store} provides the canonical store dirs; the projector
     * resolves the source path via {@link SkillStore#unitDir}.
     */
    List<Projection> planProjection(AgentUnit unit, SkillStore store);

    /**
     * Apply one projection: ensure {@code target} resolves to the
     * contents of {@code source}. Symlink preferred; falls back to a
     * recursive copy when the filesystem refuses symlinks. Replaces an
     * existing projection at {@code target} cleanly.
     */
    void apply(Projection projection) throws IOException;

    /**
     * Reverse one projection: remove {@code target} if present. No-op
     * when absent. Does not touch {@code source} — that lives in the
     * store and is owned by {@code RemoveUnitFromStore}.
     */
    void remove(Projection projection) throws IOException;
}
