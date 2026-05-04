package dev.skillmanager.effects;

public enum EffectStatus {
    OK,
    /** Effect skipped on purpose (no-op condition met, e.g. --skip-mcp). */
    SKIPPED,
    /** Effect ran but recorded a recoverable error (e.g. one MCP server failed to register). */
    PARTIAL,
    /** Effect failed entirely. */
    FAILED,
    /**
     * Effect refused to allow the program to continue — the interpreter
     * skips every remaining effect in {@link Program#effects()} (each
     * gets a SKIPPED receipt with reason "halted") but always still runs
     * {@link Program#alwaysAfter()}. Use for precondition failures: plan
     * has blocked items, top-level skill already installed, etc.
     */
    HALTED
}
