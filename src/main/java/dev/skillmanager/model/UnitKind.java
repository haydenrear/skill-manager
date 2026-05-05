package dev.skillmanager.model;

/**
 * Distinguishes the two kinds of installable units skill-manager handles.
 * Carried as data on {@link AgentUnit} descriptors and on
 * {@code InstalledUnit} records; effects branch on this only at the four
 * isolated kind-divergence points (store dir, projector, scaffold,
 * uninstall re-walk). Everywhere else, code operates on {@code AgentUnit}
 * uniformly.
 */
public enum UnitKind {
    SKILL,
    PLUGIN
}
