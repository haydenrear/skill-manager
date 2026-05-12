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
         * {@code <store>/plugins/<name>},
         * {@code <store>/docs/<repo>/<source-file>}, etc.). Null for
         * rows where no store-side source exists:
         * <ul>
         *   <li>{@link ProjectionKind#RENAMED_ORIGINAL_BACKUP} — describes
         *       a move of an existing user file out of the way.</li>
         *   <li>{@link ProjectionKind#IMPORT_DIRECTIVE} — the line text
         *       is derived from the binding's sibling {@code MANAGED_COPY}
         *       projection, not a store source path.</li>
         * </ul>
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
        String backupOf,
        /**
         * For {@link ProjectionKind#MANAGED_COPY} only — hex SHA-256
         * of the bytes copied into {@link #destPath} at the moment of
         * the last successful apply. {@code null} for every other
         * kind. Sync compares this against {@code currentSource} +
         * {@code currentDest} to route into the four-state matrix
         * (up-to-date / upgrade / locally-edited / conflict).
         */
        String boundHash
) {
    /**
     * Legacy 5-arg constructor for callers that don't need the
     * doc-repo {@code boundHash} field. New code uses the canonical
     * 6-arg constructor directly; this keeps ticket-49 callsites
     * compiling unchanged.
     */
    public Projection(String bindingId, Path sourcePath, Path destPath,
                      ProjectionKind kind, String backupOf) {
        this(bindingId, sourcePath, destPath, kind, backupOf, null);
    }
}
