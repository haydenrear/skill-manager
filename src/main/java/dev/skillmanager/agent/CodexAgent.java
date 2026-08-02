package dev.skillmanager.agent;

import java.nio.file.Path;

public final class CodexAgent implements Agent {

    @Override public String id() { return "codex"; }

    @Override
    public Path skillsDir() {
        return codexHome().resolve("skills");
    }

    /**
     * Plugin entries dir, symmetrical with {@link ClaudeAgent#pluginsDir}.
     * Codex v1 doesn't consume plugins — the projector (ticket 11) is
     * a no-op for Codex. The accessor is here so {@link Agent} stays
     * uniform across implementations.
     */
    @Override
    public Path pluginsDir() {
        return codexHome().resolve("plugins");
    }

    @Override
    public Path mcpConfigPath() {
        return codexHome().resolve("config.toml");
    }

    @Override public String mcpConfigFormat() { return "codex-toml"; }

    /**
     * {@code $CODEX_HOME}, else {@code <the active home's root>/.codex}.
     *
     * <p>The default goes through {@link AgentHomes#agentHomeRoot()} rather
     * than {@code System.getProperty("user.home")} for two reasons that arrived
     * as two separate incidents. The JVM ignores {@code $HOME} on macOS, so the
     * direct property read sent skill projection into the operator's real
     * {@code ~/.codex} whenever {@code CODEX_HOME} was unset (issue #18); and
     * {@code $HOME} is the wrong base even when it is read correctly, because a
     * unit installed into {@code <project>/.skill-manager} belongs in that
     * project's {@code .codex} and not in the operator's (issue #145).
     */
    private static Path codexHome() {
        return AgentHomes.resolveOrDefault(AgentHomes.CODEX_HOME,
                AgentHomes.agentHomeRoot().resolve(".codex"));
    }
}
