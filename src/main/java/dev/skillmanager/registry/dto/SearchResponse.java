package dev.skillmanager.registry.dto;

import java.util.List;

public record SearchResponse(String query, List<SkillSummary> items, int count) {
    public SearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
