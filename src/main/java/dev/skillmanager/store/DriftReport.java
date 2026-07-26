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
    @JsonPropertyOrder({"name", "kind", "change", "addedFiles", "removedFiles", "modifiedFiles"})
    public record UnitDrift(
            String name,
            String kind,
            Change change,
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
                drifts.add(new UnitDrift(name, now.kind(), Change.ADDED,
                        sorted(now.entries().keySet()), List.of(), List.of()));
                continue;
            }
            if (now == null) {
                drifts.add(new UnitDrift(name, was.kind(), Change.REMOVED,
                        List.of(), sorted(was.entries().keySet()), List.of()));
                continue;
            }
            if (was.digest().equals(now.digest())) continue;
            drifts.add(modified(name, now.kind(), was.entries(), now.entries()));
        }
        drifts.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new DriftReport(before.digest(), after.digest(), drifts);
    }

    private static UnitDrift modified(String name, String kind,
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
        return new UnitDrift(name, kind, Change.MODIFIED, added, removed, changed);
    }

    private static List<String> sorted(Set<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    /** Human-readable rendering, the form {@code home drift --show} prints. */
    public List<String> render() {
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
