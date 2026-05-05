package dev.skillmanager.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record Skill(
        String name,
        String description,
        String version,
        List<CliDependency> cliDependencies,
        List<SkillReference> skillReferences,
        List<McpDependency> mcpDependencies,
        Map<String, Object> rawFrontmatter,
        String body,
        Path sourcePath
) {
    public Skill {
        cliDependencies = cliDependencies == null ? List.of() : List.copyOf(cliDependencies);
        skillReferences = skillReferences == null ? List.of() : List.copyOf(skillReferences);
        mcpDependencies = mcpDependencies == null ? List.of() : List.copyOf(mcpDependencies);
        rawFrontmatter = rawFrontmatter == null ? Map.of() : Map.copyOf(rawFrontmatter);
    }

    /**
     * Wrap this skill in an {@link AgentUnit} carrier. New code that
     * operates on units uniformly should consume the result; old code
     * that depends on the {@code Skill} record continues to work
     * unchanged.
     */
    public SkillUnit asUnit() {
        return new SkillUnit(this);
    }
}
