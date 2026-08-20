package dev.skillmanager.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The difference between two {@link HomeDigest}s, per unit and per file.
 *
 * <p>This is the thing an agent has to read. It exists because "sync succeeded"
 * is not enough information to keep working: if a skill the agent is currently
 * following moved, everything it decided on the strength of that skill is
 * suspect, and nothing about a successful sync says so.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"from", "to", "units"})
public record DriftReport(String from, String to, List<UnitDrift> units) {

    public DriftReport {
        units = units == null ? List.of() : List.copyOf(units);
    }

    public enum Change { ADDED, REMOVED, MODIFIED }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "kind", "change", "digest",
            "addedFiles", "removedFiles", "modifiedFiles"})
    public record UnitDrift(
            String name,
            String kind,
            Change change,
            String digest,
            List<String> addedFiles,
            List<String> removedFiles,
            List<String> modifiedFiles
    ) {
        public UnitDrift {
            addedFiles = addedFiles == null ? List.of() : List.copyOf(addedFiles);
            removedFiles = removedFiles == null ? List.of() : List.copyOf(removedFiles);
            modifiedFiles = modifiedFiles == null ? List.of() : List.copyOf(modifiedFiles);
        }

        public String label() {
            return (kind == null ? "unit" : kind.toLowerCase()) + ":" + name;
        }

        /**
         * The unit's whole-tree hash AFTER the change — the thing the operator
         * asked for in place of the file list, and the thing this record was
         * already being handed and throwing away. Null for a REMOVED unit,
         * which has no "after" to hash.
         */
        public String shortDigest() {
            if (digest != null && !digest.isBlank()) {
                return digest.length() > 8 ? digest.substring(0, 8) : digest;
            }
            // Two different absences, and collapsing them is how a record
            // written before this field existed reads as a unit that was
            // DELETED. Measured on the committed baseline fixture, whose seven
            // units all predate the field: every one of them rendered `gone`.
            return change == Change.REMOVED ? "gone" : "no-digest";
        }

        /** How many paths this unit's drift touches. */
        public int fileCount() {
            return addedFiles.size() + removedFiles.size() + modifiedFiles.size();
        }

        /** Every path this unit's drift touches, sorted. */
        public List<String> allFiles() {
            Set<String> all = new TreeSet<>(addedFiles);
            all.addAll(removedFiles);
            all.addAll(modifiedFiles);
            return List.copyOf(all);
        }
    }

    public boolean isEmpty() { return units.isEmpty(); }

    public Set<String> unitNames() {
        return units.stream().map(UnitDrift::name)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Diff {@code before} against {@code after}.
     *
     * <p>A null {@code before} yields an empty report, not "everything is new".
     * The first digest a home ever records has nothing to be compared against, and
     * calling that drift would gate the very first launch on acknowledging a
     * change nobody made.
     */
    public static DriftReport between(HomeDigest before, HomeDigest after) {
        if (before == null || after == null) {
            return new DriftReport(
                    before == null ? null : before.digest(),
                    after == null ? null : after.digest(),
                    List.of());
        }
        List<UnitDrift> drifts = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>(before.unitNames());
        names.addAll(after.unitNames());
        for (String name : names) {
            HomeDigest.UnitDigest was = before.unit(name).orElse(null);
            HomeDigest.UnitDigest now = after.unit(name).orElse(null);
            if (was == null && now == null) continue;
            if (was == null) {
                drifts.add(new UnitDrift(name, now.kind(), Change.ADDED, now.digest(),
                        sorted(now.entries().keySet()), List.of(), List.of()));
                continue;
            }
            if (now == null) {
                drifts.add(new UnitDrift(name, was.kind(), Change.REMOVED, null,
                        List.of(), sorted(was.entries().keySet()), List.of()));
                continue;
            }
            if (was.digest().equals(now.digest())) continue;
            drifts.add(modified(name, now.kind(), now.digest(), was.entries(), now.entries()));
        }
        drifts.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new DriftReport(before.digest(), after.digest(), drifts);
    }

    private static UnitDrift modified(String name, String kind, String digest,
                                      Map<String, String> was, Map<String, String> now) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String rel : new TreeSet<>(now.keySet())) {
            String before = was.get(rel);
            if (before == null) added.add(rel);
            else if (!before.equals(now.get(rel))) changed.add(rel);
        }
        for (String rel : new TreeSet<>(was.keySet())) {
            if (!now.containsKey(rel)) removed.add(rel);
        }
        return new UnitDrift(name, kind, Change.MODIFIED, digest, added, removed, changed);
    }

    private static List<String> sorted(Set<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    /**
     * The bounded rollup: one line per changed unit, plus one total.
     *
     * <h2>Why this is not the per-file list any more</h2>
     *
     * <p>MEASURED on the operator's root home: one pending record, 7 units,
     * 87,054 bytes. The old rendering emitted one line per changed FILE — 889
     * lines, ~87,200 characters, roughly 21,800 tokens — and re-emitted the
     * whole block on every {@code project sync}, every {@code exec} launch gate
     * and every {@code home drift}. One unit, {@code spec-double-compiler},
     * was 776 of the 889 lines on its own.
     *
     * <p>That is the decision an agent needs stated in the space it takes to
     * state it: <em>which units moved, and are they the ones I was following</em>.
     * The per-file list answers a different question, one nobody was asking on
     * every pass, and it was crowding out the answer to the first.
     *
     * <p>The digest is what replaces it, and it was already here: the report
     * was being handed {@code HomeDigest.UnitDigest.digest()} for every unit
     * and dropping it on the floor.
     *
     * <h2>This is a default, not a deletion</h2>
     *
     * <p>{@link #renderDetailed()} still produces exactly what this used to,
     * byte for byte, and the JSON surface is untouched. A collapse that made
     * the paths unreachable would be trading one unusable surface for another.
     */
    public List<String> render() {
        if (units.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        int files = 0;
        for (UnitDrift unit : units) {
            files += unit.fileCount();
            lines.add("%-9s %s  %s  (%d file%s)".formatted(
                    unit.change().name().toLowerCase(),
                    unit.label(),
                    unit.shortDigest(),
                    unit.fileCount(),
                    unit.fileCount() == 1 ? "" : "s"));
        }
        // Parenthesised deliberately: `.formatted` binds tighter than `+`, so
        // without these the call applies to the LAST literal only and the first
        // one keeps its raw `%d unit%s` text. Caught by the rollup test.
        lines.add(("%d unit%s, %d file%s changed — `home drift --detail` for the paths, "
                + "`home drift --json` for the data").formatted(
                units.size(), units.size() == 1 ? "" : "s",
                files, files == 1 ? "" : "s"));
        return List.copyOf(lines);
    }

    /**
     * The per-file rendering, unchanged: one line per added, removed and
     * modified path, under a header per unit.
     *
     * <p>Reachable on demand, and byte-identical to what {@link #render()}
     * produced before it was bounded. An operator who wants the paths gets
     * exactly the paths they got before.
     */
    public List<String> renderDetailed() {
        List<String> lines = new ArrayList<>();
        for (UnitDrift unit : units) {
            lines.add(unit.change().name().toLowerCase() + "  " + unit.label());
            for (String rel : unit.addedFiles()) lines.add("    + " + rel);
            for (String rel : unit.removedFiles()) lines.add("    - " + rel);
            for (String rel : unit.modifiedFiles()) lines.add("    ~ " + rel);
        }
        return List.copyOf(lines);
    }
}
