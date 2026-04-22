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
}
