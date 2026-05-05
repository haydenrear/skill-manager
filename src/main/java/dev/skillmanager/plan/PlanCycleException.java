package dev.skillmanager.plan;

import java.util.List;

/** Thrown by {@link PlanBuilder#plan(java.util.List, boolean, boolean, java.nio.file.Path)} when the unit reference graph contains a cycle. */
public final class PlanCycleException extends RuntimeException {

    private final List<String> chain;

    public PlanCycleException(List<String> chain) {
        super("cyclic dependency detected: " + String.join(" → ", chain));
        this.chain = List.copyOf(chain);
    }

    /** The cycle chain, e.g. {@code [a, b, a]} showing the offending path. */
    public List<String> chain() { return chain; }
}
