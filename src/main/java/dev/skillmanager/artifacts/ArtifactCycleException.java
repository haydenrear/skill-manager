package dev.skillmanager.artifacts;

import java.util.List;

/**
 * The artifact graph contains a cycle, so no artifact in it can be decided.
 *
 * <p>The sibling of {@link dev.skillmanager.plan.PlanCycleException}, and it
 * exists for the same reason that one does: a graph walk that meets a cycle
 * without checking for one does not report a bad graph, it overflows the stack
 * — and a {@code StackOverflowError} names neither the graph nor the artifacts
 * in it. A cycle here is a PLAN ERROR: two derived things each claiming to be
 * derived from the other cannot both be rebuilt, and whoever declared them is
 * the one who can fix it. So the chain is carried, printed, and attributable.
 *
 * <p>Unlike the resolve graph, this one is not authored — it is derived from
 * records, so a cycle in it means the DERIVATION is wrong rather than a
 * manifest. That makes reaching this a bug report against skill-manager, and
 * the message says so rather than telling an operator to edit something.
 */
public final class ArtifactCycleException extends RuntimeException {

    private final List<String> chain;

    public ArtifactCycleException(List<String> chain) {
        super("the artifact graph contains a cycle: " + String.join(" → ", chain)
                + " — an artifact cannot be derived from something derived from it, so "
                + "nothing on this chain can be decided. This graph is derived from the "
                + "home's own records rather than authored, so a cycle here is a defect "
                + "in the derivation and worth reporting.");
        this.chain = List.copyOf(chain);
    }

    /** The offending path, first repeated id at both ends: {@code [a, b, a]}. */
    public List<String> chain() { return chain; }
}
