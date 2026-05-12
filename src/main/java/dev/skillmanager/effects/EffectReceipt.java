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
 *
 * <p>{@link #status()} and {@link #continuation()} are orthogonal —
 * "did this effect succeed?" is separate from "should the program keep
 * going?" Most effects emit {@link Continuation#CONTINUE}; precondition
 * effects, policy gates, and resolve-with-failures emit {@link
 * Continuation#HALT}. The interpreter's halt rule is purely the
 * continuation; the executor's rollback rule is purely the status.
 */
public record EffectReceipt(
        SkillEffect effect,
        EffectStatus status,
        Continuation continuation,
        List<ContextFact> facts,
        String errorMessage,
        Instant at
) {
    public EffectReceipt {
        facts = facts == null ? List.of() : List.copyOf(facts);
        if (continuation == null) continuation = Continuation.CONTINUE;
    }

    /**
     * Backwards-compatible 5-arg constructor — defaults {@code
     * continuation} to {@link Continuation#CONTINUE}. Existing callers
     * keep compiling; new callers that want an explicit halt use the
     * full 6-arg form or the {@code *AndHalt} factory methods below.
     */
    public EffectReceipt(SkillEffect effect, EffectStatus status,
                         List<ContextFact> facts, String errorMessage, Instant at) {
        this(effect, status, Continuation.CONTINUE, facts, errorMessage, at);
    }

    // ------------------------------------------------------------- factories

    public static EffectReceipt ok(SkillEffect effect, List<ContextFact> facts) {
        return new EffectReceipt(effect, EffectStatus.OK, effect.continuationOnOk(),
                facts, null, Instant.now());
    }

    public static EffectReceipt ok(SkillEffect effect, ContextFact... facts) {
        return ok(effect, List.of(facts));
    }

    public static EffectReceipt skipped(SkillEffect effect, String reason) {
        return new EffectReceipt(effect, EffectStatus.SKIPPED, Continuation.CONTINUE,
                List.of(), reason, Instant.now());
    }

    public static EffectReceipt partial(SkillEffect effect, List<ContextFact> facts, String summary) {
        return new EffectReceipt(effect, EffectStatus.PARTIAL, effect.continuationOnPartial(),
                facts, summary, Instant.now());
    }

    public static EffectReceipt partial(SkillEffect effect, String summary, ContextFact... facts) {
        return partial(effect, List.of(facts), summary);
    }

    public static EffectReceipt failed(SkillEffect effect, String message) {
        return new EffectReceipt(effect, EffectStatus.FAILED, effect.continuationOnFail(),
                List.of(), message, Instant.now());
    }

    public static EffectReceipt failed(SkillEffect effect, List<ContextFact> facts, String message) {
        return new EffectReceipt(effect, EffectStatus.FAILED, effect.continuationOnFail(),
                facts, message, Instant.now());
    }

    /**
     * OK status + {@link Continuation#HALT}. Cooperative stop — the
     * effect succeeded but the program shouldn't continue (precondition
     * met, policy gate rejected, etc.). Replaces the prior overloaded
     * {@link EffectStatus#HALTED} for cooperative halts. The interpreter
     * skips remaining main effects; {@code Program.alwaysAfter} still
     * runs.
     */
    public static EffectReceipt okAndHalt(SkillEffect effect, String reason, ContextFact... facts) {
        return new EffectReceipt(effect, EffectStatus.OK, Continuation.HALT,
                List.of(facts), reason, Instant.now());
    }

    /**
     * FAILED status + {@link Continuation#HALT}. The effect failed AND
     * downstream effects depend on its output, so the program halts.
     * Used by handlers like {@code CommitUnitsToStore} (a failed commit
     * means provenance / run / tail have nothing to operate on) and
     * resolve-with-failures (no graph means no plan).
     */
    public static EffectReceipt failedAndHalt(SkillEffect effect, String message, ContextFact... facts) {
        return new EffectReceipt(effect, EffectStatus.FAILED, Continuation.HALT,
                List.of(facts), message, Instant.now());
    }

    public static EffectReceipt failedAndHalt(SkillEffect effect,
                                              List<ContextFact> facts, String message) {
        return new EffectReceipt(effect, EffectStatus.FAILED, Continuation.HALT,
                facts, message, Instant.now());
    }

    /**
     * Legacy {@link EffectStatus#HALTED} factory — preserved as a
     * cooperative ok-with-halt alias so older callers and tests that
     * reference {@code EffectReceipt.halted(...)} keep working. New code
     * should use {@link #okAndHalt} or {@link #failedAndHalt} for
     * explicit intent.
     */
    public static EffectReceipt halted(SkillEffect effect, String reason, ContextFact... facts) {
        return new EffectReceipt(effect, EffectStatus.HALTED, Continuation.HALT,
                List.of(facts), reason, Instant.now());
    }
}
