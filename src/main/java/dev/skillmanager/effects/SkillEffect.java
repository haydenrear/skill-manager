package dev.skillmanager.effects;

import dev.skillmanager.mcp.GatewayConfig;
import dev.skillmanager.model.Skill;

import java.util.List;

/**
 * Effects are pure data — they describe what should happen, not how.
 * Interpreters give them meaning. New effect types extend the sealed
 * permits list so the compiler enforces that every interpreter handles
 * every effect.
 */
public sealed interface SkillEffect permits
        SkillEffect.ResolveTransitives,
        SkillEffect.InstallToolsAndCli,
        SkillEffect.RegisterMcp,
        SkillEffect.UnregisterMcpOrphan,
        SkillEffect.SyncAgents {

    /** Recursively install missing transitive {@code skill_references}. */
    record ResolveTransitives(List<Skill> skills) implements SkillEffect {}

    /** Build the install plan from {@code skills} and run tool + CLI dep installers. */
    record InstallToolsAndCli(List<Skill> skills) implements SkillEffect {}

    /** Register every skill's MCP deps with the gateway, capturing per-server outcomes. */
    record RegisterMcp(List<Skill> skills, GatewayConfig gateway) implements SkillEffect {}

    /** Unregister an MCP server no surviving skill still declares. */
    record UnregisterMcpOrphan(String serverId, GatewayConfig gateway) implements SkillEffect {}

    /** Refresh agent symlinks + MCP-config entries for every known agent. */
    record SyncAgents(List<Skill> skills, GatewayConfig gateway) implements SkillEffect {}
}
