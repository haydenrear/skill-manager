package dev.skillmanager.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Search response with separate organic and sponsored lanes.
 *
 * <p>Invariants the server guarantees:
 * <ul>
 *   <li>{@link #items} is the organic ranking, computed independently of
 *       any campaigns. Sponsored placements never displace or reorder it.</li>
 *   <li>{@link #sponsored} is always a distinct array. Clients must render
 *       it with a visible "sponsored" marker.</li>
 *   <li>{@code no_ads=true} at query time forces {@link #sponsored} to be
 *       empty regardless of active campaigns.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchResponse(
        String query,
        List<SkillSummary> items,
        int count,
        List<SponsoredPlacement> sponsored,
        int sponsoredCount
) {
    public SearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
        sponsored = sponsored == null ? List.of() : List.copyOf(sponsored);
    }
}
