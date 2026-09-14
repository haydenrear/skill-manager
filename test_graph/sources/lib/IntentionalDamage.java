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
     * Every {@code ✗} line of verify's output that the declared entries do not
     * account for: anything outside the unresolved section, and any finding in
     * it whose entry was not declared. Empty means the refusal is exactly the
     * planted damage and nothing else.
     */
    static List<String> unexplained(String output, Set<String> declared) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String raw : output.split("\n")) {
            if (!raw.startsWith("✗")) { inSection = false; continue; }
            String line = raw.substring(1);
            String trimmed = line.strip();
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
        return out;
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
