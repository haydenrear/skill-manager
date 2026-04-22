package dev.skillmanager.agent;

import java.nio.file.Path;

public final class CodexAgent implements Agent {

    @Override public String id() { return "codex"; }

    @Override
    public Path skillsDir() {
        String env = System.getenv("CODEX_HOME");
        Path base = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".codex");
        return base.resolve("skills");
    }

    @Override
    public Path mcpConfigPath() {
        String env = System.getenv("CODEX_HOME");
        Path base = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".codex");
        return base.resolve("config.toml");
    }

    @Override public String mcpConfigFormat() { return "codex-toml"; }
}
