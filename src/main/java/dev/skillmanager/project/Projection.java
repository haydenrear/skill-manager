package dev.skillmanager.project;

import dev.skillmanager.model.UnitKind;

import java.nio.file.Path;

/**
 * One unit's projection into one agent's tree. Produced by
 * {@link Projector#planProjection}, consumed by
 * {@link Projector#apply}/{@link Projector#remove}.
 *
 * <p>{@code source} is the canonical store dir
 * ({@code skills/<name>} or {@code plugins/<name>}); {@code target} is
 * where the agent looks ({@code ~/.claude/skills/<name>},
 * {@code ~/.claude/plugins/<name>}, {@code ~/.codex/skills/<name>}).
 * Projection always means "make {@code target} resolve to the contents
 * of {@code source}" — symlink in the happy path, recursive copy as a
 * fallback when the filesystem doesn't support symlinks.
 */
public record Projection(String agentId, Path source, Path target, UnitKind kind) {}
