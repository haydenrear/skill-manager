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

    private static Path geminiHome() {
        return AgentHomes.resolveOrDefault(AgentHomes.GEMINI_HOME,
                Path.of(System.getProperty("user.home"), ".gemini"));
    }
}
