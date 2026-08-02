package dev.skillmanager.agent;

import java.nio.file.Path;

/**
 * Claude Code's on-disk state, as skill-manager addresses it.
 *
 * <p>Claude scatters across a {@code .claude.json} file and a
 * {@code .claude/} directory, so there are several spellings of the same
 * location. All of them come from {@link AgentHomes#claude()} — the single
 * resolution point — rather than each accessor re-deriving one from
 * {@code CLAUDE_HOME}. See that method for why an independent lookup here
 * leaked skills into the developer's real {@code ~/.claude} whenever a caller
 * set only {@code CLAUDE_CONFIG_DIR}, and see
 * {@link AgentHomes.ClaudeHome} for why the JSON file is not simply
 * {@code root/.claude.json}.
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
        return AgentHomes.claude().configFile();
    }

    @Override public String mcpConfigFormat() { return "claude"; }
}
