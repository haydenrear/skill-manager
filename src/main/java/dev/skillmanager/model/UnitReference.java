package dev.skillmanager.model;

/**
 * A transitive reference from one unit to another. Replaces the legacy
 * {@code SkillReference}: same name/version/path projection (so
 * existing callers compile), plus a parsed {@link Coord} and an
 * explicit {@link UnitKindFilter} for heterogeneous reference walks.
 *
 * <p>Both {@link PluginUnit} and {@link SkillUnit} carry
 * {@code List<UnitReference>}. The resolver (ticket 04) consumes the
 * {@link #coord()} via exhaustive {@code switch}; the legacy
 * accessors ({@link #name()}, {@link #version()}, {@link #path()},
 * {@link #isLocal()}, {@link #isRegistry()}) keep the existing
 * install/upgrade flow working without ticket-by-ticket churn at every
 * call site.
 */
public record UnitReference(Coord coord, UnitKindFilter kindFilter) {

    public UnitReference {
        if (coord == null) throw new IllegalArgumentException("coord must not be null");
        if (kindFilter == null) kindFilter = UnitKindFilter.ANY;
    }

    /** Build a reference, deriving {@link UnitKindFilter} from the coord shape. */
    public static UnitReference of(Coord coord) {
        return new UnitReference(coord, derivedFilter(coord));
    }

    /** Parse {@code coord} and wrap with the derived filter. */
    public static UnitReference parse(String coord) {
        return of(Coord.parse(coord));
    }

    /**
     * Legacy factory matching the old {@code SkillReference.registry}.
     * Accepts {@code name} or {@code name@version} and produces a
     * {@link Coord.Bare} with {@link UnitKindFilter#ANY}. Returns
     * {@code null} on blank input — same behavior as before.
     */
    public static UnitReference registry(String coord) {
        if (coord == null || coord.isBlank()) return null;
        return of(Coord.parse(coord));
    }

    /** Build a local-path reference matching the legacy three-arg constructor. */
    public static UnitReference local(String path) {
        if (path == null || path.isBlank()) return null;
        return of(new Coord.Local(path, path));
    }

    /**
     * Legacy three-arg builder used by {@code SkillParser.parseReferences}
     * for inline-table references (e.g. {@code [[skill_references]] name = "x" version = "1.0"}).
     * Picks {@link Coord.Local} when {@code path} is non-blank, otherwise
     * {@link Coord.Bare}.
     */
    public static UnitReference legacy(String name, String version, String path) {
        if (path != null && !path.isBlank()) return local(path);
        if (name == null || name.isBlank()) return null;
        String raw = version == null || version.isBlank() ? name : name + "@" + version;
        return new UnitReference(new Coord.Bare(raw, name, version), UnitKindFilter.ANY);
    }

    // -------------------------------------------------- legacy projections

    /** Registry name, when the coord names one. {@code null} for git/local refs. */
    public String name() {
        return nameOf(coord);
    }

    private static String nameOf(Coord c) {
        return switch (c) {
            case Coord.Bare b -> b.name();
            case Coord.Kinded k -> k.name();
            case Coord.DirectGit g -> null;
            case Coord.Local l -> null;
            // Sub-element bindings address a unit; legacy callers see the
            // unit's name. The element selector is only consumed by the
            // bind layer.
            case Coord.SubElement s -> nameOf(s.unitCoord());
        };
    }

    /** Pinned version when present in the coord; {@code null} otherwise. */
    public String version() {
        return versionOf(coord);
    }

    private static String versionOf(Coord c) {
        return switch (c) {
            case Coord.Bare b -> b.version();
            case Coord.Kinded k -> k.version();
            case Coord.DirectGit g -> null;
            case Coord.Local l -> null;
            case Coord.SubElement s -> versionOf(s.unitCoord());
        };
    }

    /** Local filesystem path, only for {@link Coord.Local}. */
    public String path() {
        return coord instanceof Coord.Local l ? l.path() : null;
    }

    /** Git URL, only for {@link Coord.DirectGit}. */
    public String gitUrl() {
        return coord instanceof Coord.DirectGit g ? g.url() : null;
    }

    /** Git ref/branch/tag, only for {@link Coord.DirectGit} when pinned. */
    public String gitRef() {
        return coord instanceof Coord.DirectGit g ? g.ref() : null;
    }

    public boolean isLocal() { return coord instanceof Coord.Local; }

    public boolean isRegistry() {
        if (coord instanceof Coord.Bare || coord instanceof Coord.Kinded) return true;
        if (coord instanceof Coord.SubElement s) {
            return s.unitCoord() instanceof Coord.Bare || s.unitCoord() instanceof Coord.Kinded;
        }
        return false;
    }

    public boolean isDirectGit() { return coord instanceof Coord.DirectGit; }

    /** {@code true} when the coord pins kind via {@code skill:} or {@code plugin:}. */
    public boolean isKindPinned() { return kindFilter != UnitKindFilter.ANY; }

    // ------------------------------------------------------ derivation

    private static UnitKindFilter derivedFilter(Coord coord) {
        return switch (coord) {
            case Coord.Kinded k -> UnitKindFilter.forKind(k.kind());
            case Coord.Bare b -> UnitKindFilter.ANY;
            case Coord.DirectGit g -> UnitKindFilter.ANY;
            case Coord.Local l -> UnitKindFilter.ANY;
            case Coord.SubElement s -> derivedFilter(s.unitCoord());
        };
    }
}
