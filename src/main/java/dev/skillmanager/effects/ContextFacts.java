package dev.skillmanager.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Static helpers for iterating typed facts across a list of receipts —
 * lets decoders and printers share the "walk every fact, dispatch by
 * variant" loop instead of nesting a switch in every consumer.
 *
 * <p>Think of this as a tiny interpreter <em>over results</em>: callers
 * supply per-variant handlers, this class drives the walk.
 */
public final class ContextFacts {

    private ContextFacts() {}

    /** Run {@code visitor} on every {@link ContextFact} across every receipt. */
    public static void walk(List<EffectReceipt> receipts, Consumer<ContextFact> visitor) {
        for (EffectReceipt r : receipts) {
            for (ContextFact f : r.facts()) visitor.accept(f);
        }
    }

    /** Collect facts of a specific variant across every receipt. */
    @SuppressWarnings("unchecked")
    public static <T extends ContextFact> List<T> collect(List<EffectReceipt> receipts, Class<T> kind) {
        List<T> out = new ArrayList<>();
        for (EffectReceipt r : receipts) {
            for (ContextFact f : r.facts()) {
                if (kind.isInstance(f)) out.add((T) f);
            }
        }
        return out;
    }

    /** True if any receipt carries a {@link ContextFact.DryRun} marker. */
    public static boolean anyDryRun(List<EffectReceipt> receipts) {
        for (EffectReceipt r : receipts) {
            for (ContextFact f : r.facts()) if (f instanceof ContextFact.DryRun) return true;
        }
        return false;
    }
}
