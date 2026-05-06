package dev.skillmanager.effects;

import java.util.function.Function;

/**
 * Two-stage program where stage 2 is built dynamically from the
 * {@link EffectContext} after stage 1 has executed. Use when stage 2's
 * effect list depends on data computed by stage 1 (e.g. sync's post-merge
 * reference discovery — the new {@code skill_references} only become
 * visible once the merge has landed in the working tree).
 *
 * <p>This complements {@link Program#then(Program, java.util.function.BiFunction)},
 * which composes two statically-known programs at use-case-build time.
 * {@code StagedProgram} fills the dynamic-composition gap.
 *
 * <h3>Reporting</h3>
 *
 * The decoder receives the flat union of receipts from stage 1 and stage 2
 * in execution order. A single decoder owns the combined report — there is
 * no "merge two reports" step. Callers that already have a decoder over a
 * superset of receipts (e.g. {@link dev.skillmanager.app.SyncUseCase}'s
 * decoder, which already pattern-matches every receipt fact it cares about)
 * reuse it unchanged.
 *
 * <h3>Renderer / context lifecycle</h3>
 *
 * The interpreter creates one {@link ProgramRenderer} and one
 * {@link EffectContext}, threads both through stage 1 and stage 2, and
 * calls {@link ProgramRenderer#onComplete} once at the end. Receipts from
 * both stages flow through the same renderer in the same order they were
 * produced, so user-facing output looks like one continuous program.
 */
public record StagedProgram<R>(
        String operationId,
        Program<?> stage1,
        Function<EffectContext, Program<?>> stage2,
        ResultDecoder<R> decoder
) {}
