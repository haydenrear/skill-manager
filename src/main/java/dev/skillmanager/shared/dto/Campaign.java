package dev.skillmanager.shared.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * An advertiser's bid to place {@link #skillName} into a sponsored slot for
 * queries matching any of its {@link #keywords} or {@link #categories}.
 *
 * <p>Shared DTO: the server persists this record as-is, the client
 * deserializes it from GET responses.
 *
 * <p>Sums of money are represented as integer cents to dodge floating-point
 * artifacts at ranking time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Campaign(
        String id,
        String sponsor,
        String skillName,
        List<String> keywords,
        List<String> categories,
        long bidCents,
        long dailyBudgetCents,
        String status,
        double createdAt,
        String notes
) {
    public Campaign {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_EXHAUSTED = "exhausted";
}
