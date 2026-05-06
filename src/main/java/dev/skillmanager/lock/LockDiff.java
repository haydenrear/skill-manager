package dev.skillmanager.lock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Difference between two {@link UnitsLock}s. {@code lock status} renders
 * one of these against the on-disk reality; {@code sync --lock} walks
 * one to drive the install set toward the target lock.
 *
 * <p>Three buckets:
 * <ul>
 *   <li>{@link #added}: in target, not in current. Need to install.</li>
 *   <li>{@link #removed}: in current, not in target. Need to uninstall.</li>
 *   <li>{@link #bumped}: present in both with the same name but different
 *       fields (typically {@code resolved_sha} drift). Need to re-sync.</li>
 * </ul>
 *
 * <p>Equality for "bumped" detection is field-by-field on
 * {@link LockedUnit}'s record components — version / installSource /
 * origin / ref / resolvedSha all matter. Kind drift never appears in
 * practice (a unit doesn't change kind across re-installs) but would
 * show up as a bump too.
 */
public record LockDiff(
        List<LockedUnit> added,
        List<LockedUnit> removed,
        List<Bump> bumped
) {
    public LockDiff {
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
        bumped = bumped == null ? List.of() : List.copyOf(bumped);
    }

    public record Bump(LockedUnit before, LockedUnit after) {}

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && bumped.isEmpty();
    }

    /**
     * Compute the diff "from current to target": what would have to change
     * to bring {@code current} into the shape of {@code target}.
     */
    public static LockDiff between(UnitsLock current, UnitsLock target) {
        Map<String, LockedUnit> currentByName = byName(current);
        Map<String, LockedUnit> targetByName = byName(target);

        List<LockedUnit> added = new ArrayList<>();
        List<LockedUnit> removed = new ArrayList<>();
        List<Bump> bumped = new ArrayList<>();

        for (var entry : targetByName.entrySet()) {
            LockedUnit t = entry.getValue();
            LockedUnit c = currentByName.get(entry.getKey());
            if (c == null) {
                added.add(t);
            } else if (!Objects.equals(c, t)) {
                bumped.add(new Bump(c, t));
            }
        }
        for (var entry : currentByName.entrySet()) {
            if (!targetByName.containsKey(entry.getKey())) {
                removed.add(entry.getValue());
            }
        }
        return new LockDiff(added, removed, bumped);
    }

    private static Map<String, LockedUnit> byName(UnitsLock lock) {
        Map<String, LockedUnit> out = new LinkedHashMap<>();
        for (LockedUnit u : lock.units()) out.put(u.name(), u);
        return out;
    }
}
