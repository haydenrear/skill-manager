package dev.skillmanager.model;

import java.nio.file.Path;

/**
 * The fetchable projection of a {@link Coord}: the {@code (source, version)}
 * pair {@link dev.skillmanager.store.Fetcher#fetch} accepts.
 *
 * <p>Every reference walk — the resolver's transitive BFS, the sync
 * unmet-reference scan, project dependency resolution — needs to turn a
 * parsed {@link Coord} back into something fetchable. Each of those used
 * to carry its own local/registry {@code if} chain, and each silently
 * dropped {@link Coord.DirectGit} because a git coord has neither a
 * registry {@code name()} nor a {@code path()} (ticket 115).
 *
 * <p>The {@code switch} below is exhaustive over the sealed {@link Coord}
 * hierarchy, so a new coord shape is a compile error at the one place that
 * has to handle it rather than a reference that vanishes at runtime.
 *
 * <p>Direct-git coords project to {@code git+<url>} with the git ref as the
 * version, which is the spelling {@code Fetcher} clones and the spelling
 * {@link dev.skillmanager.resolve.Resolver}'s coord key canonicalizes — so a
 * {@code github:owner/repo} top-level coord and a {@code github:owner/repo}
 * child reference collapse onto the same graph node and the same cycle key.
 */
public record CoordSource(String source, String version) {

    /**
     * Project {@code coord} onto a fetchable source.
     *
     * @param baseRoot directory a relative {@link Coord.Local} path resolves
     *                 against — the referring unit's own root
     */
    public static CoordSource of(Coord coord, Path baseRoot) {
        Coord c = coord instanceof Coord.SubElement s ? s.unitCoord() : coord;
        return switch (c) {
            case Coord.Local l -> {
                Path p = Path.of(l.path());
                Path resolved = p.isAbsolute() || baseRoot == null
                        ? p
                        : baseRoot.resolve(p).normalize();
                yield new CoordSource(resolved.toString(), null);
            }
            case Coord.Bare b -> new CoordSource(b.name(), b.version());
            case Coord.Kinded k -> new CoordSource(k.name(), k.version());
            case Coord.DirectGit g -> new CoordSource("git+" + g.url(), g.ref());
            // Coord.parse never nests a SubElement inside a SubElement, so the
            // unwrap above is total.
            case Coord.SubElement ignored ->
                    throw new IllegalStateException("nested sub-element coord: " + coord.raw());
        };
    }

    /** {@code true} when this projects to a git clone rather than a registry name or path. */
    public boolean isGit() { return source != null && source.startsWith("git+"); }

    public boolean isUsable() { return source != null && !source.isBlank(); }
}
