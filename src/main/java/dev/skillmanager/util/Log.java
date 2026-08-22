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
    private static boolean jsonMode = false;
    private static String lastError = null;

    private Log() {}

    public static void setVerbose(boolean v) { verbose = v; }

    /**
     * Declare that this invocation's <b>stdout is a machine-readable
     * document</b>, so every human line this class emits goes to stderr
     * instead.
     *
     * <h2>The defect this closes (#235)</h2>
     *
     * <p>{@code --json} is a promise about stdout: one parseable document and
     * nothing else. It was enforced nowhere. Any library code beneath a
     * {@code --json} command that called {@link #info}, {@link #ok} or
     * {@link #step} wrote a sentence onto the same stream — and the consumer,
     * which is a script, got a parse error instead of a verdict.
     *
     * <p>MEASURED, and note the exit code:
     *
     * <pre>
     * $ skill-manager home close-out --home &lt;wt&gt; --into &lt;proj&gt; --json
     * exit=0
     * home sync --dry-run: waiting for /…/proj-home — another skill-manager process holds this home's lock
     * {"home":"/…","into":"/…","safe":true,"exitCode":0,"blockers":[],"units":[]}
     *
     * $ python3 -c 'import json,sys; json.load(sys.stdin)' &lt; stdout
     * json.decoder.JSONDecodeError: Expecting value: line 1 column 1 (char 0)
     * </pre>
     *
     * <p>The command SUCCEEDED. The wait line is HomeLock announcing that it
     * queued behind a peer — correct behaviour, added by HIS-11 (#186), on the
     * wrong stream. {@code close-change.sh} parses that stdout, could not, and
     * advised {@code --force}: discarding the work the gate had just refused to
     * destroy.
     *
     * <h2>Why a latch here rather than a fix at the call site</h2>
     *
     * <p>The call site was {@code HomeLock.announceWait}. Fixing it there fixes
     * one sentence. Every other {@code Log.info} under any of the thirty
     * commands that declare {@code --json} is the same defect waiting, and a
     * new one is written every time somebody adds a progress line to a shared
     * code path — which is precisely how this one arrived. The stream a human
     * line belongs on is a property of the INVOCATION, and this is the one
     * place that knows it.
     *
     * <p>{@code BuildCommand} had already solved this for itself by
     * redirecting {@link System#out} to {@code FileDescriptor.err} for the
     * duration of its program. That is the same decision, taken privately by
     * one command; this makes it the rule.
     */
    public static void setJsonMode(boolean v) { jsonMode = v; }

    /** Whether stdout is reserved for a machine-readable document. */
    public static boolean isJsonMode() { return jsonMode; }

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
        String line = format(msg, args);
        lastError = line;
        consoleErr("✗ " + line);
    }

    /**
     * The last sentence {@link #error} printed, or {@code null}.
     *
     * <p>Exists so the {@code --json} failure envelope can carry the SAME
     * sentence the command already put on stderr rather than composing a
     * second one. Two renderings of one refusal that can disagree is the
     * defect #235 is about; this is the cheapest way to make them the same
     * string by construction.
     *
     * <p>Per-invocation, cleared by the CLI, and deliberately not part of any
     * command's logic — nothing branches on it.
     */
    public static String lastError() { return lastError; }

    /** Forget the last error. Called once per CLI invocation. */
    public static void clearLastError() { lastError = null; }

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
            if (jsonMode) System.err.println(line);
            else System.out.println(line);
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

    /**
     * <b>The bound on an enumerated failure.</b>
     *
     * <p>Twelve entries, then a count and the log path. An error that prints
     * nothing and only names a file is worse than what we started with — the
     * caller cannot tell a typo from a catastrophe without a second command —
     * and an error that prints three hundred lines is what this change exists
     * to fix. Twelve is chosen because the two readings a caller acts on
     * differently are "one thing broke" and "everything broke", and both are
     * distinguishable well inside a dozen entries; twelve also keeps one
     * failing block inside a half screen, so a SECOND failing block in the same
     * run is still visible without scrolling. Past that the list is something
     * you grep rather than read, and the log is where you grep it.
     *
     * <p>Every entry reaches the run log regardless — this bounds the console,
     * never the record.
     *
     * @param indent prefix for each entry, e.g. {@code "    "}
     * @param items  the entries, in the order they should be read
     */
    public static void errorList(String indent, java.util.List<String> items) {
        if (items == null || items.isEmpty()) return;
        int shown = Math.min(ERROR_SAMPLE, items.size());
        for (int i = 0; i < shown; i++) error("%s%s", indent, items.get(i));
        if (items.size() <= shown) return;
        java.nio.file.Path log = RunLog.path();
        for (int i = shown; i < items.size(); i++) RunLog.demote(indent + items.get(i));
        // Re-read: the demote() calls above may have created the file.
        if (log == null) log = RunLog.path();
        error("%s… %d more%s", indent, items.size() - shown,
                log == null ? "" : " — all " + items.size() + " in " + log);
    }

    /** How many entries of an enumerated failure reach the console. */
    public static final int ERROR_SAMPLE = 12;

    private static void console(String line) {
        // Under --json stdout belongs to the document, not to us.
        if (jsonMode) System.err.println(line);
        else System.out.println(line);
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
