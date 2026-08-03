import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captured CLI output, plus whatever file its {@code log:} footer names.
 *
 * <p>The CLI prints a short contract on the console and demotes the per-item
 * detail to a run log, naming it in a {@code log: <path>} footer on stderr.
 * A node that asserts on demoted detail must follow that footer, or it
 * asserts against the half the detail was moved out of — which reads as
 * "the clause is missing" when the clause is fine, and, far worse, makes
 * every assertion phrased as an absence pass over a string that never
 * contained the thing in the first place.
 *
 * <p>This is the {@code sources/lib} twin of
 * {@code OnboardingSupport.plusNamedLog}, kept identical in the one respect
 * that matters: <b>a named log that cannot be read is a THROWN failure, never
 * a silent {@code ""}</b>. {@code Node.run} turns the throw into an errored
 * node naming the path, which is the only outcome that cannot be mistaken for
 * a passing absence.
 */
final class RunLogText {

    private RunLogText() {}

    private static final Pattern FOOTER = Pattern.compile("(?m)^\\s*log:\\s+(\\S+)");

    /** The path the output's {@code log:} footer names, or {@code ""}. */
    static String namedLogPath(String text) {
        if (text == null) return "";
        Matcher m = FOOTER.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /**
     * {@code console}, plus the contents of the run log it names.
     *
     * @throws IllegalStateException if a footer names a file that is empty or
     *                               unreadable — nothing below that point may
     *                               be believed either way.
     */
    static String plusNamedLog(String console) {
        if (console == null || console.isEmpty()) return "";
        String named = namedLogPath(console);
        if (named.isEmpty()) return console;
        String detail = read(Path.of(named));
        if (detail.isEmpty()) {
            throw new IllegalStateException(
                    "output named a run log at " + named + " and it is empty or unreadable — "
                            + "the demoted detail is gone, so nothing below this may be "
                            + "believed either way");
        }
        return console + "\n" + detail;
    }

    /** Read a file, or {@code ""} — a missing file is a finding for the caller. */
    static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
