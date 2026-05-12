package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.model.UnitKind;

import java.nio.file.Path;
import java.util.List;

/**
 * A persisted, named association {@code (unit, sub-element?, targetRoot,
 * conflictPolicy)}. The unit-of-management for "this unit is projected
 * to this place." Listed, shown, unbound.
 *
 * <p>The {@code projections} list owns the data needed to undo every
 * filesystem action this binding produced — the symlink itself, plus
 * any {@link ProjectionKind#RENAMED_ORIGINAL_BACKUP} row recorded for
 * the {@link ConflictPolicy#RENAME_EXISTING} flow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Binding(
        /** ULID; stable across operations. */
        String bindingId,
        String unitName,
        UnitKind unitKind,
        /** Null for whole-unit bindings; otherwise the manifest sub-element selector. */
        String subElement,
        Path targetRoot,
        ConflictPolicy conflictPolicy,
        /** ISO-8601 UTC timestamp the binding was first created. */
        String createdAt,
        BindingSource source,
        List<Projection> projections
) {
    public Binding {
        projections = projections == null ? List.of() : List.copyOf(projections);
    }

    public Binding withProjections(List<Projection> next) {
        return new Binding(bindingId, unitName, unitKind, subElement, targetRoot,
                conflictPolicy, createdAt, source, next);
    }
}
