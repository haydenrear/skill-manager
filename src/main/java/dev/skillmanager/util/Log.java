package dev.skillmanager.util;

/**
 * The CLI's console.
 *
 * <h2>Two audiences, one call site</h2>
 *
 * <p>Every line this class emits is recorded in the invocation's
 * {@link RunLog}. What differs is whether it also reaches the console:
 *
 * <ul>
 *   <li>{@link #ok}, {@link #info}, {@link #step} — the verdict and the counts
 *       that make a claim checkable. Console + log.</li>
 *   <li>{@link #warn}, {@link #error} — anything the caller has to act on.
 *       Console (stderr) + log, always, in every mode.</li>
 *   <li>{@link #detail} — the per-item chatter behind a verdict: one line per
 *       (unit × agent), per file re-anchored, per plan row. Log always;
 *       console only under {@code --verbose}.</li>
 *   <li>{@link #debug} — diagnostics that are only ever wanted under
 *       {@code --verbose}. Same routing as {@code detail}, kept separate
 *       because it is stderr.</li>
 * </ul>
 *
 * <p>{@code --verbose} therefore restores exactly the console output that
 * existed before the split: {@code detail} is the only level that was demoted,
 * and under verbose it prints. Nothing is reachable ONLY through the file.
 */
public final class Log {

    private static boolean verbose = false;

    private Log() {}

    public static void setVerbose(boolean v) { verbose = v; }

    /**
     * Whether {@code --verbose} (or an embedding caller) asked for the
     * diagnostic half of the output.
     *
     * <p>Read by the CLI's fall-through failure printer, which prints a
     * refusal as a message and keeps the stack trace behind this flag, and by
     * {@link dev.skillmanager.plan.PlanPrinter}, which puts the plan back on
     * the console when it is about to ask the operator to approve it.
     */
    public static boolean isVerbose() { return verbose; }

    public static void info(String msg, Object... args) {
        console(format(msg, args));
    }

    public static void step(String msg, Object... args) {
        console("→ " + format(msg, args));
    }

    public static void ok(String msg, Object... args) {
        console("✓ " + format(msg, args));
    }

    public static void warn(String msg, Object... args) {
        consoleErr("! " + format(msg, args));
    }

    public static void error(String msg, Object... args) {
        consoleErr("✗ " + format(msg, args));
    }

    /**
     * A line behind a verdict: it goes to the run log always, and to the
     * console only under {@code --verbose}.
     *
     * <p>Use this for anything whose count is more informative than its
     * contents — "claude: synced acme-widgets" is one of twenty identical
     * sentences, and the sentence a reader wants is the twenty.
     */
    public static void detail(String msg, Object... args) {
        String line = format(msg, args);
        if (verbose) {
            System.out.println(line);
            RunLog.mirror(line);
        } else {
            RunLog.demote(line);
        }
    }

    /** {@code detail}, on stderr. */
    public static void debug(String msg, Object... args) {
        String line = "  " + format(msg, args);
        if (verbose) {
            System.err.println(line);
            RunLog.mirror(line);
        } else {
            RunLog.demote(line);
        }
    }

    /**
     * Record a line the caller printed itself (raw {@code System.out} /
     * {@code System.err}) so the run log stays a complete transcript.
     */
    public static void record(String line) {
        RunLog.mirror(line);
    }

    private static void console(String line) {
        System.out.println(line);
        RunLog.mirror(line);
    }

    private static void consoleErr(String line) {
        System.err.println(line);
        RunLog.mirror(line);
    }

    private static String format(String msg, Object... args) {
        if (args == null || args.length == 0) return msg;
        return String.format(msg, args);
    }
}
