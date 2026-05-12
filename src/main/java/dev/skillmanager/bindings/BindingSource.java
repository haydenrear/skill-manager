package dev.skillmanager.bindings;

/**
 * Provenance for a {@link Binding} — who created it. Drives the
 * {@code MANAGED-BY} column in {@code skill-manager bindings list}
 * and informs teardown semantics:
 *
 * <ul>
 *   <li>{@link #EXPLICIT} bindings survive containing-operation
 *       teardowns (e.g. a harness {@code rm} does not remove an
 *       explicit user-bound projection that happens to be in the
 *       harness sandbox).</li>
 *   <li>{@link #DEFAULT_AGENT}, {@link #HARNESS}, {@link #PROFILE}
 *       bindings are managed bindings — torn down by their owner.</li>
 * </ul>
 */
public enum BindingSource {
    /** User-initiated {@code bind} command. */
    EXPLICIT,
    /** Implicit binding {@code install} creates for the configured agent's fixed skill/plugin dir. */
    DEFAULT_AGENT,
    /** Created as part of a harness-template instantiation. */
    HARNESS,
    /** Created as part of a profile sync. */
    PROFILE
}
