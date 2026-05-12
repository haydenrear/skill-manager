package dev.skillmanager.bindings;

/**
 * The four-state drift matrix for {@link ProjectionKind#MANAGED_COPY}
 * sync (#48). A pure function over three SHA-256 hashes — the
 * {@code boundHash} recorded in the ledger at last apply, the
 * current source hash, and the current dest hash — plus structural
 * "file missing" states.
 *
 * <p>The matrix:
 * <pre>
 *   source ?= bound | dest ?= bound | state
 *   --------------- | -------------- | --------------------
 *   equal           | equal          | UP_TO_DATE
 *   different       | equal          | UPGRADE_AVAILABLE
 *   equal           | different      | LOCALLY_EDITED
 *   different       | different      | CONFLICT
 *   missing         | (any)          | ORPHAN_SOURCE
 *   (any)           | missing        | ORPHAN_DEST
 * </pre>
 */
public final class SyncDecision {

    private SyncDecision() {}

    public enum State {
        /** Source and dest both match {@code boundHash}; sync is a no-op. */
        UP_TO_DATE,
        /** Source moved; dest unchanged. Sync rewrites dest from source + bumps {@code boundHash}. */
        UPGRADE_AVAILABLE,
        /** Source unchanged; dest was edited locally. Warn; {@code --force} clobbers. */
        LOCALLY_EDITED,
        /** Source AND dest both changed. Warn loudly; {@code --force} clobbers + loses local edits. */
        CONFLICT,
        /** Source is gone from the doc-repo — the binding is stale. Leave dest in place; error. */
        ORPHAN_SOURCE,
        /** Dest was deleted by the user (or git). Recreate from source on next sync. */
        ORPHAN_DEST
    }

    /**
     * Decide the sync state for one {@link ProjectionKind#MANAGED_COPY}
     * projection. Each hash may be {@code null} to signal "file missing
     * at the moment of the check."
     *
     * <p>Precedence rules:
     * <ol>
     *   <li>If {@code currentSourceHash == null} → {@link State#ORPHAN_SOURCE}
     *       (no upstream bytes — the doc-repo dropped this source).</li>
     *   <li>Else if {@code currentDestHash == null} → {@link State#ORPHAN_DEST}
     *       (project deleted the tracked file — recreate).</li>
     *   <li>Otherwise pure 2×2 over the four equal/different cells.</li>
     * </ol>
     *
     * <p>Source-orphan wins over dest-orphan because the binding is
     * fundamentally stale when the source is gone — recreating an
     * empty dest from nothing would just amplify the broken state.
     */
    public static State decide(String boundHash, String currentSourceHash, String currentDestHash) {
        if (currentSourceHash == null) return State.ORPHAN_SOURCE;
        if (currentDestHash == null) return State.ORPHAN_DEST;
        boolean sourceEqualsBound = eq(currentSourceHash, boundHash);
        boolean destEqualsBound = eq(currentDestHash, boundHash);
        if (sourceEqualsBound && destEqualsBound) return State.UP_TO_DATE;
        if (!sourceEqualsBound && destEqualsBound) return State.UPGRADE_AVAILABLE;
        if (sourceEqualsBound) return State.LOCALLY_EDITED;
        return State.CONFLICT;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
