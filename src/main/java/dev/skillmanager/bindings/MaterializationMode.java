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
 *   <li>{@link #CHECKOUT} — the child unit is its own git clone of the unit's
 *       repository. Edits are commits, so they can be pushed back to the unit's
 *       own trunk (see {@code UnitPublisher}) instead of only existing inside
 *       one child home.</li>
 * </ul>
 *
 * <p>The chosen mode is persisted by name in each unit's materialization
 * record, which is what makes {@link #CHECKOUT} safe to mix with the others:
 * the mode is <b>per unit</b>, and a later pass that was asked for
 * {@link #COPY} recognizes a recorded checkout and leaves it alone rather than
 * inferring intent from the shape of the tree it finds. Without that, the next
 * {@code project resolve} would delete the checkout — and with it every commit
 * in it that had not been pushed yet.
 *
 * <p>Recorded {@link #CHECKOUT} therefore <em>sticks</em>. To take a unit back
 * to a copy, remove the child unit directory and re-resolve; that is the same
 * gesture the hold-back message already asks for, and it is deliberately
 * something a human does on purpose rather than something a routine resolve can
 * do by accident.
 */
public enum MaterializationMode {
    LINK,
    COPY,
    CHECKOUT
}
