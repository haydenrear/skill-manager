package dev.skillmanager.bindings;

import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent editor for the skill-manager-owned imports section of
 * a markdown file. Used by {@link ProjectionKind#IMPORT_DIRECTIVE}
 * materialize / unmaterialize.
 *
 * <p>Format (per #48):
 * <pre>
 * &lt;!-- skill-manager:imports start --&gt;
 * # skill-manager-imports
 *
 * &#64;docs/agents/review-stance.md
 * &#64;docs/agents/build-instructions.md
 * &lt;!-- skill-manager:imports end --&gt;
 * </pre>
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>skill-manager owns everything <em>between</em> the markers;
 *       outside is user-owned and never touched.</li>
 *   <li>{@code # skill-manager-imports} is part of the managed
 *       content — added on first insertion, removed when the last
 *       import is removed.</li>
 *   <li>If the markers don't exist, {@link #upsertLine} appends a
 *       fresh managed section at end-of-file.</li>
 *   <li>If a removal leaves zero import lines, the whole managed
 *       section (markers + heading + surrounding blanks) is removed
 *       by {@link #removeLine}.</li>
 *   <li>Lines that don't match the expected shape ({@code @<path>}
 *       or the heading) are preserved as "unknown imports" and
 *       returned via {@link #unknownLines}; sync uses them to warn
 *       the user.</li>
 * </ul>
 *
 * <p>Operates on raw string content — caller does file I/O.
 */
public final class ManagedImports {

    private ManagedImports() {}

    public static final String START_MARKER = "<!-- skill-manager:imports start -->";
    public static final String END_MARKER = "<!-- skill-manager:imports end -->";
    public static final String HEADING = "# skill-manager-imports";

    /**
     * Ensure {@code importLine} (the literal {@code @path} text,
     * without leading whitespace) is present inside the managed
     * section. Creates the section at end-of-file if absent.
     * Idempotent: a line already present is left alone.
     */
    public static String upsertLine(String content, String importLine) {
        String line = normalize(importLine);
        Parsed parsed = parse(content);
        if (parsed.bodyLines.contains(line)) {
            // Already present — re-render to normalize body whitespace.
            return render(parsed);
        }
        parsed.bodyLines.add(line);
        return render(parsed);
    }

    /**
     * Ensure {@code importLine} is NOT present inside the managed
     * section. If removing it leaves no import lines, drops the
     * entire managed section. Idempotent: removing an absent line
     * is a no-op.
     */
    public static String removeLine(String content, String importLine) {
        String line = normalize(importLine);
        Parsed parsed = parse(content);
        parsed.bodyLines.remove(line);
        if (parsed.bodyLines.isEmpty()) {
            // No remaining import lines — drop the whole section.
            parsed.hasSection = false;
        }
        return render(parsed);
    }

    /**
     * Return the import lines currently inside the managed section.
     * Used by sync to detect "unknown imports" (lines that don't
     * correspond to any known binding on this machine).
     */
    public static List<String> currentImports(String content) {
        return parse(content).bodyLines;
    }

    /**
     * Return non-import lines found inside the managed section
     * (e.g. user-written {@code # notes} hand-added between the
     * markers). Sync warns about these.
     */
    public static List<String> unknownLines(String content) {
        return parse(content).unknownLines;
    }

    // ============================================================ internals

    private static class Parsed {
        boolean hasSection;
        /** {@code pre} = content before the start marker (verbatim, user-owned). */
        String pre = "";
        /** {@code post} = content after the end marker (verbatim, user-owned). */
        String post = "";
        /** Import {@code @<path>} lines inside the managed section, in order. */
        List<String> bodyLines = new ArrayList<>();
        /** Anything else (besides heading + blank lines + markers) inside the section. */
        List<String> unknownLines = new ArrayList<>();
    }

    private static String normalize(String line) {
        return line == null ? "" : line.trim();
    }

    private static Parsed parse(String content) {
        Parsed out = new Parsed();
        if (content == null || content.isEmpty()) return out;

        int startIdx = content.indexOf(START_MARKER);
        if (startIdx < 0) {
            out.pre = content;
            return out;
        }
        int afterStart = startIdx + START_MARKER.length();
        // Skip the newline directly after the start marker if present —
        // we'll re-emit it on render so round-trips are clean.
        int bodyStart = afterStart;
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\n') bodyStart++;

        int endIdx = content.indexOf(END_MARKER, bodyStart);
        if (endIdx < 0) {
            // Unterminated section — treat the whole tail as user-owned
            // so we don't accidentally swallow text outside our control.
            out.pre = content;
            return out;
        }
        int afterEnd = endIdx + END_MARKER.length();
        if (afterEnd < content.length() && content.charAt(afterEnd) == '\n') afterEnd++;

        out.hasSection = true;
        // pre = everything before the start marker (preserve user trailing newlines).
        out.pre = content.substring(0, startIdx);
        // post = everything after the end marker (and its newline).
        out.post = afterEnd <= content.length() ? content.substring(afterEnd) : "";

        // Walk the body between bodyStart and endIdx.
        String body = content.substring(bodyStart, endIdx);
        for (String raw : body.split("\n", -1)) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.equals(HEADING)) continue;
            if (l.startsWith("@")) {
                out.bodyLines.add(l);
            } else {
                out.unknownLines.add(l);
            }
        }
        return out;
    }

    private static String render(Parsed parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append(parsed.pre);
        if (parsed.hasSection || !parsed.bodyLines.isEmpty()) {
            // If pre doesn't end with a newline, add one so the marker
            // starts cleanly on its own line.
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            // A blank line above the section if there's any content before it.
            if (parsed.pre.length() > 0 && !parsed.pre.endsWith("\n\n")) sb.append('\n');
            sb.append(START_MARKER).append('\n');
            sb.append(HEADING).append('\n').append('\n');
            for (String l : parsed.bodyLines) sb.append(l).append('\n');
            // Preserve unknown lines verbatim (sync warns about them).
            for (String l : parsed.unknownLines) sb.append(l).append('\n');
            sb.append(END_MARKER).append('\n');
        }
        sb.append(parsed.post);
        return sb.toString();
    }
}
