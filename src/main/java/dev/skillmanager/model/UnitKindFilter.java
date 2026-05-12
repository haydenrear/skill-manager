package dev.skillmanager.model;

/**
 * Constrains which {@link UnitKind} a coordinate is willing to bind to
 * during resolution. Set explicitly when the coord uses a kind prefix
 * (e.g. {@code skill:foo} → {@link #SKILL_ONLY},
 * {@code plugin:bar} → {@link #PLUGIN_ONLY}); otherwise {@link #ANY}
 * lets the resolver pick whichever kind it finds first.
 *
 * <p>Used by the resolver (ticket 04) to disambiguate registry hits.
 * Stored on {@link UnitReference} at parse time so the constraint
 * survives the trip from manifest → planner → resolver.
 */
public enum UnitKindFilter {
    ANY,
    SKILL_ONLY,
    PLUGIN_ONLY,
    DOC_ONLY,
    HARNESS_ONLY;

    public boolean accepts(UnitKind kind) {
        return switch (this) {
            case ANY -> true;
            case SKILL_ONLY -> kind == UnitKind.SKILL;
            case PLUGIN_ONLY -> kind == UnitKind.PLUGIN;
            case DOC_ONLY -> kind == UnitKind.DOC;
            case HARNESS_ONLY -> kind == UnitKind.HARNESS;
        };
    }

    public static UnitKindFilter forKind(UnitKind kind) {
        return switch (kind) {
            case SKILL -> SKILL_ONLY;
            case PLUGIN -> PLUGIN_ONLY;
            case DOC -> DOC_ONLY;
            case HARNESS -> HARNESS_ONLY;
        };
    }
}
