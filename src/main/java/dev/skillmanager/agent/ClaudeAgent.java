package dev.skillmanager.agent;

import java.nio.file.Path;

public final class ClaudeAgent implements Agent {

    @Override public String id() { return "claude"; }

    @Override
    public Path skillsDir() {
        return claudeHome().resolve(".claude").resolve("skills");
    }

    @Override
    public Path pluginsDir() {
        return claudeHome().resolve(".claude").resolve("plugins");
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
     *
     * <p>Resolution routes through {@link AgentHomes} so a test harness
     * can install a thread-local override without mutating
     * {@code System.getenv()} — see {@code AgentHomes} javadoc for why
     * (preventing real-~/.claude pollution from unit tests).
     */
    private static Path claudeHome() {
        return AgentHomes.resolveOrDefault(AgentHomes.CLAUDE_HOME,
                Path.of(System.getProperty("user.home")));
    }
}
