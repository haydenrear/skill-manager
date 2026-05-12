package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.file.Path;

/**
 * One filesystem action a {@link Binding} produced — a persisted
 * ledger row. Distinct from {@link dev.skillmanager.project.Projection},
 * which is the projector-internal "what to symlink for this agent"
 * tuple. The {@link Binding} layer translates projector entries
 * into {@code Projection}s and records them here so {@code unbind}
 * can walk an exact filesystem footprint instead of guessing.
 *
 * <p>One {@link Binding} may produce multiple {@code Projection}s.
 * The common case is a single {@link ProjectionKind#SYMLINK}; when
 * the conflict policy is {@link ConflictPolicy#RENAME_EXISTING} and
 * the destination is occupied, the planner emits a
 * {@link ProjectionKind#RENAMED_ORIGINAL_BACKUP} alongside it so the
 * backup move is its own reversible action.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Projection(
        /** Back-pointer to the {@link Binding} this row belongs to. */
        String bindingId,
        /**
         * Absolute path in the store ({@code <store>/skills/<name>},
         * {@code <store>/plugins/<name>}, etc.). Null for
         * {@link ProjectionKind#RENAMED_ORIGINAL_BACKUP} rows where no
         * store-side source exists — those rows describe a move of an
         * existing user file out of the way.
         */
        Path sourcePath,
        /** Absolute path on the target side — where the projection lands. */
        Path destPath,
        ProjectionKind kind,
        /**
         * For {@link ProjectionKind#RENAMED_ORIGINAL_BACKUP} only —
         * the original destination path the existing file was moved
         * away from. Unbind moves the backup back to this path.
         */
        String backupOf
) {}
