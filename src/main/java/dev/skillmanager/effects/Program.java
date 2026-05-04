package dev.skillmanager.effects;

import java.util.List;

/**
 * A program is a list of effects + a way to decode their receipts into a
 * report shape the caller cares about. Use cases build programs;
 * interpreters execute them.
 */
public record Program<R>(
        String operationId,
        List<SkillEffect> effects,
        ResultDecoder<R> decoder
) {
    public Program {
        effects = List.copyOf(effects);
    }
}
