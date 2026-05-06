package dev.skillmanager.lock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory representation of {@code units.lock.toml}. The lock is read
 * at command start, mutated in-memory via the {@code with*} helpers, and
 * written exactly once at successful commit. Compensation walks restore
 * the in-memory copy and never write a half-applied lock — that's the
 * atomicity contract pinned by {@code LockAtomicityTest}.
 *
 * <p>Schema version 1 is the v1 layout per the spec. Any future
 * non-additive change bumps the version and the reader rejects unknown
 * versions loudly (see {@link UnitsLockReader}).
 */
public record UnitsLock(int schemaVersion, List<LockedUnit> units) {

    public static final int CURRENT_SCHEMA = 1;

    public UnitsLock {
        units = units == null ? List.of() : List.copyOf(units);
    }

    public static UnitsLock empty() {
        return new UnitsLock(CURRENT_SCHEMA, List.of());
    }

    /** Look up a row by unit name. */
    public Optional<LockedUnit> get(String name) {
        for (LockedUnit u : units) {
            if (u.name().equals(name)) return Optional.of(u);
        }
        return Optional.empty();
    }

    /**
     * Upsert a row by name — replaces an existing entry with matching
     * name, or appends if absent. Returns a new {@link UnitsLock}; the
     * caller's instance is unchanged (immutable by design).
     */
    public UnitsLock withUnit(LockedUnit u) {
        Map<String, LockedUnit> by = byName();
        by.put(u.name(), u);
        return new UnitsLock(schemaVersion, new ArrayList<>(by.values()));
    }

    /** Remove a row by name. No-op if the name isn't present. */
    public UnitsLock withoutUnit(String name) {
        Map<String, LockedUnit> by = byName();
        if (by.remove(name) == null) return this;
        return new UnitsLock(schemaVersion, new ArrayList<>(by.values()));
    }

    private Map<String, LockedUnit> byName() {
        Map<String, LockedUnit> out = new LinkedHashMap<>();
        for (LockedUnit u : units) out.put(u.name(), u);
        return out;
    }
}
