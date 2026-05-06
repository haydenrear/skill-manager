package dev.skillmanager.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.shared.dto.SkillSummary;

import java.util.ArrayList;
import java.util.List;

/** On-disk index entry for a single unit, persisted as {@code <name>/index.json}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillIndexEntry(
        String name,
        String description,
        List<String> versions,
        /** {@code "skill"} or {@code "plugin"} — fixed at first publish, immutable thereafter. */
        String unitKind
) {
    public SkillIndexEntry {
        versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
        if (unitKind == null || unitKind.isBlank()) unitKind = "skill";
    }

    public SkillSummary toSummary() {
        String latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        return new SkillSummary(name, description, latest, List.copyOf(versions), unitKind);
    }
}
