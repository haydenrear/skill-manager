package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AgentUnit} carrier for a plugin. The dependency and reference
 * lists are pre-unioned across the plugin-level
 * {@code skill-manager-plugin.toml} and every contained skill's
 * {@code skill-manager.toml} — the planner sees one flat list per
 * dimension regardless of where any entry originated.
 *
 * <p>{@link #containedSkills()} is exposed for plan-print and for the
 * uninstall re-walk (ticket 09): on uninstall, the command re-parses
 * the plugin from disk so {@code *_IfOrphan} compensations see every
 * dep the plugin currently owns, not just the ones declared at the
 * plugin level.
 *
 * <p>{@link #declaredMcpServers()} carries the {@code mcpServers}
 * field from {@code .claude-plugin/plugin.json}. It is captured at
 * parse time and consumed at plan time (ticket 09) to emit the
 * "MCP server declared in both .mcp.json and skill-manager-plugin.toml"
 * warning. Skill-manager never registers anything from this map with
 * the gateway — it's harness-facing, recorded for diagnostics only.
 *
 * <p>{@link #warnings()} accumulates parse-time warnings (currently:
 * identity drift between {@code plugin.json} and
 * {@code skill-manager-plugin.toml}). They are surfaced by callers at
 * plan time; the parse itself does not throw on warnings.
 */
public record PluginUnit(
        String name,
        String version,
        String description,
        List<CliDependency> cliDependencies,
        List<McpDependency> mcpDependencies,
        List<UnitReference> references,
        List<ContainedSkill> containedSkills,
        Map<String, Object> declaredMcpServers,
        List<String> warnings,
        Path sourcePath
) implements AgentUnit {

    public PluginUnit {
        cliDependencies = cliDependencies == null ? List.of() : List.copyOf(cliDependencies);
        mcpDependencies = mcpDependencies == null ? List.of() : List.copyOf(mcpDependencies);
        references = references == null ? List.of() : List.copyOf(references);
        containedSkills = containedSkills == null ? List.of() : List.copyOf(containedSkills);
        declaredMcpServers = declaredMcpServers == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(declaredMcpServers));
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    @Override public UnitKind kind() { return UnitKind.PLUGIN; }

    /**
     * Compute the unioned CLI dependency list given the plugin-level
     * deps and the contained skills. Order: plugin-level entries
     * first (preserving declaration order), then contained-skill
     * entries in directory iteration order. Duplicates are kept —
     * conflict detection runs at planner time against
     * {@code cli-lock.toml}.
     */
    public static List<CliDependency> unionCli(
            List<CliDependency> pluginLevel,
            List<ContainedSkill> contained
    ) {
        List<CliDependency> out = new ArrayList<>(pluginLevel == null ? List.of() : pluginLevel);
        if (contained != null) for (ContainedSkill cs : contained) out.addAll(cs.cliDependencies());
        return out;
    }

    /** Same as {@link #unionCli} for MCP dependencies. */
    public static List<McpDependency> unionMcp(
            List<McpDependency> pluginLevel,
            List<ContainedSkill> contained
    ) {
        List<McpDependency> out = new ArrayList<>(pluginLevel == null ? List.of() : pluginLevel);
        if (contained != null) for (ContainedSkill cs : contained) out.addAll(cs.mcpDependencies());
        return out;
    }

    /** Same as {@link #unionCli} for references. */
    public static List<UnitReference> unionRefs(
            List<UnitReference> pluginLevel,
            List<ContainedSkill> contained
    ) {
        List<UnitReference> out = new ArrayList<>(pluginLevel == null ? List.of() : pluginLevel);
        if (contained != null) for (ContainedSkill cs : contained) out.addAll(cs.references());
        return out;
    }
}
