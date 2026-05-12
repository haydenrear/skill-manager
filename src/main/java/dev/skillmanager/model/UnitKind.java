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
    DOC,
    /**
     * A harness template (#47): a named, versioned bundle of skills +
     * plugins + doc-repo sources + MCP tool selections. Templates
     * install into the store like any other unit
     * ({@code <store>/harnesses/<name>/}), then instantiation
     * ({@code skill-manager harness instantiate}) fans out
     * {@link dev.skillmanager.bindings.BindingSource#HARNESS}
     * bindings per referenced unit into a sandbox root. Harness
     * templates do not project into agent dirs themselves — they're
     * metadata for the instantiator.
     */
    HARNESS
}
