package dev.skillmanager.bindings;

/**
 * What to do when a {@link Projection}'s {@code destPath} is already
 * occupied at materialization time.
 *
 * <p>Defaults are chosen per use-site, not globally:
 * {@link #RENAME_EXISTING} is appropriate for doc-repo-style bindings
 * that target arbitrary user files (the project's pre-existing
 * {@code CLAUDE.md} should be preserved as a backup), while
 * {@link #ERROR} is appropriate for whole-unit bindings (a stray dir
 * at {@code <root>/plugins/<name>} should not be silently shadowed).
 */
public enum ConflictPolicy {
    /** Refuse to materialize if {@code destPath} already exists. */
    ERROR,
    /** Rename the existing {@code destPath} to a timestamped backup; record the rename in the ledger. */
    RENAME_EXISTING,
    /** Leave {@code destPath} alone, log and continue. No projection emitted. */
    SKIP,
    /** Delete {@code destPath} unconditionally. Opt-in only; never a default. */
    OVERWRITE
}
