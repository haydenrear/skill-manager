package dev.skillmanager.agent;

import java.nio.file.Path;

/**
 * Claude Code's on-disk state, as skill-manager addresses it.
 *
 * <p>Claude scatters across {@code <root>/.claude.json} (a file) and
 * {@code <root>/.claude/} (a directory), so there are two spellings of
 * the same location. Both come from {@link AgentHomes#claude()} — the
 * single resolution point — rather than each accessor re-deriving one
 * from {@code CLAUDE_HOME}. See that method for why an independent
 * lookup here leaked skills into the developer's real {@code ~/.claude}
 * whenever a caller set only {@code CLAUDE_CONFIG_DIR}.
 */
public final class ClaudeAgent implements Agent {

    @Override public String id() { return "claude"; }

    @Override
    public Path skillsDir() {
        return AgentHomes.claude().configDir().resolve("skills");
    }

    @Override
    public Path pluginsDir() {
        return AgentHomes.claude().configDir().resolve("plugins");
    }

    @Override
    public Path mcpConfigPath() {
        return AgentHomes.claude().root().resolve(".claude.json");
    }

    @Override public String mcpConfigFormat() { return "claude"; }
}
