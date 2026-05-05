package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link AgentUnit} carrier for a bare skill. Wraps the existing
 * {@link Skill} record so every accessor on the unit interface
 * delegates to the underlying skill's field — no copying, no mutation.
 *
 * <p>This wrapper exists so we can widen call sites from {@code Skill}
 * to {@code AgentUnit} without rewriting the {@code Skill} record
 * itself. Existing skill code keeps using {@code Skill}; new
 * unit-aware code consumes {@code SkillUnit}. The bridge is
 * {@link Skill#asUnit()}.
 */
public record SkillUnit(Skill skill) implements AgentUnit {

    public SkillUnit {
        if (skill == null) throw new IllegalArgumentException("skill must not be null");
    }

    @Override public String name() { return skill.name(); }
    @Override public String version() { return skill.version(); }
    @Override public String description() { return skill.description(); }
    @Override public UnitKind kind() { return UnitKind.SKILL; }
    @Override public List<CliDependency> cliDependencies() { return skill.cliDependencies(); }
    @Override public List<McpDependency> mcpDependencies() { return skill.mcpDependencies(); }
    @Override public List<SkillReference> references() { return skill.skillReferences(); }
    @Override public Path sourcePath() { return skill.sourcePath(); }
}
