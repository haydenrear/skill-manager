package dev.skillmanager.bindings;

import dev.skillmanager.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * How much a sync held back in child homes, counted rather than narrated.
 *
 * <h2>The output this replaces</h2>
 *
 * <p>Every unit a child home declined to refresh, every CLI it provisions
 * itself, and every unit it keeps after a project stopped depending on it was
 * a {@code Log.warn} — one line each, on the console, in a command that walks
 * every child home of every registered project. Measured on a root home with
 * five projects: twenty-five such lines in one {@code skill-manager sync},
 * none of them actionable, all of them true.
 *
 * <p>They are not warnings. "This child home has local changes so I left it
 * alone" is the mechanism working: the alternative is deleting an agent's
 * edits. A reader needs to know it HAPPENED and where to look — not to read
 * the roll call.
 *
 * <p>So the per-item lines are {@link Log#detail}, which the run log always
 * records and {@code --verbose} still prints, and this renders the one line
 * that says how much there was. Nothing is reachable only through the file.
 *
 * <h2>Static, deliberately</h2>
 *
 * <p>These lines are emitted from three classes across two packages, none of
 * which shares a context object with the others, and the CLI is one shot per
 * process. Threading a tally through {@code ChildHomeMaterializer},
 * {@code ProjectChildHomeScaffolder} and their callers would be a wide change
 * to carry a counter. {@link Log} already keeps per-invocation state on the
 * same reasoning.
 */
public final class ChildHomeTally {

    private static int heldBack;
    private static int selfProvisionedCli;
    private static int keptAfterUndeclared;
    private static final Set<String> homes = new LinkedHashSet<>();

    private ChildHomeTally() {}

    /** A unit left as-is because refreshing it would overwrite local work. */
    public static void heldBack(String home) { heldBack++; note(home); }

    /** A CLI the child home provisions itself, kept rather than re-linked. */
    public static void selfProvisionedCli(String home) { selfProvisionedCli++; note(home); }

    /** A unit kept although the project no longer declares it. */
    public static void keptAfterUndeclared(String home) { keptAfterUndeclared++; note(home); }

    private static void note(String home) {
        if (home != null && !home.isBlank()) homes.add(home);
    }

    /** Start of a command. */
    public static void reset() {
        heldBack = 0; selfProvisionedCli = 0; keptAfterUndeclared = 0; homes.clear();
    }

    public static boolean isEmpty() {
        return heldBack == 0 && selfProvisionedCli == 0 && keptAfterUndeclared == 0;
    }

    /**
     * One line, or nothing when there was nothing to say.
     *
     * <p>Deliberately {@link Log#info} and not {@link Log#warn}: none of it
     * requires the reader to do anything. The units that DO — a home that
     * cannot be refreshed at all, a unit whose changes must be published
     * first — keep their own warnings at their own call sites.
     */
    public static void render() {
        if (isEmpty()) return;
        StringBuilder b = new StringBuilder("child homes: ");
        boolean first = true;
        if (heldBack > 0) {
            b.append(heldBack).append(" unit(s) left as-is (local changes)");
            first = false;
        }
        if (keptAfterUndeclared > 0) {
            if (!first) b.append(", ");
            b.append(keptAfterUndeclared).append(" kept after the project stopped declaring them");
            first = false;
        }
        if (selfProvisionedCli > 0) {
            if (!first) b.append(", ");
            b.append(selfProvisionedCli).append(" self-provisioned cli(s) kept");
        }
        b.append(" across ").append(homes.size()).append(" home(s)");
        Log.info("%s — `--verbose` or the run log names each one", b);
    }
}
