package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AgentUnit} carrier for a harness template (#47). A harness
 * template is metadata-only — a named, versioned bundle of skill /
 * plugin / doc-repo coords plus MCP tool selections. Instantiation
 * ({@code skill-manager harness instantiate}) fans out one
 * {@link dev.skillmanager.bindings.BindingSource#HARNESS}
 * {@link dev.skillmanager.bindings.Binding} per referenced unit
 * into a sandbox root.
 *
 * <p>Both {@link #units()} and {@link #docs()} are flat lists of
 * {@link UnitReference}s. {@code references()} returns the union so
 * the resolver / install pipeline picks up every referenced unit as
 * a transitive dependency of the harness install.
 */
public record HarnessUnit(
        String name,
        String version,
        String description,
        Path sourcePath,
        /** Coords for skills and plugins the harness exposes. */
        List<UnitReference> units,
        /** Coords for doc-repo sources the harness binds into its sandbox. */
        List<UnitReference> docs,
        /** Per-server MCP tool selections; allowlist applied at gateway time. */
        List<HarnessMcpToolSelection> mcpTools
) implements AgentUnit {

    public HarnessUnit {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("HarnessUnit.name must not be blank");
        }
        units = units == null ? List.of() : List.copyOf(units);
        docs = docs == null ? List.of() : List.copyOf(docs);
        mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
    }

    @Override public UnitKind kind() { return UnitKind.HARNESS; }
    @Override public List<CliDependency> cliDependencies() { return List.of(); }
    @Override public List<McpDependency> mcpDependencies() { return List.of(); }

    /**
     * Combined {@code units + docs} so the resolver walks them as
     * transitive deps. Order is units-first, docs-after.
     */
    @Override
    public List<UnitReference> references() {
        List<UnitReference> out = new ArrayList<>(units.size() + docs.size());
        out.addAll(units);
        out.addAll(docs);
        return out;
    }
}
