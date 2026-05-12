package dev.skillmanager.model;

import java.util.List;

/**
 * One bindable sub-element inside a {@link DocUnit} — corresponds to one
 * {@code [[sources]]} row in the doc-repo's {@code skill-manager.toml}.
 *
 * <p>{@link #id} is what the coord grammar addresses
 * ({@code doc:<repo>/<id>}); it defaults to the file stem when the
 * manifest omits {@code id}. {@link #file} is the path inside the
 * doc-repo (typically under {@code claude-md/}). {@link #agents}
 * controls which agent files the {@link DocUnit} projects into when
 * bound — {@code "claude"} → {@code CLAUDE.md}, {@code "codex"} →
 * {@code AGENTS.md}.
 */
public record DocSource(
        String id,
        String file,
        List<String> agents
) {
    /** Default {@code agents} list when the manifest omits it. */
    public static final List<String> DEFAULT_AGENTS = List.of("claude", "codex");

    public DocSource {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("DocSource.id must not be blank");
        if (file == null || file.isBlank()) throw new IllegalArgumentException("DocSource.file must not be blank");
        agents = agents == null || agents.isEmpty() ? DEFAULT_AGENTS : List.copyOf(agents);
    }
}
