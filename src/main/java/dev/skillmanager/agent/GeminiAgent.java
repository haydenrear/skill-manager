package dev.skillmanager.agent;

import java.nio.file.Path;

public final class GeminiAgent implements Agent {

    @Override public String id() { return "gemini"; }

    @Override
    public Path skillsDir() {
        return geminiHome().resolve("skills");
    }

    /**
     * Gemini CLI does not currently consume skill-manager plugin units
     * through a Claude/Codex-style plugin directory. This path keeps the
     * {@link Agent} interface uniform; plugin projection is deliberately
     * unsupported until Gemini extension mapping is modeled.
     */
    @Override
    public Path pluginsDir() {
        return geminiHome().resolve("plugins");
    }

    @Override
    public Path mcpConfigPath() {
        return geminiHome().resolve("settings.json");
    }

    @Override public String mcpConfigFormat() { return "gemini-json"; }

    /**
     * {@code $GEMINI_HOME}, else {@code <the active home's root>/.gemini}.
     *
     * <p>Same reasoning as {@code CodexAgent.codexHome()}: the default is
     * derived from {@link AgentHomes#agentHomeRoot()}, because the JVM's
     * {@code user.home} ignores {@code $HOME} on macOS and so bypasses every
     * sandbox (issue #18), and because {@code $HOME} names the wrong home
     * whenever {@code SKILL_MANAGER_HOME} names a project's (issue #145).
     */
    private static Path geminiHome() {
        return AgentHomes.resolveOrDefault(AgentHomes.GEMINI_HOME,
                AgentHomes.agentHomeRoot().resolve(".gemini"));
    }
}
