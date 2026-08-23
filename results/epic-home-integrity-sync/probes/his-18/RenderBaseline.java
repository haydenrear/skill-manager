///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES ../../../../src/main/java/**/*.java
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=warn
//DEPS org.slf4j:slf4j-api:2.0.16
//DEPS info.picocli:picocli:4.7.6
//DEPS org.yaml:snakeyaml:2.3
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2
//DEPS org.tomlj:tomlj:1.1.1
//DEPS org.apache.commons:commons-compress:1.27.1
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r
//DEPS io.modelcontextprotocol.sdk:mcp:1.1.1
//DEPS org.slf4j:slf4j-simple:2.0.16
//DEPS io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.62.0
//DEPS io.opentelemetry:opentelemetry-exporter-otlp:1.62.0

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.skillmanager.bindings.ChildHomeMaterializer;
import dev.skillmanager.shared.util.GitIgnoreRules;
import dev.skillmanager.shared.util.Rederivable;
import dev.skillmanager.store.DriftReport;
import dev.skillmanager.store.HomeDigest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HIS-18's goal measurement: what the COMMITTED baseline record becomes once a
 * unit's own declaration decides its digest.
 *
 * <h2>What this does, and what it deliberately does not</h2>
 *
 * <p>It does NOT regenerate the baseline. {@code baseline/README.md} forbids
 * that, and rightly: the homes have moved since 2026-08-19 and a regenerated
 * baseline measures the epic against itself. What it does is take the committed
 * record's own path lists and ask the PRODUCTION predicate — the same
 * {@link GitIgnoreRules} and {@link Rederivable} the digest walk now asks — which
 * of those paths would never have entered the record at all. The surviving lists
 * are rebuilt into a {@link DriftReport} and rendered through the product's own
 * {@code render()} and {@code renderDetailed()}.
 *
 * <h2>Two honest caveats, stated rather than buried</h2>
 *
 * <ul>
 *   <li>The {@code .gitignore} files and indexes consulted are the ones in the
 *       operator's root store TODAY, not the ones of 2026-08-19. A unit whose
 *       declaration changed since would be scored against the new one. The
 *       alternative — reconstructing seven units' historical ignore files — is
 *       not available, and inventing them would be worse.</li>
 *   <li>Directory-ness is inferred structurally: an entry is a directory iff
 *       another entry in the same unit's lists sits under it. A LEAF is
 *       genuinely ambiguous — an originally-empty directory is recorded with no
 *       children at all — so both readings are run and both are reported. See
 *       {@link #LEAVES_ARE_DIRECTORIES}.</li>
 * </ul>
 *
 * <p>Run from the repository root:
 * {@code jbang results/epic-home-integrity-sync/probes/his-18/RenderBaseline.java <root-home>}
 */
public class RenderBaseline {

    /**
     * Whether a recorded entry with no recorded descendants is read as a
     * DIRECTORY. The record carries paths and nothing else, so a leaf is
     * genuinely ambiguous: {@code specs/**\/states/} is a directory-only rule,
     * and an EMPTY directory is emitted with no children at all
     * ({@code emitDirectory} keeps an originally-empty directory on purpose).
     *
     * <p>Both readings are run, and BOTH numbers are reported, because picking
     * the flattering one silently is how a measurement stops being one. The
     * conservative reading (leaves are files) is the headline.
     */
    private static final boolean LEAVES_ARE_DIRECTORIES =
            Boolean.getBoolean("his18.leavesAreDirectories");

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/.skill-manager");
        Path record = Path.of("results/epic-home-integrity-sync/baseline/root-home-drift.json");

        JsonNode report = new ObjectMapper().readTree(record.toFile()).get("report");

        List<DriftReport.UnitDrift> before = new ArrayList<>();
        List<DriftReport.UnitDrift> after = new ArrayList<>();

        System.out.printf("%-24s %-7s %6s %6s   %s%n",
                "unit", "kind", "before", "after", "what the exclusion removed");
        System.out.println("-".repeat(96));

        for (JsonNode unit : report.get("units")) {
            String name = unit.get("name").asText();
            String kind = unit.get("kind").asText();
            List<String> added = strings(unit.get("addedFiles"));
            List<String> removed = strings(unit.get("removedFiles"));
            List<String> modified = strings(unit.get("modifiedFiles"));

            Path unitDir = home.resolve(kind.equals("PLUGIN") ? "plugins" : "skills").resolve(name);
            GitIgnoreRules rules = GitIgnoreRules.forUnit(unitDir);

            Set<String> all = new LinkedHashSet<>(added);
            all.addAll(removed);
            all.addAll(modified);
            Set<String> kept = keep(all, rules, LEAVES_ARE_DIRECTORIES);

            DriftReport.Change change =
                    DriftReport.Change.valueOf(unit.get("change").asText());
            before.add(new DriftReport.UnitDrift(name, kind, change,
                    unit.hasNonNull("digest") ? unit.get("digest").asText() : null,
                    added, removed, modified));
            List<String> a2 = added.stream().filter(kept::contains).toList();
            List<String> r2 = removed.stream().filter(kept::contains).toList();
            List<String> m2 = modified.stream().filter(kept::contains).toList();
            if (!a2.isEmpty() || !r2.isEmpty() || !m2.isEmpty()) {
                after.add(new DriftReport.UnitDrift(name, kind, change,
                        unit.hasNonNull("digest") ? unit.get("digest").asText() : null,
                        a2, r2, m2));
            }
            int b = all.size();
            int k = kept.size();
            System.out.printf("%-24s %-7s %6d %6d   %s%n", name, kind, b, k,
                    b == k ? "—" : (b - k) + " path(s) the unit declares generated");
        }

        DriftReport was = new DriftReport(report.get("from").asText(),
                report.get("to").asText(), before);
        DriftReport now = new DriftReport(report.get("from").asText(),
                report.get("to").asText(), after);

        System.out.println();
        line("units with any drift left", was.units().size(), now.units().size());
        line("file lines in the record", files(was), files(now));
        line("render() lines", was.render().size(), now.render().size());
        line("render() characters", chars(was.render()), chars(now.render()));
        line("renderDetailed() lines", was.renderDetailed().size(), now.renderDetailed().size());
        line("renderDetailed() characters",
                chars(was.renderDetailed()), chars(now.renderDetailed()));

        System.out.println("\n--- render() over the projected record ---");
        now.render().forEach(System.out::println);

        System.out.println("\n--- NET NEW EXCLUSIONS ON THE LIVE TREE, per unit ---");
        System.out.println("(what this change hides that Rederivable did not already hide, "
                + "TODAY, on trees that still exist — the forward-going number, which is NOT "
                + "the baseline projection above)");
        netNewExclusions(home);

        System.out.println("\n--- test_graph/ entries, as the digest counts them ---");
        entryCounts(home, Path.of(args.length > 1 ? args[1]
                : System.getProperty("user.dir") + "/.skill-manager"));
    }

    /**
     * The paths that survive the exclusion, including the rule that a directory
     * whose entire content is excluded is itself excluded
     * ({@code ChildHomeMaterializer.emitDirectory}). Applied to a fixpoint,
     * because dropping a directory can empty its parent.
     */
    private static Set<String> keep(Set<String> all, GitIgnoreRules rules, boolean leafIsDir) {
        Set<String> dirs = new LinkedHashSet<>();
        for (String p : all) {
            for (String q : all) {
                if (q.startsWith(p + "/")) { dirs.add(p); break; }
            }
        }
        Set<String> kept = new LinkedHashSet<>();
        for (String p : all) {
            if (Rederivable.isDerived(p)) continue;
            if (rules.ignores(p, dirs.contains(p) || leafIsDir)) continue;
            kept.add(p);
        }
        boolean moved = true;
        while (moved) {
            moved = false;
            for (String p : List.copyOf(kept)) {
                if (!dirs.contains(p)) continue;
                boolean any = kept.stream().anyMatch(q -> q.startsWith(p + "/"));
                if (!any) { kept.remove(p); moved = true; }
            }
        }
        return kept;
    }

    /**
     * The per-unit {@code test_graph/} entry counts the plan measured as
     * "13 and 12 against the root store's 10", re-read through the digest that
     * now applies the unit's declaration.
     */
    private static void entryCounts(Path rootHome, Path projectHome) throws Exception {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Path home : List.of(rootHome, projectHome)) {
            Path skills = home.resolve("skills");
            if (!Files.isDirectory(skills)) continue;
            try (var units = Files.list(skills)) {
                for (Path unit : units.sorted().toList()) {
                    if (!Files.isDirectory(unit.resolve("test_graph"))) continue;
                    Map<String, String> entries =
                            ChildHomeMaterializer.entryDigests(unit, HomeDigest.EXCLUDED);
                    int top = (int) entries.keySet().stream()
                            .filter(r -> r.startsWith("test_graph/"))
                            .filter(r -> r.indexOf('/', "test_graph/".length()) < 0)
                            .count();
                    counts.computeIfAbsent(unit.getFileName().toString(), k -> new int[]{-1, -1})
                            [home.equals(rootHome) ? 0 : 1] = top;
                }
            }
        }
        System.out.printf("%-24s %10s %10s%n", "unit", "root", "project");
        counts.forEach((name, c) -> System.out.printf("%-24s %10s %10s%s%n", name,
                c[0] < 0 ? "-" : c[0], c[1] < 0 ? "-" : c[1],
                c[0] >= 0 && c[1] >= 0 && c[0] != c[1] ? "   DIVERGES" : ""));
    }

    /**
     * What {@link GitIgnoreRules} hides on the LIVE tree that {@link Rederivable}
     * did not already hide. The baseline projection above scores paths that were
     * DELETED — that is why they are in {@code removedFiles} — so it is a
     * retrodiction. This is the same predicate asked about trees that are still
     * there, and it is the number a record generated today would move by.
     */
    private static void netNewExclusions(Path home) throws Exception {
        int total = 0;
        for (String kind : List.of("skills", "plugins")) {
            Path dir = home.resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try (var units = Files.list(dir)) {
                for (Path unit : units.sorted().toList()) {
                    if (!Files.isDirectory(unit, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                    Map<String, String> now =
                            ChildHomeMaterializer.entryDigests(unit, HomeDigest.EXCLUDED);
                    Map<String, String> declaredOnly =
                            ChildHomeMaterializer.declaredOnlyEntriesForReview(unit);
                    if (declaredOnly.isEmpty()) continue;
                    total += declaredOnly.size();
                    System.out.printf("  %-28s %6d entries digested, %4d NET NEW exclusions%n",
                            unit.getFileName(), now.size(), declaredOnly.size());
                    declaredOnly.keySet().stream().limit(6)
                            .forEach(r -> System.out.println("       " + r));
                    if (declaredOnly.size() > 6) {
                        System.out.println("       … " + (declaredOnly.size() - 6) + " more");
                    }
                }
            }
        }
        System.out.println("  TOTAL NET NEW EXCLUSIONS ACROSS THE STORE: " + total);
    }

    private static void line(String what, int was, int now) {
        System.out.printf("%-30s %8d -> %8d   (%s)%n", what, was, now,
                was == 0 ? "n/a" : String.format("%.1f%% removed", 100.0 * (was - now) / was));
    }

    private static int files(DriftReport r) {
        return r.units().stream().mapToInt(DriftReport.UnitDrift::fileCount).sum();
    }

    private static int chars(List<String> lines) {
        return lines.stream().mapToInt(l -> l.length() + 1).sum();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
