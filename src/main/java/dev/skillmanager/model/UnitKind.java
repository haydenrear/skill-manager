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
    PLUGIN,
    /**
     * A doc-repo (#48): a manifested collection of markdown files
     * (each a {@code [[sources]]} row in {@code skill-manager.toml})
     * bindable into project CLAUDE.md / AGENTS.md via tracked copies
     * + {@code @}-imports. Doc-repos do not project into any agent's
     * default skill/plugin dir — they are bound explicitly through
     * {@code skill-manager bind doc:<repo>[/<source>] --to <root>}.
     */
    DOC
}
