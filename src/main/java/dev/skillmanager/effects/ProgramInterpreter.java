package dev.skillmanager.effects;

public interface ProgramInterpreter {

    <R> R run(Program<R> program);

    /**
     * Run a {@link StagedProgram}: stage 1 first, then stage 2 built from
     * the post-stage-1 {@link EffectContext}. Receipts from both stages
     * flow through one renderer; the staged program's decoder sees the
     * flat union.
     */
    <R> R runStaged(StagedProgram<R> staged);
}
