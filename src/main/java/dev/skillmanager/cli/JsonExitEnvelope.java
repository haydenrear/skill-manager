package dev.skillmanager.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The safety net that makes {@code --json} a promise instead of a habit:
 * <b>a {@code --json} invocation that fails always leaves one parseable JSON
 * document on stdout, whatever went wrong.</b>
 *
 * <h2>The hole this closes (#235)</h2>
 *
 * <p>{@code --json} is declared by thirty commands. Each renders its own
 * document on the paths its author thought about, and on the paths they did
 * not, stdout is empty and the reason is a sentence on stderr. Sixteen such
 * paths were enumerated while writing this, including two <em>adjacent catch
 * blocks in one method</em> — {@code home close-out} answers a
 * {@code NotAHomeException} with a JSON error and a {@code FrozenHomeException}
 * with nothing at all. Nobody wrote that difference on purpose; the second
 * catch was simply added later.
 *
 * <p>Fixing sixteen sites leaves the seventeenth to whoever writes it next.
 * This makes the contract structural: the CLI counts the bytes the command put
 * on stdout, and if a {@code --json} run is about to exit non-zero having
 * written none, the CLI writes the document the command did not.
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It does not fire on <b>success</b>. A command that exits 0 having printed
 * nothing is a different defect ({@code harness rm} with no matching bindings
 * does exactly that) and a {@code {"error":…}} envelope would be a lie about
 * it. That gap is recorded rather than papered over.
 *
 * <p>It does not <b>replace</b> a command's own document. Whenever the command
 * printed one, the net sees bytes and stays out of the way — so a richer,
 * command-specific payload always wins over this generic one.
 */
public final class JsonExitEnvelope {

    private static PrintStream realOut;
    private static CountingStream counting;

    private JsonExitEnvelope() {}

    /** stdout, wrapped so we can ask whether the command wrote to it. */
    private static final class CountingStream extends PrintStream {
        private final ByteArrayOutputStream unused = new ByteArrayOutputStream(0);
        private volatile boolean wrote;

        CountingStream(PrintStream delegate) {
            super(delegate, true, StandardCharsets.UTF_8);
        }

        @Override public void write(int b) { wrote = true; super.write(b); }

        @Override public void write(byte[] buf, int off, int len) {
            if (len > 0) wrote = true;
            super.write(buf, off, len);
        }
    }

    /**
     * Start counting stdout for this invocation. Idempotent and cheap; only
     * called when {@code --json} was actually requested.
     */
    public static void arm() {
        if (counting != null) return;
        realOut = System.out;
        counting = new CountingStream(realOut);
        System.setOut(counting);
    }

    /** Whether the command has put any byte on stdout. */
    public static boolean wroteAnything() {
        return counting != null && counting.wrote;
    }

    /** Put the real stdout back. Safe to call when never armed. */
    public static void disarm() {
        if (counting == null) return;
        System.out.flush();
        System.setOut(realOut);
        counting = null;
        realOut = null;
    }

    /**
     * Write the envelope the failing command did not.
     *
     * @param exitCode the non-zero code the invocation is about to return
     * @param error    a stable machine-readable reason, e.g. {@code home_locked}
     * @param message  the human sentence, which is whatever the command already
     *                 said on stderr — repeated here rather than re-derived, so
     *                 the two renderings cannot disagree
     */
    public static void emit(int exitCode, String error, String message) {
        StringBuilder json = new StringBuilder(128);
        json.append("{\"error\":").append(quote(error))
                .append(",\"message\":").append(quote(message))
                .append(",\"exitCode\":").append(exitCode)
                .append('}');
        PrintStream out = counting != null ? counting : System.out;
        out.println(json);
        out.flush();
    }

    /**
     * JSON string escaping that handles control characters.
     *
     * <p>The four hand-rolled {@code esc()} copies this CLI already carries
     * escape only {@code \\} and {@code "}, so any message with a newline in it
     * produces invalid JSON — which is the same class of defect as emitting no
     * JSON at all, and this envelope exists precisely to be the thing that
     * always parses. A refusal message containing a path with a newline is
     * unlikely; an envelope that is only conditionally valid is not worth
     * shipping.
     */
    static String quote(String raw) {
        if (raw == null) return "null";
        StringBuilder b = new StringBuilder(raw.length() + 8).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
