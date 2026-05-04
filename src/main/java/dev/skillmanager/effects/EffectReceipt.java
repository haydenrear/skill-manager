package dev.skillmanager.effects;

import java.time.Instant;
import java.util.List;

/**
 * What actually happened when an interpreter ran an effect. Receipts are
 * the substrate for compensation planning + the operation journal — every
 * decoder reads them to build its report.
 *
 * <p>{@link #facts()} is a typed list of {@link ContextFact} variants —
 * decoders {@code switch} over them exhaustively rather than grepping a
 * string-keyed map. Failed receipts may still carry partial facts (e.g.
 * which skills committed before a copy threw).
 */
public record EffectReceipt(
        SkillEffect effect,
        EffectStatus status,
        List<ContextFact> facts,
        String errorMessage,
        Instant at
) {
    public EffectReceipt {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static EffectReceipt ok(SkillEffect effect, List<ContextFact> facts) {
        return new EffectReceipt(effect, EffectStatus.OK, facts, null, Instant.now());
    }

    public static EffectReceipt ok(SkillEffect effect, ContextFact... facts) {
        return ok(effect, List.of(facts));
    }

    public static EffectReceipt skipped(SkillEffect effect, String reason) {
        return new EffectReceipt(effect, EffectStatus.SKIPPED, List.of(), reason, Instant.now());
    }

    public static EffectReceipt partial(SkillEffect effect, List<ContextFact> facts, String summary) {
        return new EffectReceipt(effect, EffectStatus.PARTIAL, facts, summary, Instant.now());
    }

    public static EffectReceipt partial(SkillEffect effect, String summary, ContextFact... facts) {
        return partial(effect, List.of(facts), summary);
    }

    public static EffectReceipt failed(SkillEffect effect, String message) {
        return new EffectReceipt(effect, EffectStatus.FAILED, List.of(), message, Instant.now());
    }

    public static EffectReceipt failed(SkillEffect effect, List<ContextFact> facts, String message) {
        return new EffectReceipt(effect, EffectStatus.FAILED, facts, message, Instant.now());
    }

    /**
     * Halt the program — interpreter SKIPS every remaining effect in
     * {@link Program#effects()} (with reason "halted") but still runs
     * {@link Program#alwaysAfter()}.
     */
    public static EffectReceipt halted(SkillEffect effect, String reason, ContextFact... facts) {
        return new EffectReceipt(effect, EffectStatus.HALTED, List.of(facts), reason, Instant.now());
    }
}
