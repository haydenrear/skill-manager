package dev.skillmanager.registry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One sponsored slot in a search result set. Always lives in a separate
 * {@code sponsored} array — never mixed into organic {@code items}.
 *
 * <p>The {@link #reason} is the "why am I seeing this ad" — a short human
 * readable string like {@code "matched keyword: code-review"}, so the CLI
 * and agents can surface it for transparency.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SponsoredPlacement(
        String name,
        String description,
        String latestVersion,
        String sponsor,
        String campaignId,
        String reason,
        long bidCents
) {}
