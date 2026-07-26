package dev.skillmanager.bindings;

/**
 * How a parent-store unit directory is materialized into a child Skill
 * Manager home (a project child home, or a {@code harness instantiate
 * --child-home-dir} target).
 *
 * <ul>
 *   <li>{@link #LINK} — symlink the child home entry at the parent store
 *       directory, falling back to a recursive copy only when the filesystem
 *       rejects symlinks. Cheap, but every write through the child home lands
 *       in the parent store.</li>
 *   <li>{@link #COPY} — materialize an independent copy so an agent working
 *       in the child home can edit its units without touching the shared
 *       parent store. See {@link ChildHomeMaterializer} for the exact
 *       isolation guarantee, which is strong but not absolute.</li>
 * </ul>
 *
 * <p>The chosen mode is persisted by name in each unit's materialization
 * record, so a future mode (for example a git checkout of the unit source)
 * can be added here and recognized on disk without inferring intent from the
 * shape of the materialized tree.
 */
public enum MaterializationMode {
    LINK,
    COPY
}
