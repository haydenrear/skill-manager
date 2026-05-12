package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link AgentUnit} carrier for a doc-repo (#48). A doc-repo is a
 * manifested collection of markdown files — each declared as a
 * {@link DocSource} — that bind into project {@code CLAUDE.md} /
 * {@code AGENTS.md} via tracked-copy + {@code @}-import pairs.
 *
 * <p>Doc-repos do not project into any agent's default skill/plugin
 * dir; they're bound explicitly through
 * {@code skill-manager bind doc:<repo>[/<source>] --to <root>} (or
 * declared in a harness template, which fans out the same bindings
 * with {@link dev.skillmanager.bindings.BindingSource#HARNESS}).
 * Their {@link #cliDependencies()}, {@link #mcpDependencies()}, and
 * {@link #references()} are always empty — doc-repos are content,
 * not runtime.
 */
public record DocUnit(
        String name,
        String version,
        String description,
        Path sourcePath,
        List<DocSource> sources
) implements AgentUnit {

    public DocUnit {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("DocUnit.name must not be blank");
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    @Override public UnitKind kind() { return UnitKind.DOC; }
    @Override public List<CliDependency> cliDependencies() { return List.of(); }
    @Override public List<McpDependency> mcpDependencies() { return List.of(); }
    @Override public List<UnitReference> references() { return List.of(); }

    /** Look up a source by id. Used by the binder to validate sub-element coords. */
    public Optional<DocSource> findSource(String id) {
        if (id == null) return Optional.empty();
        return sources.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
