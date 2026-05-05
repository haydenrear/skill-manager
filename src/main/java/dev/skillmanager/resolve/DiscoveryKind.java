package dev.skillmanager.resolve;

/**
 * Where a unit was discovered. Distinct from {@link Transport} — the
 * transport is how the bytes land on disk; the discovery kind is which
 * mechanism resolved the coord.
 *
 * <p>Today only two values are populated. The spec leaves room for
 * {@code MARKETPLACE} when multi-source composition lands; until then
 * the planner sees {@link #REGISTRY} for bare/kinded coords and
 * {@link #DIRECT} for github/git+/file/local-path coords.
 */
public enum DiscoveryKind {
    REGISTRY,
    DIRECT
}
