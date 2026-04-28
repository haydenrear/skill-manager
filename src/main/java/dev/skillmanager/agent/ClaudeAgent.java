package dev.skillmanager.agent;

import java.nio.file.Path;

public final class ClaudeAgent implements Agent {

    @Override public String id() { return "claude"; }

    @Override
    public Path skillsDir() {
        return claudeHome().resolve(".claude").resolve("skills");
    }

    @Override
    public Path mcpConfigPath() {
        return claudeHome().resolve(".claude.json");
    }

    @Override public String mcpConfigFormat() { return "claude"; }

    /**
     * Root directory containing Claude Code's {@code .claude.json} and
     * {@code .claude/skills/} — i.e. the equivalent of {@code $HOME} for
     * Claude's purposes. {@code CLAUDE_HOME} env var override exists so
     * test runs (and any other sandboxed context) can keep their config
     * mutations away from the developer's real {@code ~/.claude.json}.
     * Mirrors {@code CODEX_HOME} on {@link CodexAgent}, modulo path
     * shape: Codex puts everything under {@code ~/.codex/}, while Claude
     * scatters across {@code ~/.claude.json} (file) and {@code ~/.claude/}
     * (dir), so {@code CLAUDE_HOME} stands in for the parent of both.
     */
    private static Path claudeHome() {
        String env = System.getenv("CLAUDE_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"));
    }
}
