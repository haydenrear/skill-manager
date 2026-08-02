package dev.skillmanager.util;

public final class Log {

    private static boolean verbose = false;

    private Log() {}

    public static void setVerbose(boolean v) { verbose = v; }

    /**
     * Whether {@code --verbose} (or an embedding caller) asked for the
     * diagnostic half of the output.
     *
     * <p>Read by the CLI's fall-through failure printer, which prints a
     * refusal as a message and keeps the stack trace behind this flag.
     */
    public static boolean isVerbose() { return verbose; }

    public static void info(String msg, Object... args) {
        System.out.println(format(msg, args));
    }

    public static void step(String msg, Object... args) {
        System.out.println("→ " + format(msg, args));
    }

    public static void ok(String msg, Object... args) {
        System.out.println("✓ " + format(msg, args));
    }

    public static void warn(String msg, Object... args) {
        System.err.println("! " + format(msg, args));
    }

    public static void error(String msg, Object... args) {
        System.err.println("✗ " + format(msg, args));
    }

    public static void debug(String msg, Object... args) {
        if (verbose) System.err.println("  " + format(msg, args));
    }

    private static String format(String msg, Object... args) {
        if (args == null || args.length == 0) return msg;
        return String.format(msg, args);
    }
}
