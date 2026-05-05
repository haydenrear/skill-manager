package dev.skillmanager.resolve;

import dev.skillmanager.model.UnitKind;
import dev.skillmanager.model.UnitKindFilter;

import java.util.List;
import java.util.Optional;

/**
 * Minimal registry port the resolver needs. Default impl wraps
 * {@code RegistryClient}; tests substitute {@code FakeRegistry} so
 * the resolver can be exercised without HTTP.
 *
 * <p>Returns lightweight {@link Hit} records carrying just enough
 * info for the resolver to decide kind + version + git origin. The
 * heavy descriptor (with parsed deps) is built by
 * {@link CoordResolver} after fetching, not the registry.
 */
public interface Registry {

    /**
     * Look up units by name. Returns every kind variant the registry
     * knows about so the resolver can detect ambiguity (multi-kind
     * collisions). The {@code filter} lets the resolver short-circuit
     * for kind-pinned coords.
     */
    List<Hit> lookup(String name, String version, UnitKindFilter filter);

    /**
     * Optional convenience: lookup expected to match a single hit
     * post-filtering. Default impl just runs {@link #lookup} and
     * picks the first.
     */
    default Optional<Hit> lookupOne(String name, String version, UnitKindFilter filter) {
        List<Hit> hits = lookup(name, version, filter);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * Registry hit: name + kind + version + the git origin we'd clone
     * to materialize the bytes. Today every registry hit is GIT
     * transport; a future ticket can add a {@code archiveUrl} variant.
     */
    record Hit(
            String name,
            UnitKind kind,
            String version,
            String gitUrl,
            String gitRef
    ) {}
}
