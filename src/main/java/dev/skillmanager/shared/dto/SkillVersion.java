package dev.skillmanager.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-version metadata. With the GitHub-only publish path the server stores
 * a pointer to a repo + ref + resolved SHA; legacy tarball-publish records
 * (still reachable via {@code skill-registry.publish.allow-file-upload})
 * carry sha256 + sizeBytes instead. Both shapes coexist in the registry.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillVersion(
        String name,
        String version,
        String description,
        double publishedAt,
        String sha256,
        Long sizeBytes,
        List<String> skillReferences,
        String ownerUsername,
        String githubUrl,
        String gitRef,
        String gitSha,
        /**
         * Plugin vs skill — detected from the bundle at publish time
         * (presence of {@code .claude-plugin/plugin.json} → plugin,
         * {@code SKILL.md} at root → skill). Defaults to {@code "skill"}
         * when absent so legacy metadata.json files (predating ticket 18)
         * deserialize cleanly.
         */
        String unitKind
) {
    public SkillVersion {
        skillReferences = skillReferences == null ? List.of() : List.copyOf(skillReferences);
        if (unitKind == null || unitKind.isBlank()) unitKind = "skill";
    }

    /** GitHub-registered version: no tarball bytes; pointer + SHA + cached metadata. */
    public static SkillVersion github(String name, String version, String description, double publishedAt,
                                      List<String> skillReferences, String ownerUsername,
                                      String githubUrl, String gitRef, String gitSha,
                                      String unitKind) {
        return new SkillVersion(name, version, description, publishedAt,
                null, null, skillReferences, ownerUsername, githubUrl, gitRef, gitSha, unitKind);
    }

    /** Legacy tarball-published version (file-upload backend). */
    public static SkillVersion tarball(String name, String version, String description, double publishedAt,
                                       String sha256, long sizeBytes, List<String> skillReferences,
                                       String ownerUsername, String unitKind) {
        return new SkillVersion(name, version, description, publishedAt,
                sha256, sizeBytes, skillReferences, ownerUsername, null, null, null, unitKind);
    }
}
