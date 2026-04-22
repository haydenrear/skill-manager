package dev.skillmanager.agent;

import java.nio.file.Path;

public final class ClaudeAgent implements Agent {

    @Override public String id() { return "claude"; }

    @Override
    public Path skillsDir() {
        return Path.of(System.getProperty("user.home"), ".claude", "skills");
    }

    @Override
    public Path mcpConfigPath() {
        return Path.of(System.getProperty("user.home"), ".claude.json");
    }

    @Override public String mcpConfigFormat() { return "claude"; }
}
