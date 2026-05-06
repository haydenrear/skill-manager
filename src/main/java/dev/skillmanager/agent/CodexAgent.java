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

    private static Path codexHome() {
        String env = System.getenv("CODEX_HOME");
        return env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".codex");
    }
}
