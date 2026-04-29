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
        String gitSha
) {
    public SkillVersion {
        skillReferences = skillReferences == null ? List.of() : List.copyOf(skillReferences);
    }

    /** GitHub-registered version: no tarball bytes; pointer + SHA + cached metadata. */
    public static SkillVersion github(String name, String version, String description, double publishedAt,
                                      List<String> skillReferences, String ownerUsername,
                                      String githubUrl, String gitRef, String gitSha) {
        return new SkillVersion(name, version, description, publishedAt,
                null, null, skillReferences, ownerUsername, githubUrl, gitRef, gitSha);
    }

    /** Legacy tarball-published version (file-upload backend). */
    public static SkillVersion tarball(String name, String version, String description, double publishedAt,
                                       String sha256, long sizeBytes, List<String> skillReferences,
                                       String ownerUsername) {
        return new SkillVersion(name, version, description, publishedAt,
                sha256, sizeBytes, skillReferences, ownerUsername, null, null, null);
    }

    /** 8-arg compatibility ctor for callers that predate the github fields. */
    public SkillVersion(String name, String version, String description, double publishedAt,
                        String sha256, long sizeBytes, List<String> skillReferences, String ownerUsername) {
        this(name, version, description, publishedAt, sha256, sizeBytes, skillReferences, ownerUsername,
                null, null, null);
    }

    /** Legacy 7-arg ctor for callers that predate owner attribution. */
    public SkillVersion(String name, String version, String description, double publishedAt,
                        String sha256, long sizeBytes, List<String> skillReferences) {
        this(name, version, description, publishedAt, sha256, sizeBytes, skillReferences, null,
                null, null, null);
    }
}
