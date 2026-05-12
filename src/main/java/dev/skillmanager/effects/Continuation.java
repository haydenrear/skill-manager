package dev.skillmanager.effects;

/**
 * Per-effect signal that decides whether the interpreter keeps executing
 * subsequent effects in the program. Decoupled from {@link EffectStatus} so
 * "did this effect succeed?" and "should the program keep going?" are
 * orthogonal axes.
 *
 * <p>Each {@link SkillEffect} carries a {@code continuation} field — its
 * <em>default</em> intent. Handlers can override on the receipt via
 * {@link EffectReceipt} factories that take an explicit {@code Continuation},
 * so a precondition effect (e.g. {@code RejectIfAlreadyInstalled}) can
 * statically declare HALT but emit a CONTINUE receipt when its condition
 * wasn't met. Interpreters drive off the receipt's continuation, never the
 * effect's declared default directly.
 *
 * <p>The split lets {@code LiveInterpreter} and {@code Executor} share one
 * halt rule: {@code if receipt.continuation == HALT, skip the rest}. The
 * {@code Executor} additionally walks back its compensation journal when
 * any receipt's status is {@link EffectStatus#FAILED} — but that's a
 * rollback decision, not a halt decision.
 */
public enum Continuation {
    /** Default — interpreters keep running subsequent effects after this one. */
    CONTINUE,
    /**
     * Stop the program — interpreters skip every remaining main effect
     * (each gets a SKIPPED receipt with reason "halted") but always still
     * run {@code Program.alwaysAfter()} cleanup.
     */
    HALT
}
