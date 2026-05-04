package dev.skillmanager.effects;

public enum EffectStatus {
    OK,
    /** Effect skipped on purpose (no-op condition met, e.g. --skip-mcp). */
    SKIPPED,
    /** Effect ran but recorded a recoverable error (e.g. one MCP server failed to register). */
    PARTIAL,
    /** Effect failed entirely. */
    FAILED
}
