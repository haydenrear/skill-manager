import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A PUBLISHED declaration that a node damaged a home ON PURPOSE, naming the
 * exact finding it planted — the one way to tell {@code home.fixpoint.law}
 * "this refusal is the fixture, not the product" (#344).
 *
 * <h2>Why it exists</h2>
 *
 * <p>{@code HomeCloneFixtureBuilt} plants a dangling shim so that "a skipped
 * toolchain root is reported" has something to report. The law is appended to
 * every graph, runs {@code home verify}, gets exit 1 on exactly that shim, runs
 * the printed remedy (which cannot create an interpreter nobody declared) and
 * fails — on Linux. On macOS it passed only because verify could not see the
 * shim at all (#343). The law had no way to be told.
 *
 * <h2>What it is NOT</h2>
 *
 * <ul>
 *   <li>Not a directory skip. A declaration names ONE home (compared by its
 *       resolved path, never as a prefix) AND the home-relative entries of the
 *       findings planted in it. Any other finding in that home is judged exactly
 *       as it would be without the declaration.</li>
 *   <li>Not silent. The law counts declared homes as a metric and names each
 *       with its entries and reason in its log, beside
 *       {@code homesOutsideSandbox}.</li>
 *   <li>Not a free pass. A declared home on which {@code home verify} reports
 *       NONE of the declared findings fails the law: either the declaration is
 *       stale or verify has gone blind to a defect the fixture really planted —
 *       which is #343's shape, and precisely what a skip would have hidden.</li>
 * </ul>
 *
 * <h2>Wire format</h2>
 *
 * <p>Published under {@link #KEY}; one declaration per line:
 * {@code <absolute home>\t<entry>[|<entry>...]\t<reason>}. Tabs and newlines
 * rather than commas, because the law's structural home discovery splits every
 * published value on commas and newlines and would otherwise read a reason as
 * a path. The home column still starts with {@code /}, so discovery also
 * offers the home itself — harmless, it is a home the node produced.
 */
final class IntentionalDamage {

    /** The context key every declaring node publishes under. */
    static final String KEY = "intentionallyDamagedHomes";

    record Declaration(Path home, Set<String> entries, String reason) {}

    private IntentionalDamage() {}

    /** One declaration line. {@code entries} are home-relative, as verify prints them. */
    static String declare(Path home, List<String> entries, String reason) {
        if (entries.isEmpty()) throw new IllegalArgumentException("a declaration names at least one entry");
        for (String e : entries) {
            if (e.startsWith("/") || e.contains("\t") || e.contains("|") || e.contains("\n")) {
                throw new IllegalArgumentException("entry must be home-relative and plain: " + e);
            }
        }
        return home.toAbsolutePath().normalize() + "\t" + String.join("|", entries) + "\t"
                + reason.replace('\t', ' ').replace('\n', ' ');
    }

    /** Joins several declarations into one published value. */
    static String join(List<String> declarations) {
        return String.join("\n", declarations);
    }

    /** Parses every declaration in one published value; malformed lines are returned in {@code malformed}. */
    static List<Declaration> parse(String value, List<String> malformed) {
        List<Declaration> out = new ArrayList<>();
        if (value == null) return out;
        for (String line : value.split("\n")) {
            if (line.isBlank()) continue;
            String[] cols = line.split("\t", 3);
            if (cols.length < 2 || !cols[0].startsWith("/") || cols[1].isBlank()) {
                malformed.add(line);
                continue;
            }
            Set<String> entries = new LinkedHashSet<>(List.of(cols[1].split("\\|")));
            out.add(new Declaration(resolved(Path.of(cols[0])), entries,
                    cols.length > 2 ? cols[2] : "(no reason given)"));
        }
        return out;
    }

    /** Merges declarations naming the same resolved home. */
    static Map<Path, Declaration> byHome(List<Declaration> declarations) {
        Map<Path, Declaration> out = new LinkedHashMap<>();
        for (Declaration d : declarations) {
            Declaration prior = out.get(d.home());
            if (prior == null) {
                out.put(d.home(), d);
            } else {
                Set<String> entries = new LinkedHashSet<>(prior.entries());
                entries.addAll(d.entries());
                out.put(d.home(), new Declaration(d.home(), entries, prior.reason() + "; " + d.reason()));
            }
        }
        return out;
    }

    /**
     * The home-relative entries of the "do not resolve" findings in
     * {@code home verify}'s output — the {@code ✗ <indent><entry> -> <target>}
     * rows {@code HomeCommand} prints under its header.
     */
    static Set<String> unresolvedEntries(String output) {
        Set<String> out = new LinkedHashSet<>();
        boolean inSection = false;
        for (String raw : output.split("\n")) {
            String line = raw.startsWith("✗") ? raw.substring(1) : null;
            if (line == null) { inSection = false; continue; }
            if (line.contains("reference(s) in ") && line.contains(" do not resolve")) {
                inSection = true;
                continue;
            }
            if (!inSection) continue;
            String trimmed = line.strip();
            int arrow = trimmed.indexOf(" -> ");
            if (arrow <= 0 || trimmed.startsWith("complete it with:")) { inSection = false; continue; }
            out.add(trimmed.substring(0, arrow));
        }
        return out;
    }

    /**
     * The subjects of the {@code home repair} findings {@code home verify}
     * prints since OHV-2 (#339): the {@code ✗   <KIND> <subject> — <detail>}
     * rows under {@code "<n> finding(s) `home repair` reports in <home>"}.
     *
     * <p>A declaration names an entry, and a planted shape can surface in
     * either section: home-clone's {@code bin/cli/hc-venv-tool} is a dangling
     * reference in a clone and a frozen shim everywhere. The law asks "does
     * verify still report every declared entry", so both sections count.
     */
    static Set<String> repairSubjects(String output) {
        Set<String> out = new LinkedHashSet<>();
        boolean inSection = false;
        for (String raw : output.split("\n")) {
            if (!raw.startsWith("✗")) { inSection = false; continue; }
            String trimmed = raw.substring(1).strip();
            if (isRepairHeader(trimmed)) { inSection = true; continue; }
            if (!inSection) continue;
            String subject = repairFindingSubject(trimmed);
            if (subject != null) { out.add(subject); continue; }
            if (trimmed.startsWith("repair: ") || isRepairTrailer(trimmed)) continue;
            inSection = false;
        }
        return out;
    }

    /**
     * Every {@code ✗} line of verify's output that the declared entries do not
     * account for: anything outside the two finding sections, any unresolved
     * finding whose entry was not declared, and any {@code home repair} finding
     * whose subject was not declared, together with that section's header and
     * remedy lines. Empty means the refusal is exactly the planted damage and
     * nothing else.
     */
    static List<String> unexplained(String output, Set<String> declared) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        // The repair section (OHV-2). Its header and remedy lines are only
        // unexplained when some finding under them is; buffered until the
        // section ends, because the findings come between them.
        List<String> repairFrame = null;
        boolean repairUndeclared = false;
        boolean lastFindingDeclared = false;
        for (String raw : output.split("\n")) {
            if (!raw.startsWith("✗")) {
                inSection = false;
                if (repairFrame != null && repairUndeclared) out.addAll(repairFrame);
                repairFrame = null;
                continue;
            }
            String line = raw.substring(1);
            String trimmed = line.strip();
            if (repairFrame != null) {
                String subject = repairFindingSubject(trimmed);
                if (subject != null) {
                    lastFindingDeclared = declared.contains(subject);
                    if (!lastFindingDeclared) { repairUndeclared = true; out.add(trimmed); }
                    continue;
                }
                if (trimmed.startsWith("repair: ")) {
                    if (!lastFindingDeclared) out.add(trimmed);
                    continue;
                }
                if (isRepairTrailer(trimmed)) { repairFrame.add(trimmed); continue; }
                if (repairUndeclared) out.addAll(repairFrame);
                repairFrame = null;
                // falls through: this line belongs to whatever comes next
            }
            if (isRepairHeader(trimmed)) {
                inSection = false;
                repairFrame = new ArrayList<>(List.of(trimmed));
                repairUndeclared = false;
                lastFindingDeclared = false;
                continue;
            }
            if (trimmed.contains("reference(s) in ") && trimmed.contains(" do not resolve")) {
                inSection = true;
                continue;
            }
            if (inSection && trimmed.startsWith("complete it with:")) { inSection = false; continue; }
            if (inSection) {
                int arrow = trimmed.indexOf(" -> ");
                if (arrow > 0 && declared.contains(trimmed.substring(0, arrow))) continue;
            }
            out.add(trimmed);
        }
        if (repairFrame != null && repairUndeclared) out.addAll(repairFrame);
        return out;
    }

    /** {@code "<n> finding(s) `home repair` reports in <home>, …"} — verify's repair section header. */
    static boolean isRepairHeader(String trimmed) {
        return trimmed.contains("finding(s) `home repair` reports in ");
    }

    /** The section's closing lines: its remedy, and its count of what --fix cannot repair. */
    static boolean isRepairTrailer(String trimmed) {
        return trimmed.matches("\\d+ of these: complete it with: .*")
                || trimmed.matches("\\d+ cannot be repaired by .*");
    }

    /** {@code <subject>} of a {@code "<KIND> <subject> — <detail>"} row, or null. */
    static String repairFindingSubject(String trimmed) {
        int space = trimmed.indexOf(' ');
        if (space <= 0 || !trimmed.substring(0, space).matches("[A-Z][A-Z_]+")) return null;
        int dash = trimmed.indexOf(" — ", space + 1);
        if (dash <= space + 1) return null;
        return trimmed.substring(space + 1, dash);
    }

    static Path resolved(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException | RuntimeException notThere) {
            return abs;
        }
    }
}
