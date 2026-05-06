package dev.skillmanager.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Summary of a published unit — what {@code GET /skills} and search return for each hit. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillSummary(
        String name,
        String description,
        String latestVersion,
        List<String> versions,
        /** {@code "skill"} or {@code "plugin"} — set at first publish from the bundle layout. */
        String unitKind
) {
    public SkillSummary {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (unitKind == null || unitKind.isBlank()) unitKind = "skill";
    }
}
