package dev.skillmanager.bindings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Per-unit ledger. Persisted to
 * {@code $SKILL_MANAGER_HOME/installed/<name>.projections.json}.
 * The list of all live {@link Binding}s for {@code unitName}.
 *
 * <p>{@code uninstall} walks this file to learn the exact footprint
 * the unit has projected so non-default bindings (custom target
 * roots, harness sandboxes, project-local doc files) get torn down
 * alongside the default {@link BindingSource#DEFAULT_AGENT} ones.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectionLedger(
        String unitName,
        List<Binding> bindings
) {
    public ProjectionLedger {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public static ProjectionLedger empty(String unitName) {
        return new ProjectionLedger(unitName, List.of());
    }

    public Optional<Binding> findById(String bindingId) {
        return bindings.stream().filter(b -> b.bindingId().equals(bindingId)).findFirst();
    }

    /** Add or replace (matching {@code bindingId}) a binding. */
    public ProjectionLedger withBinding(Binding b) {
        List<Binding> next = new ArrayList<>(bindings.size() + 1);
        boolean replaced = false;
        for (Binding cur : bindings) {
            if (cur.bindingId().equals(b.bindingId())) {
                next.add(b);
                replaced = true;
            } else {
                next.add(cur);
            }
        }
        if (!replaced) next.add(b);
        return new ProjectionLedger(unitName, next);
    }

    /** Drop the binding with {@code bindingId}; no-op if absent. */
    public ProjectionLedger withoutBinding(String bindingId) {
        List<Binding> next = new ArrayList<>(bindings.size());
        for (Binding cur : bindings) {
            if (!cur.bindingId().equals(bindingId)) next.add(cur);
        }
        return new ProjectionLedger(unitName, next);
    }
}
