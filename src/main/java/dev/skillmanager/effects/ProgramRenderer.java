package dev.skillmanager.effects;

/**
 * One place per program where user-facing output lives. Handlers emit
 * {@link ContextFact}s only — they do not log, print, or banner. The
 * interpreter feeds every receipt through {@link #onReceipt}, then calls
 * {@link #onComplete} once at the top of the program. The renderer's
 * {@code switch} over {@link ContextFact} variants is exhaustive (no
 * {@code default}) so adding a new fact is a compile error in every
 * renderer until it's handled.
 *
 * <p>Sub-programs share the parent's renderer via {@link EffectContext}
 * so accumulated state (refused/conflicted lists, agent-config rollups,
 * MCP register results, outstanding-error banner) survives the boundary.
 */
public interface ProgramRenderer {

    /** Called after every receipt — both main and {@link Program#alwaysAfter()}. */
    void onReceipt(EffectReceipt receipt);

    /** Called once at the end of the top-level {@code run()}. */
    void onComplete();

    /** No-op renderer for tests / programmatic invocation. */
    ProgramRenderer NOOP = new ProgramRenderer() {
        @Override public void onReceipt(EffectReceipt receipt) {}
        @Override public void onComplete() {}
    };
}
