package dev.skillmanager.agent;

import java.nio.file.Path;
import java.util.List;

public sealed interface Agent permits ClaudeAgent, CodexAgent {

    String id();

    Path skillsDir();

    /**
     * Directory containing this agent's plugin entries. Plugin install
     * symlinks (and eventually projector-rendered config) land under
     * {@code pluginsDir().resolve(<plugin-name>)}. Symmetrical with
     * {@link #skillsDir()}; ticket 11 (Projector) replaces the direct
     * symlink in {@link dev.skillmanager.effects.SkillEffect.SyncAgents}'s
     * handler with a projector call.
     */
    Path pluginsDir();

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
