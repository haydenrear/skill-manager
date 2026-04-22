package dev.skillmanager.agent;

import java.nio.file.Path;
import java.util.List;

public sealed interface Agent permits ClaudeAgent, CodexAgent {

    String id();

    Path skillsDir();

    Path mcpConfigPath();

    String mcpConfigFormat();

    static List<Agent> all() {
        return List.of(new ClaudeAgent(), new CodexAgent());
    }

    static Agent byId(String id) {
        for (Agent a : all()) if (a.id().equalsIgnoreCase(id)) return a;
        throw new IllegalArgumentException("Unknown agent: " + id);
    }
}
