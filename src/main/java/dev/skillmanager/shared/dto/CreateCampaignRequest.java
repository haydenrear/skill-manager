package dev.skillmanager.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** What clients POST to /ads/campaigns. Server validates + assigns id + timestamp. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateCampaignRequest(
        String sponsor,
        String skillName,
        List<String> keywords,
        List<String> categories,
        long bidCents,
        long dailyBudgetCents,
        String status,
        String notes
) {
    public CreateCampaignRequest {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
