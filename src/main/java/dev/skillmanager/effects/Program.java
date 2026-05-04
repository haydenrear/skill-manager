package dev.skillmanager.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A program is a list of effects + a way to decode their receipts into a
 * report shape the caller cares about. Use cases build programs;
 * interpreters execute them.
 *
 * <p><b>Composition rule:</b> commands build exactly one {@code Program}.
 * Multi-phase commands compose at use-case-build time via {@link #then} —
 * never by running the interpreter twice. Decoder isolation is preserved:
 * the combined decoder routes its receipt slice to each component's
 * decoder by index range and merges their reports with the supplied
 * combiner.
 */
public record Program<R>(
        String operationId,
        List<SkillEffect> effects,
        ResultDecoder<R> decoder
) {
    public Program {
        effects = List.copyOf(effects);
    }

    /**
     * Concatenate {@code this} with {@code next}, producing a single
     * program whose decoder combines the two reports via {@code combine}.
     * Receipts are partitioned by effect index — {@code this.effects.size()}
     * receipts go to {@code this.decoder}, the rest to {@code next.decoder}.
     */
    public <U, V> Program<V> then(Program<U> next, BiFunction<R, U, V> combine) {
        List<SkillEffect> combinedEffects = new ArrayList<>(effects.size() + next.effects().size());
        combinedEffects.addAll(effects);
        combinedEffects.addAll(next.effects());
        int firstSize = effects.size();
        ResultDecoder<R> firstDecoder = decoder;
        ResultDecoder<U> secondDecoder = next.decoder();
        ResultDecoder<V> combined = receipts -> {
            R r1 = firstDecoder.decode(receipts.subList(0, firstSize));
            U r2 = secondDecoder.decode(receipts.subList(firstSize, receipts.size()));
            return combine.apply(r1, r2);
        };
        return new Program<>(operationId + "+" + next.operationId(), combinedEffects, combined);
    }
}
