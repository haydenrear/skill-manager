package dev.skillmanager.registry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Summary of a published skill — what {@code GET /skills} and search return for each hit. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillSummary(
        String name,
        String description,
        String latestVersion,
        List<String> versions
) {
    public SkillSummary {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
