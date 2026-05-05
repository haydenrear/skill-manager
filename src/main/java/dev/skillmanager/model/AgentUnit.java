package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;

/**
 * The shared abstraction over installable units. Both {@link PluginUnit}
 * and {@link SkillUnit} present the same surface — a name, a version,
 * dependency lists, a reference list, and a source path on disk — so
 * commands and effect handlers can operate on units uniformly without
 * branching on kind.
 *
 * <p>For a {@link PluginUnit}, the dependency and reference lists are
 * already <em>unioned</em> across the plugin-level
 * {@code skill-manager-plugin.toml} and every contained skill's
 * {@code skill-manager.toml}. Callers see one flat list per dimension
 * regardless of where any individual entry was declared.
 *
 * <p>For a {@link SkillUnit}, the lists come straight off the underlying
 * {@link Skill} record — no rewriting.
 *
 * <p>Kind-aware dispatch is intentionally rare. The four places that
 * legitimately branch on {@link #kind()} — store directory choice
 * ({@code plugins/} vs {@code skills/}), projector dispatch, scaffold
 * effect selection, and the plugin-uninstall re-walk — each call out
 * the branch in their handler. Anywhere else, branching on
 * {@code kind()} is a smell that the code should be widened to read
 * the field directly off the unit.
 */
public sealed interface AgentUnit permits PluginUnit, SkillUnit {

    String name();

    String version();

    String description();

    UnitKind kind();

    /**
     * For a plugin: union of plugin-level + every contained skill's CLI
     * dependencies. For a skill: the skill's own. Order is stable but
     * not significant — conflict resolution flows through
     * {@code cli-lock.toml} downstream.
     */
    List<CliDependency> cliDependencies();

    /**
     * For a plugin: union of plugin-level + every contained skill's MCP
     * dependencies. For a skill: the skill's own.
     */
    List<McpDependency> mcpDependencies();

    /**
     * For a plugin: union of {@code references} declared at the plugin
     * level + every contained skill's {@code skill_references}. For a
     * skill: the skill's {@code skill_references}.
     *
     * <p>Reference resolution and kind filtering happen at the resolver
     * layer (ticket 04). At parse time these are simply collected.
     */
    List<UnitReference> references();

    Path sourcePath();
}
