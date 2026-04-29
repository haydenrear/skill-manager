package dev.skillmanager.shared.dto;

import java.util.List;

public record ListResponse(List<SkillSummary> items, int count) {
    public ListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
