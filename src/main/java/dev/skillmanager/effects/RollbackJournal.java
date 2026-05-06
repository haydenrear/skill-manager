package dev.skillmanager.effects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Append-only record of {@link Compensation}s the {@link Executor}
 * accumulated while running a program. On a downstream effect failure
 * the journal is walked LIFO — newest compensation first — so the
 * inverse-of-the-most-recent runs before the inverse-of-anything-it-
 * depended-on.
 *
 * <p>Ticket-09a lands an in-memory journal. Disk persistence under
 * {@code $SKILL_MANAGER_HOME/journals/&lt;install-id&gt;/} per the spec
 * comes in 09b (when the executor wires through Install / Upgrade /
 * Uninstall) — at that point a crash mid-program needs the journal
 * to survive process death so the next CLI invocation can finish the
 * walk. For a single in-process run the in-memory list is sufficient.
 *
 * <p>Not thread-safe — programs run on the calling thread.
 */
public final class RollbackJournal {

    private final String installId;
    private final List<Compensation> entries = new ArrayList<>();

    public RollbackJournal() { this(UUID.randomUUID().toString()); }

    public RollbackJournal(String installId) { this.installId = installId; }

    public String installId() { return installId; }

    /** Append one compensation. The journal preserves insertion order. */
    public void record(Compensation c) {
        if (c == null) return;
        entries.add(c);
    }

    /** Append every compensation in order. Convenience for handler-batches. */
    public void recordAll(List<Compensation> cs) {
        if (cs == null) return;
        for (Compensation c : cs) record(c);
    }

    /** Total count of recorded compensations. */
    public int size() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    /**
     * Compensations in LIFO order (newest first) — the order they should
     * be applied during rollback. The returned list is a defensive copy;
     * walking it does not mutate the journal.
     */
    public List<Compensation> pendingLifo() {
        List<Compensation> out = new ArrayList<>(entries);
        Collections.reverse(out);
        return out;
    }

    /**
     * Drop everything — call after a successful program commits. The
     * journal exists to roll back partial state, so once the program
     * finishes cleanly there's nothing to keep.
     */
    public void clear() { entries.clear(); }
}
