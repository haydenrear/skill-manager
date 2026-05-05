package dev.skillmanager.resolve;

/**
 * How a unit's bytes get from origin onto disk. Today only two values
 * are populated. {@code ARCHIVE} (signed tarball) is on the roadmap;
 * adding it doesn't require touching {@link CoordResolver} or its
 * callers — only a new {@link Git}-style port and the appropriate
 * branch in the resolver.
 */
public enum Transport {
    GIT,
    LOCAL
}
