package dev.skillmanager.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.skillmanager.shared.dto.SkillSummary;

import java.util.ArrayList;
import java.util.List;

/** On-disk index entry for a single skill, persisted as {@code <skill>/index.json}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillIndexEntry(
        String name,
        String description,
        List<String> versions
) {
    public SkillIndexEntry {
        versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
    }

    public SkillSummary toSummary() {
        String latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        return new SkillSummary(name, description, latest, List.copyOf(versions));
    }
}
