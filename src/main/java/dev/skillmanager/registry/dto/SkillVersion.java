package dev.skillmanager.registry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Per-version metadata record stored alongside each skill tarball. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillVersion(
        String name,
        String version,
        String description,
        double publishedAt,
        String sha256,
        long sizeBytes,
        List<String> skillReferences,
        String ownerUsername
) {
    public SkillVersion {
        skillReferences = skillReferences == null ? List.of() : List.copyOf(skillReferences);
    }

    /** Legacy 7-arg ctor for callers that predate owner attribution. */
    public SkillVersion(String name, String version, String description, double publishedAt,
                        String sha256, long sizeBytes, List<String> skillReferences) {
        this(name, version, description, publishedAt, sha256, sizeBytes, skillReferences, null);
    }
}
